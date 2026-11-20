package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgetLeavesPanelTest {
    @Test
    void forgetClearsPlacementsAndDoesNotCloseThePanel() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/tools/dscode/browser/surface/pkb-runtime.js"));
        int start = js.indexOf("function forget()");
        int end = js.indexOf("window.__pkbUi", start);
        assertTrue(start > 0 && end > start);
        String body = js.substring(start, end);
        assertTrue(body.contains("clear()"));
        assertFalse(body.contains("panelClose"));
        assertFalse(body.contains("withdraw("));
        String catalog = UiCatalog.component("clear").summary();
        assertTrue(catalog.contains("panel stays open"), catalog);
    }
}
