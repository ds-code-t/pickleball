package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupLogTest {
    @TempDir
    Path project;

    @Test
    void offByDefaultThenFiltersByCapabilityEntityAndCap() throws Exception {
        String previous = System.getProperty("pkb_agent_popup_log");
        String entity = System.getProperty("pkb_agent_popup_log_entity");
        String cap = System.getProperty("pkb_agent_popup_log_max_bytes");
        try {
            System.clearProperty("pkb_agent_popup_log");
            PopupLog.log(project, "r1", "lifecycle", "r1", "hidden");
            assertFalse(Files.exists(PopupLog.popupDir(project, "r1").resolve("popup.log")));
            System.setProperty("pkb_agent_popup_log", "lifecycle");
            System.setProperty("pkb_agent_popup_log_entity", "r1");
            PopupLog.log(project, "r1", "browser", "r1", "no");
            PopupLog.log(project, "r1", "lifecycle", "other", "no");
            PopupLog.log(project, "r1", "lifecycle", "r1", "yes");
            String text = Files.readString(PopupLog.popupDir(project, "r1").resolve("popup.log"));
            assertTrue(text.contains("yes"), text);
            assertFalse(text.contains(" no"), text);
            assertTrue(text.startsWith("20"), text);
            System.setProperty("pkb_agent_popup_log_max_bytes", "64");
            PopupLog.log(project, "r1", "lifecycle", "r1", "x".repeat(200));
            assertTrue(Files.size(PopupLog.popupDir(project, "r1").resolve("popup.log")) <= 400);
        } finally {
            restore("pkb_agent_popup_log", previous);
            restore("pkb_agent_popup_log_entity", entity);
            restore("pkb_agent_popup_log_max_bytes", cap);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
