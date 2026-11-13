package tools.dscode.workbench.ui;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchNativeOverlayHighlightTest {
    @Test
    void boundsUseTheBoxAndTheCaptionWindowStaysWhenPopupIsFalse() {
        WorkbenchNativeOverlay.resetForTests();
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("id", "h1");
        command.put("x", 10);
        command.put("y", 20);
        command.put("width", 30);
        command.put("height", 40);
        command.put("guidance", "look");
        command.put("sentence", "agent-injected overlay; a person did not author this UI");
        command.put("popup", false);
        WorkbenchNativeOverlay.place(command);
        assertTrue(WorkbenchNativeOverlay.caption(command).contains("look"));
        WorkbenchNativeOverlay.remove("h1");
        assertEquals(List.of("h1"), WorkbenchNativeOverlay.removedForTests());

        WorkbenchNativeOverlay.resetForTests();
        command.put("popup", true);
        WorkbenchNativeOverlay.place(command);
        WorkbenchNativeOverlay.remove("h1");
        assertEquals(List.of("h1"), WorkbenchNativeOverlay.removedForTests());
    }
}
