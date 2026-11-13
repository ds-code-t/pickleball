package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupLifecycleTest {
    @TempDir
    Path project;

    private final List<Process> processes = new ArrayList<>();
    private String browser;
    private String onTop;
    private String headless;
    private String idle;

    @AfterEach
    void cleanup() {
        restore("pkb_agent_popup_browser", browser);
        restore("pkb_agent_popup_on_top", onTop);
        restore("pkb_agent_popup_headless", headless);
        restore("pkb_agent_popup_idle_minutes", idle);
        for (Process process : processes) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    @Test
    void fakeBrowserRecordsArgvReusesAndObeysExitRules() throws Exception {
        browser = System.getProperty("pkb_agent_popup_browser");
        onTop = System.getProperty("pkb_agent_popup_on_top");
        headless = System.getProperty("pkb_agent_popup_headless");
        idle = System.getProperty("pkb_agent_popup_idle_minutes");
        Path script = project.resolve("fake-browser.sh");
        Files.writeString(script, """
                #!/bin/sh
                printf '%s\\n' "$@" >> "$(dirname "$0")/argv.txt"
                if [ "$1" = "--exit-now" ]; then exit 0; fi
                trap 'exit 0' TERM INT
                while true; do sleep 0.2; done
                """);
        script.toFile().setExecutable(true);
        System.setProperty("pkb_agent_popup_browser", script.toString());
        System.setProperty("pkb_agent_popup_on_top", "false");
        System.setProperty("pkb_agent_popup_headless", "true");
        System.setProperty("pkb_agent_popup_idle_minutes", "30");

        Map<String, Object> first = PopupHost.open(project, "reuse");
        assertEquals("open", String.valueOf(first.get("state")), String.valueOf(first));
        Map<String, Object> second = PopupHost.open(project, "reuse");
        assertEquals(true, second.get("reused"));
        String argv = Files.readString(project.resolve("argv.txt"));
        assertTrue(argv.contains("--app=http://127.0.0.1:"), argv);
        assertTrue(argv.contains("--user-data-dir="), argv);
        assertTrue(argv.contains("profile"), argv);
        PopupLedger.append(project, "reuse", "close", "", "", "", "", "", "");
        assertTrue(waitGone(project, "reuse"));
        assertFalse(Files.exists(PopupLog.popupDir(project, "reuse").resolve("profile")));

        Path exitBrowser = project.resolve("exit-browser.sh");
        Files.writeString(exitBrowser, """
                #!/bin/sh
                printf '%s\\n' "$@" >> "$(dirname "$0")/exit-argv.txt"
                exit 0
                """);
        exitBrowser.toFile().setExecutable(true);
        System.setProperty("pkb_agent_popup_browser", exitBrowser.toString());
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread exited = new Thread(() -> {
            try {
                PopupHostMain.serve(project, "bye", "cli", ProcessHandle.current().pid());
            } catch (Exception error) {
                failure.set(error);
            }
        });
        exited.start();
        exited.join(15_000);
        assertFalse(exited.isAlive());
        assertEquals(null, failure.get());
        assertEquals("closed-by-user", String.valueOf(PopupStatus.read(project, "bye").get("state")));

        System.setProperty("pkb_agent_popup_browser", script.toString());
        Process owner = new ProcessBuilder("sleep", "30").start();
        processes.add(owner);
        Thread hosted = new Thread(() -> {
            try {
                PopupHostMain.serve(project, "owner", "run", owner.pid());
            } catch (Exception error) {
                failure.set(error);
            }
        });
        hosted.start();
        assertTrue(waitOpen(project, "owner"));
        owner.destroyForcibly();
        hosted.join(10_000);
        assertFalse(hosted.isAlive(), "owner-pid host did not exit");

        System.setProperty("pkb_agent_popup_idle_minutes", "0.05");
        assertEquals(60_000L, minutes("1"));
        Thread idled = new Thread(() -> {
            try {
                PopupHostMain.serve(project, "idle", "cli", ProcessHandle.current().pid());
            } catch (Exception error) {
                failure.set(error);
            }
        });
        idled.start();
        idled.join(15_000);
        assertFalse(idled.isAlive(), "idle host did not exit");
        assertEquals(null, failure.get());
        assertNoPopupHost(project);
    }

    private static long minutes(String raw) {
        String previous = System.getProperty("pkb_agent_popup_idle_minutes");
        System.setProperty("pkb_agent_popup_idle_minutes", raw);
        try {
            return PopupConfig.idleMillis();
        } finally {
            if (previous == null) System.clearProperty("pkb_agent_popup_idle_minutes");
            else System.setProperty("pkb_agent_popup_idle_minutes", previous);
        }
    }

    private static boolean waitOpen(Path project, String runId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if ("open".equals(String.valueOf(PopupStatus.read(project, runId).get("state")))) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean waitGone(Path project, String runId) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            Object pid = PopupStatus.read(project, runId).get("pid");
            boolean alive = pid instanceof Number && ProcessHandle.of(((Number) pid).longValue()).isPresent();
            if (!alive && !Files.exists(PopupLog.popupDir(project, runId).resolve("profile"))) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static void assertNoPopupHost(Path project) {
        String marker = project.toAbsolutePath().toString();
        ProcessHandle.allProcesses().forEach(handle -> {
            String command = handle.info().commandLine().orElse("");
            if (command.contains("PopupHostMain") && command.contains(marker)) {
                handle.destroyForcibly();
                throw new AssertionError("orphan PopupHostMain " + handle.pid());
            }
        });
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
