package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupHostCloseTest {
    @TempDir
    Path project;

    private String browser;
    private String onTop;
    private String headless;

    @AfterEach
    void restore() throws Exception {
        PopupHost.closeAndWait(project, "held", Duration.ofSeconds(2));
        restore("pkb_agent_popup_browser", browser);
        restore("pkb_agent_popup_on_top", onTop);
        restore("pkb_agent_popup_headless", headless);
    }

    @Test
    void closeAndWaitWithNoHostReturns() throws Exception {
        long start = System.nanoTime();
        assertTrue(PopupHost.closeAndWait(project, "empty", Duration.ofSeconds(10)));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(2).toNanos());
        assertTrue(Files.readString(PopupLedger.file(project, "empty")).contains("\"op\":\"close\""));
    }

    @Test
    void closeAndWaitStopsTheHostAndDeletesTheProfile() throws Exception {
        browser = System.getProperty("pkb_agent_popup_browser");
        onTop = System.getProperty("pkb_agent_popup_on_top");
        headless = System.getProperty("pkb_agent_popup_headless");
        Path script = project.resolve("fake-browser.sh");
        Files.writeString(script, """
                #!/bin/sh
                trap 'exit 0' TERM INT
                while true; do sleep 0.2; done
                """);
        script.toFile().setExecutable(true);
        System.setProperty("pkb_agent_popup_browser", script.toString());
        System.setProperty("pkb_agent_popup_on_top", "false");
        System.setProperty("pkb_agent_popup_headless", "true");

        Map<String, Object> opened = PopupHost.open(project, "held");
        assertEquals("open", String.valueOf(opened.get("state")), String.valueOf(opened));
        long hostPid = opened.get("pid") instanceof Number number ? number.longValue() : -1;
        assertTrue(hostPid > 0 && ProcessHandle.of(hostPid).isPresent());
        assertTrue(Files.isDirectory(PopupLog.popupDir(project, "held").resolve("profile")));

        assertTrue(PopupHost.closeAndWait(project, "held", Duration.ofSeconds(10)));
        assertFalse(ProcessHandle.of(hostPid).isPresent(), "host pid " + hostPid);
        assertFalse(Files.exists(PopupLog.popupDir(project, "held").resolve("profile")));
    }

    @Test
    void cliPopupCloseUsesTheSameWait() throws Exception {
        browser = System.getProperty("pkb_agent_popup_browser");
        onTop = System.getProperty("pkb_agent_popup_on_top");
        headless = System.getProperty("pkb_agent_popup_headless");
        Path script = project.resolve("fake-browser.sh");
        Files.writeString(script, """
                #!/bin/sh
                trap 'exit 0' TERM INT
                while true; do sleep 0.2; done
                """);
        script.toFile().setExecutable(true);
        System.setProperty("pkb_agent_popup_browser", script.toString());
        System.setProperty("pkb_agent_popup_on_top", "false");
        System.setProperty("pkb_agent_popup_headless", "true");
        Map<String, Object> opened = PopupHost.open(project, "held");
        assertEquals("open", String.valueOf(opened.get("state")), String.valueOf(opened));
        int code = PopupCli.execute(new String[] {
                "popup-close", "--project=" + project.toAbsolutePath(), "--run-id=held"
        });
        assertEquals(0, code);
        assertFalse(Files.exists(PopupLog.popupDir(project, "held").resolve("profile")));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
