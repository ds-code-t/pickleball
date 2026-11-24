package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R8: pickleball's own storage writes never become browser-events rows; the page's do. */
class OwnStorageFilterTest {
    @TempDir
    Path project;

    @Test
    void pkbStorageKeysAreNotPageStorage() {
        BrowserEventHub hub = new BrowserEventHub(project, "s", "r");
        for (String key : new String[] {"pkb-dock", "pkb-ui", "pkb-pop-chat"}) {
            ObjectNode params = JsonNodeFactory.instance.objectNode().put("key", key).put("newValue", "{}");
            params.putObject("storageId").put("isLocalStorage", true).put("securityOrigin", "http://127.0.0.1");
            assertTrue(MonitorCommands.cdp(null, null, "DOMStorage.domStorageItemUpdated", params, "", hub), key);
        }
        assertEquals(0L, hub.cursor(), "no storage row for pickleball's own keys");
        assertTrue(MonitorCommands.ownStorageKey("pkb-dock"));
        assertFalse(MonitorCommands.ownStorageKey("cart"));
        assertFalse(MonitorCommands.ownStorageKey("pkbcart"));
    }
}
