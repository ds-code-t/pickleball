package dev.domengine.desktop;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live Windows attach. A structure change on a real window drops the cached tree
 * through the UI Automation listener, and close stops that listener.
 */
class WindowsStructureLiveTest {
    private Process window;
    private Path stopFile;

    @AfterEach
    void reset() {
        if (stopFile != null) {
            try {
                Files.writeString(stopFile, "stop");
            } catch (Exception ignored) {
                // The process is destroyed below.
            }
        }
        if (window != null) {
            window.destroy();
            try {
                if (!window.waitFor(3, TimeUnit.SECONDS)) window.destroyForcibly();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                window.destroyForcibly();
            }
        }
        DesktopTree.resetForTests();
        System.clearProperty("pkb_warm");
        System.clearProperty("pkb_engine_log");
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void structureChangeDropsTheCachedTree(@TempDir Path dir) throws Exception {
        String title = "pkb-r8-struct-" + Long.toUnsignedString(System.nanoTime());
        Path ready = dir.resolve("ready");
        Path add = dir.resolve("add");
        Path added = dir.resolve("added");
        stopFile = dir.resolve("stop");
        window = openWindow(dir, title, ready, add, added, stopFile);
        assertTrue(waitFor(ready, 20_000), () -> "window did not appear: " + peek(window));

        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_engine_log", "gate");
        DesktopTree.useHeapForTests(0, 100);
        DesktopTree.Session session = DesktopTree.attachLive("uia-live", title);
        assertTrue(session.live());
        assertTrue(await(15_000, () -> WindowsStructureEvents.listening(title)), "listener did not start");
        assertTrue(await(25_000, () -> WindowsStructureEvents.subscribed(title)), "listener did not subscribe");

        AtomicInteger loads = new AtomicInteger();
        DesktopTree.cachedTree(title, 10, () -> {
            loads.incrementAndGet();
            return new DomNode();
        });
        assertEquals(1, loads.get());

        Files.writeString(add, "add");
        assertTrue(waitFor(added, 10_000), () -> "window did not add a control: " + peek(window));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && loads.get() < 2) {
            DesktopTree.cachedTree(title, 10, () -> {
                loads.incrementAndGet();
                return new DomNode();
            });
            if (loads.get() >= 2) break;
            Thread.sleep(250);
        }
        assertEquals(2, loads.get(), "cached tree was not dropped after the structure change");

        DesktopTree.close(session.id());
        assertFalse(WindowsStructureEvents.listening(title), "detach left the listener running");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void missingWindowIsNotWatched() throws Exception {
        String name = "pkb-r8-missing-" + Long.toUnsignedString(System.nanoTime());
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            WindowsStructureEvents.start(name);
            assertTrue(await(20_000, () -> {
                String text = buffer.toString(StandardCharsets.UTF_8);
                return text.contains("error: window not found: " + name)
                        && text.contains("fix:")
                        && text.contains(name)
                        && !WindowsStructureEvents.listening(name)
                        && !WindowsStructureEvents.subscribed(name);
            }), () -> buffer.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(previous);
            WindowsStructureEvents.stop(name);
        }
    }

    private static Process openWindow(Path dir, String title, Path ready, Path add, Path added, Path stop) throws Exception {
        Path script = dir.resolve("window.ps1");
        Files.writeString(script, """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -AssemblyName System.Drawing
                Add-Type -TypeDefinition @"
                using System;
                using System.Runtime.InteropServices;
                public static class PkbAddButton {
                  [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
                  public static extern IntPtr CreateWindowEx(int ex, string cls, string title, int style, int x, int y, int w, int h, IntPtr parent, IntPtr menu, IntPtr inst, IntPtr param);
                  [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
                  public static extern IntPtr GetModuleHandle(string name);
                  public static void Add(IntPtr parent) {
                    IntPtr hwnd = CreateWindowEx(0, "BUTTON", "added", 0x50000000, 12, 12, 80, 24, parent, new IntPtr(2), GetModuleHandle(null), IntPtr.Zero);
                    if (hwnd == IntPtr.Zero) throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
                  }
                }
                "@
                $form = New-Object System.Windows.Forms.Form
                $form.Text = $env:PKB_WINDOW_TITLE
                $form.StartPosition = 'Manual'
                $form.Location = New-Object System.Drawing.Point(160, 160)
                $form.ClientSize = New-Object System.Drawing.Size(320, 180)
                $timer = New-Object System.Windows.Forms.Timer
                $timer.Interval = 200
                $script:added = $false
                $timer.Add_Tick({
                  if (-not $script:added -and (Test-Path -LiteralPath $env:PKB_ADD_FILE)) {
                    $script:added = $true
                    [PkbAddButton]::Add($form.Handle)
                    Set-Content -LiteralPath $env:PKB_ADDED_FILE -Value 'added' -Encoding ascii
                  }
                  if (Test-Path -LiteralPath $env:PKB_STOP_FILE) {
                    $timer.Stop()
                    $form.Close()
                  }
                })
                $form.Add_Shown({
                  $form.Activate()
                  Set-Content -LiteralPath $env:PKB_READY_FILE -Value 'ready' -Encoding ascii
                  $timer.Start()
                })
                [System.Windows.Forms.Application]::Run($form)
                """);
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-STA", "-File", script.toAbsolutePath().toString());
        builder.environment().put("PKB_WINDOW_TITLE", title);
        builder.environment().put("PKB_READY_FILE", ready.toAbsolutePath().toString());
        builder.environment().put("PKB_ADD_FILE", add.toAbsolutePath().toString());
        builder.environment().put("PKB_ADDED_FILE", added.toAbsolutePath().toString());
        builder.environment().put("PKB_STOP_FILE", stop.toAbsolutePath().toString());
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static boolean waitFor(Path file, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) return true;
            Thread.sleep(200);
        }
        return Files.isRegularFile(file);
    }

    private static boolean await(long millis, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(200);
        }
        return condition.getAsBoolean();
    }

    /** Reads bytes already buffered. A blocking read would hang on the live window process. */
    private static String peek(Process process) {
        if (process == null) return "";
        try {
            InputStream stream = process.getInputStream();
            int available = stream.available();
            if (available <= 0) return "";
            byte[] bytes = stream.readNBytes(Math.min(available, 4_000));
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return failure.getMessage() == null ? "" : failure.getMessage();
        }
    }
}
