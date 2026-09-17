package tools.dscode.workbench.player;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorTabStateTest {
    @Test
    void tracksDirtyDocumentAndMatchesOpenTarget() {
        Path file = Path.of("src/test/resources/features/login.feature");
        EditorTabState tab = new EditorTabState("Login", file, "Feature: Login\n", false);
        tab.setOrigin(file, "Valid password", 4, 8, 0, "");
        assertFalse(tab.dirty());
        tab.setDocumentText("Feature: Login\nScenario: Valid password\n");
        assertTrue(tab.dirty());
        assertEquals("Login *", tab.tabTitle());
        assertTrue(tab.sameTarget(file, "Valid password", 0));
        assertFalse(tab.sameTarget(file, "Locked", 0));
        tab.setPeek(true);
        assertTrue(tab.peek());
        assertTrue(tab.readOnly());
    }
}