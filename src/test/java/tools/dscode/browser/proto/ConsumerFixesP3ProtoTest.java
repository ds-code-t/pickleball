package tools.dscode.browser.proto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P3 protocol verbs: tab-activate --page and one Input.insertText. */
class ConsumerFixesP3ProtoTest {
    @Test
    void tabActivatePageMatchesAUrlSubstring() {
        CdpSession.Target first = new CdpSession.Target("s1", "tab-a", "page", "http://127.0.0.1/home");
        CdpSession.Target second = new CdpSession.Target("s2", "tab-b", "page", "http://127.0.0.1/orders");
        assertEquals("tab-b", ContextCommands.tabForPage(List.of(first, second), "orders"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ContextCommands.tabForPage(List.of(first, second), "missing-page"));
        assertTrue(missing.getMessage().contains("no tab url contains \"missing-page\""), missing.getMessage());
        assertTrue(missing.getMessage().contains("fix:"), missing.getMessage());
    }

    @Test
    void textAtIsOneInsertTextAndActionsStayAChain() {
        assertTrue(InputCommands.oneInsertText(Map.of("text", "hello", "at", "s1.h2")));
        assertFalse(InputCommands.oneInsertText(Map.of("text", "hello", "at", "s1.h2", "actions", "[{\"type\":\"key\"}]")));
        assertFalse(InputCommands.oneInsertText(Map.of("actions", "[]")));
        assertEquals("hello", InputCommands.insertTextParams("hello").get("text"));
        assertEquals(1, InputCommands.insertTextParams("hello").size());
    }
}
