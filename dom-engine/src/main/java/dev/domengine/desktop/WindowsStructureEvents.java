package dev.domengine.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscribes one Windows UI Automation structure-changed listener per attached window.
 * The listener process writes {@code changed}; {@link #onStructureChanged(String)} drops that window's cached tree.
 * Other platforms do not start a process. A listener that fails to start does not fail attach.
 */
final class WindowsStructureEvents {
    private static final ConcurrentHashMap<String, Process> LISTENERS = new ConcurrentHashMap<>();

    private WindowsStructureEvents() {
    }

    /** Starts the listener for {@code windowId}. A second start for the same id keeps the first process. */
    static void start(String windowId) {
        if (!"windows".equals(NativeDesktop.family())) return;
        String key = key(windowId);
        if (LISTENERS.containsKey(key)) return;
        try {
            String encoded = Base64.getEncoder().encodeToString(
                    script().getBytes(StandardCharsets.UTF_16LE));
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand", encoded);
            builder.redirectErrorStream(true);
            builder.environment().put("DESKTOP_UIA_WINDOW", key);
            Process process = builder.start();
            Process existing = LISTENERS.putIfAbsent(key, process);
            if (existing != null) {
                process.destroyForcibly();
                return;
            }
            Thread reader = new Thread(() -> drain(key, process), "uia-structure-" + Integer.toHexString(key.hashCode()));
            reader.setDaemon(true);
            reader.start();
        } catch (Exception failure) {
            // Attach still succeeds when the listener cannot start.
        }
    }

    /** Entry the native callback invokes. Drops the cached tree for that window. */
    static void onStructureChanged(String windowId) {
        DesktopTree.structureChanged(windowId);
    }

    static boolean listening(String windowId) {
        Process process = LISTENERS.get(key(windowId));
        return process != null && process.isAlive();
    }

    static void stop(String windowId) {
        Process process = LISTENERS.remove(key(windowId));
        if (process != null) process.destroyForcibly();
    }

    static void stopAll() {
        for (String id : List.copyOf(LISTENERS.keySet())) {
            Process process = LISTENERS.remove(id);
            if (process != null) process.destroyForcibly();
        }
    }

    private static void drain(String key, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if ("changed".equals(line.trim())) onStructureChanged(key);
            }
        } catch (Exception ignored) {
            // The process exit is the unsubscribe.
        }
    }

    private static String key(String windowId) {
        return windowId == null || windowId.isBlank() ? "desktop" : windowId;
    }

    private static String script() {
        return """
                $ErrorActionPreference = 'Continue'
                try {
                  Add-Type -AssemblyName UIAutomationClient
                  $name = $env:DESKTOP_UIA_WINDOW
                  $root = [System.Windows.Automation.AutomationElement]::RootElement
                  $target = $root
                  if ($name) {
                    $cond = New-Object System.Windows.Automation.PropertyCondition ([System.Windows.Automation.AutomationElement]::NameProperty, $name)
                    $found = $root.FindFirst([System.Windows.Automation.TreeScope]::Children, $cond)
                    if ($null -ne $found) { $target = $found }
                  }
                  $handler = [System.Windows.Automation.StructureChangedEventHandler] {
                    param($sender, $e)
                    [Console]::Out.WriteLine('changed')
                    [Console]::Out.Flush()
                  }
                  [System.Windows.Automation.Automation]::AddStructureChangedEventHandler($target, [System.Windows.Automation.TreeScope]::Subtree, $handler)
                } catch {
                  [Console]::Out.WriteLine('listener-error')
                  [Console]::Out.Flush()
                }
                while ($true) { Start-Sleep -Seconds 30 }
                """;
    }
}
