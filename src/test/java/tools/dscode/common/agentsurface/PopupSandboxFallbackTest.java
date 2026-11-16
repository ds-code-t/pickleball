package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chrome for Testing on Ubuntu 24.04 CI exits at launch with "No usable sandbox!" because the
 * runner has no SUID sandbox helper for it. The popup rendered nothing. The host now relaunches
 * once with {@code --no-sandbox} after that exact failure, and only then.
 */
class PopupSandboxFallbackTest {
    @TempDir
    Path project;

    private final String browser = System.getProperty("pkb_agent_popup_browser");
    private final String onTop = System.getProperty("pkb_agent_popup_on_top");
    private final String headless = System.getProperty("pkb_agent_popup_headless");
    private final String noSandbox = System.getProperty("pkb_agent_popup_no_sandbox");

    @AfterEach
    void restore() {
        restore("pkb_agent_popup_browser", browser);
        restore("pkb_agent_popup_on_top", onTop);
        restore("pkb_agent_popup_headless", headless);
        restore("pkb_agent_popup_no_sandbox", noSandbox);
    }

    @Test
    void argvAddsNoSandboxOnlyWhenAsked() {
        Path profile = Path.of("profile");
        List<String> plain = PopupLauncher.argv("chrome", "http://127.0.0.1:1/", profile, 420, 640, 0, 0, true);
        List<String> relaunch = PopupLauncher.argv("chrome", "http://127.0.0.1:1/", profile, 420, 640, 0, 0, true, true);
        assertFalse(plain.contains("--no-sandbox"), plain.toString());
        assertTrue(relaunch.contains("--no-sandbox"), relaunch.toString());
        assertEquals("--headless=new", relaunch.get(relaunch.size() - 1));
    }

    @Test
    void sandboxFailureMatchesChromiumSandboxErrorsOnly(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("browser.out");
        assertFalse(PopupLauncher.sandboxFailure(out));
        Files.writeString(out, "[123:123:1116/014800.1:ERROR:bus.cc(407)] Failed to connect to the bus\n");
        assertFalse(PopupLauncher.sandboxFailure(out));
        Files.writeString(out, "[4521:4521:1116/014801.2:FATAL:zygote_host_impl_linux.cc(128)] No usable sandbox! "
                + "If you are running on Ubuntu 23.10+ or another Linux distro that has disabled unprivileged user namespaces with AppArmor, see ...\n");
        assertTrue(PopupLauncher.sandboxFailure(out));
        assertTrue(PopupLauncher.sandboxLine(out).contains("No usable sandbox"));
        Files.writeString(out, "Running as root without --no-sandbox is not supported. See https://crbug.com/638180.\n");
        assertTrue(PopupLauncher.sandboxFailure(out));
    }

    @Test
    void zombieIsFalseForALiveProcess() {
        assertFalse(PopupHost.zombie(ProcessHandle.current().pid()));
    }

    @Test
    void hostRelaunchesWithoutSandboxAfterASandboxExitAndCloses() throws Exception {
        Assumptions.assumeFalse(PopupHost.windows(), "the fake browser is a POSIX shell script");
        Path script = project.resolve("sandbox-browser.sh");
        Files.writeString(script, """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$(dirname "$0")/launches.txt"
                case " $* " in
                  *" --no-sandbox "*) ;;
                  *) echo "[1:1:0101/000000.0:FATAL:zygote_host_impl_linux.cc(128)] No usable sandbox!" >&2; exit 133 ;;
                esac
                trap 'exit 0' TERM INT
                while true; do sleep 0.2; done
                """);
        assertTrue(script.toFile().setExecutable(true));
        System.setProperty("pkb_agent_popup_browser", script.toString());
        System.setProperty("pkb_agent_popup_on_top", "false");
        System.setProperty("pkb_agent_popup_headless", "true");
        System.clearProperty("pkb_agent_popup_no_sandbox");

        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread host = new Thread(() -> {
            try {
                PopupHostMain.serve(project, "sbx", "cli", ProcessHandle.current().pid());
            } catch (Exception error) {
                failure.set(error);
            }
        });
        host.start();
        Path launches = project.resolve("launches.txt");
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && (!Files.isRegularFile(launches) || Files.readAllLines(launches).size() < 2)) {
            Thread.sleep(50);
        }
        List<String> lines = Files.readAllLines(launches);
        assertEquals(2, lines.size(), lines.toString());
        assertFalse(lines.get(0).contains("--no-sandbox"), lines.get(0));
        assertTrue(lines.get(1).contains("--no-sandbox"), lines.get(1));
        Thread.sleep(500);
        assertEquals("open", String.valueOf(PopupStatus.read(project, "sbx").get("state")));
        String log = String.join("\n", PopupLog.warnings(project, "sbx"));
        assertTrue(log.contains("sandbox-unavailable"), log);
        assertTrue(log.contains("No usable sandbox"), log);

        PopupLedger.append(project, "sbx", "close", "", "", "", "", "", "");
        host.join(10_000);
        assertFalse(host.isAlive(), "host did not exit after close");
        assertNull(failure.get());
        assertEquals(2, Files.readAllLines(launches).size(), "relaunch happens once");
        assertFalse(Files.exists(PopupLog.popupDir(project, "sbx").resolve("profile")));
    }

    @Test
    void aBrowserThatExitsWithoutASandboxErrorIsNotRelaunched() throws Exception {
        Assumptions.assumeFalse(PopupHost.windows(), "the fake browser is a POSIX shell script");
        Path script = project.resolve("exit-browser.sh");
        Files.writeString(script, """
                #!/bin/sh
                printf '%s\\n' "$*" >> "$(dirname "$0")/exits.txt"
                exit 0
                """);
        assertTrue(script.toFile().setExecutable(true));
        System.setProperty("pkb_agent_popup_browser", script.toString());
        System.setProperty("pkb_agent_popup_on_top", "false");
        System.setProperty("pkb_agent_popup_headless", "true");
        PopupHostMain.serve(project, "bye", "cli", ProcessHandle.current().pid());
        assertEquals(1, Files.readAllLines(project.resolve("exits.txt")).size());
        assertEquals("closed-by-user", String.valueOf(PopupStatus.read(project, "bye").get("state")));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
