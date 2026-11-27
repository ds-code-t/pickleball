package dev.domengine.desktop;

import dev.domengine.log.EngineLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Subscribes one Windows UI Automation structure-changed listener per attached window.
 * The listener process writes {@code changed}; {@link #onStructureChanged(String)} drops that window's cached tree.
 * The handler is C# so the callback can run on the UI Automation thread.
 * Other platforms do not start a process. A listener that fails to start does not fail attach.
 */
final class WindowsStructureEvents {
    private static final ConcurrentHashMap<String, Listener> LISTENERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> SUBSCRIBED = new ConcurrentHashMap<>();

    private record Listener(Process process, Path script) {
    }

    private WindowsStructureEvents() {
    }

    /** Starts the listener for {@code windowId}. A second start for the same id keeps the first process. */
    static void start(String windowId) {
        start(windowId, windowId);
    }

    /**
     * Starts the listener for {@code windowId}. {@code title} is the window to subscribe.
     * An empty title subscribes to the desktop root. A title that matches no window does not.
     */
    static void start(String windowId, String title) {
        if (!"windows".equals(NativeDesktop.family())) return;
        String key = key(windowId);
        if (LISTENERS.containsKey(key)) return;
        Path script = null;
        try {
            script = writeScript(key);
            ProcessBuilder builder = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                    "-STA", "-File", script.toAbsolutePath().toString());
            builder.redirectErrorStream(true);
            builder.environment().put("DESKTOP_UIA_WINDOW", key);
            builder.environment().put("DESKTOP_UIA_TITLE", title == null ? "" : title);
            Process process = builder.start();
            Listener created = new Listener(process, script);
            Listener existing = LISTENERS.putIfAbsent(key, created);
            if (existing != null) {
                process.destroyForcibly();
                deleteScript(script);
                return;
            }
            Thread reader = new Thread(() -> drain(key, process), "uia-structure-" + Integer.toHexString(key.hashCode()));
            reader.setDaemon(true);
            reader.start();
        } catch (Exception failure) {
            deleteScript(script);
            // Attach still succeeds when the listener cannot start.
        }
    }

    /** Entry the native callback invokes. Drops the cached tree for that window. */
    static void onStructureChanged(String windowId) {
        DesktopTree.structureChanged(windowId);
    }

    static boolean listening(String windowId) {
        Listener listener = LISTENERS.get(key(windowId));
        return listener != null && listener.process.isAlive();
    }

    /** True after the C# handler is registered for this window. */
    static boolean subscribed(String windowId) {
        return Boolean.TRUE.equals(SUBSCRIBED.get(key(windowId)));
    }

    static void stop(String windowId) {
        stopKey(key(windowId));
    }

    static void stopAll() {
        for (String id : List.copyOf(LISTENERS.keySet())) {
            stopKey(id);
        }
    }

    private static void stopKey(String key) {
        SUBSCRIBED.remove(key);
        Listener listener = LISTENERS.remove(key);
        if (listener == null) return;
        listener.process.destroyForcibly();
        deleteScript(listener.script);
    }

    private static void drain(String key, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.trim();
                if ("changed".equals(text)) {
                    onStructureChanged(key);
                } else if ("subscribed".equals(text)) {
                    SUBSCRIBED.put(key, true);
                } else if (text.startsWith("window-missing")) {
                    String title = text.substring("window-missing".length()).trim();
                    reportMissing(title.isEmpty() ? key : title);
                    stopKey(key);
                } else if (text.startsWith("listener-error")) {
                    System.err.println("error: structure listener failed: " + text);
                    System.err.println("fix: keep the window open while the warm attach subscribes");
                }
            }
        } catch (Exception ignored) {
            // The process exit is the unsubscribe.
        }
    }

    private static void reportMissing(String name) {
        String error = "error: window not found: " + name;
        String fix = "fix: attach a visible window whose title contains " + name;
        System.err.println(error);
        System.err.println(fix);
        EngineLog.error("desktop", error);
        EngineLog.error("desktop", fix);
        try {
            Class<?> log = Class.forName("tools.dscode.engine.EngineLog");
            java.lang.reflect.Method method = log.getMethod(
                    "log", Path.class, String.class, String.class, String.class);
            Path project = Path.of("").toAbsolutePath();
            method.invoke(null, project, "desktop", "desktop", error);
            method.invoke(null, project, "desktop", "desktop", fix);
        } catch (ReflectiveOperationException ignored) {
            // dom-engine tests read stderr. The pickleball log is optional here.
        }
    }

    private static Path writeScript(String key) throws IOException {
        Path dir = Path.of(".pickleball", "desktop");
        Files.createDirectories(dir);
        String safe = Integer.toHexString(key.hashCode());
        Path file = dir.resolve("structure-" + safe + "-" + Long.toUnsignedString(System.nanoTime()) + ".ps1");
        Files.writeString(file, script(), StandardCharsets.US_ASCII);
        return file;
    }

    private static void deleteScript(Path script) {
        if (script == null) return;
        try {
            Files.deleteIfExists(script);
        } catch (IOException ignored) {
            // The process may still be releasing the file.
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
                  Add-Type -AssemblyName UIAutomationTypes
                  $refs = @(
                    [System.Windows.Automation.AutomationElement].Assembly.Location
                    [System.Windows.Automation.StructureChangedEventArgs].Assembly.Location
                  )
                  Add-Type -ReferencedAssemblies $refs -TypeDefinition @"
                using System;
                using System.Windows.Automation;
                public static class PkbStructureChanged {
                  public static StructureChangedEventHandler Handler;
                  public static void Handle(object sender, StructureChangedEventArgs e) {
                    try {
                      Console.Out.WriteLine("changed");
                      Console.Out.Flush();
                    } catch (Exception) {
                    }
                  }
                }
                "@
                  Add-Type -TypeDefinition @"
                using System;
                using System.Text;
                using System.Runtime.InteropServices;
                public static class PkbPickStructure {
                  public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
                  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
                  [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
                  public static IntPtr Found;
                  public static string Want;
                  static EnumProc callback;
                  public static bool Visit(IntPtr hWnd, IntPtr lParam) {
                    StringBuilder sb = new StringBuilder(512);
                    GetWindowText(hWnd, sb, sb.Capacity);
                    string title = sb.ToString();
                    if (title.Length == 0 || string.IsNullOrEmpty(Want)) return true;
                    if (title.IndexOf(Want, StringComparison.OrdinalIgnoreCase) < 0) return true;
                    Found = hWnd;
                    return false;
                  }
                  public static IntPtr Pick(string want) {
                    Want = want;
                    Found = IntPtr.Zero;
                    callback = Visit;
                    EnumWindows(callback, IntPtr.Zero);
                    return Found;
                  }
                }
                "@
                  $title = [string]$env:DESKTOP_UIA_TITLE
                  $root = [System.Windows.Automation.AutomationElement]::RootElement
                  $target = $null
                  if ([string]::IsNullOrWhiteSpace($title)) {
                    $target = $root
                  } else {
                    $hwnd = [PkbPickStructure]::Pick($title)
                    if ($hwnd -eq [IntPtr]::Zero) {
                      Write-Output ("window-missing " + $title)
                    } else {
                      $target = [System.Windows.Automation.AutomationElement]::FromHandle($hwnd)
                      if ($null -eq $target) { Write-Output ("window-missing " + $title) }
                    }
                  }
                  if ($null -ne $target) {
                    $method = [PkbStructureChanged].GetMethod('Handle')
                    $handler = [System.Delegate]::CreateDelegate([System.Windows.Automation.StructureChangedEventHandler], $method)
                    [PkbStructureChanged]::Handler = $handler
                    [System.Windows.Automation.Automation]::AddStructureChangedEventHandler($target, [System.Windows.Automation.TreeScope]::Subtree, $handler)
                    Write-Output 'subscribed'
                  }
                } catch {
                  Write-Output ("listener-error " + $_.Exception.Message)
                }
                while ($true) { Start-Sleep -Seconds 30 }
                """;
    }
}
