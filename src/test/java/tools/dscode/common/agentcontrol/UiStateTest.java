package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStateTest {
    @Test
    void scopeMatching() {
        String a = "http://127.0.0.1:4000/account?x=1#top";
        assertTrue(UiState.inScope("page", a, "http://127.0.0.1:4000/account?x=1", 0, 0));
        assertFalse(UiState.inScope("page", a, "http://127.0.0.1:4000/account?x=2", 0, 0));
        assertFalse(UiState.inScope("page", a, "http://127.0.0.1:4000/other", 0, 0));
        assertTrue(UiState.inScope("origin", a, "http://127.0.0.1:4000/other", 0, 0));
        assertFalse(UiState.inScope("origin", a, "http://127.0.0.1:4001/account", 0, 0));
        assertFalse(UiState.inScope("origin", a, "https://127.0.0.1:4000/account", 0, 0));
        assertTrue(UiState.inScope("run", a, "https://example.test/", 0, 0));
    }

    @Test
    void ttlExpires() {
        long expires = UiState.expires("30", 1_000L);
        assertEquals(31_000L, expires);
        assertTrue(UiState.inScope("run", "http://a/", "http://a/", expires, 30_999L));
        assertFalse(UiState.inScope("run", "http://a/", "http://a/", expires, 31_001L));
        assertEquals(0L, UiState.expires("", 5L));
        assertThrows(IllegalArgumentException.class, () -> UiState.expires("0", 5L));
        assertThrows(IllegalArgumentException.class, () -> UiState.expires("soon", 5L));
    }

    @Test
    void scopeValidation() {
        assertEquals("page", UiState.scope(null));
        assertEquals("origin", UiState.scope("Origin"));
        assertThrows(IllegalArgumentException.class, () -> UiState.scope("tab"));
    }

    @Test
    void stateCarriesOrderAndNoValues() throws Exception {
        JsonNode snap = AgentStore.JSON.readTree("{\"url\":\"http://a/p\",\"items\":[{\"id\":\"o1\"},{\"id\":\"o2\"}],\"guides\":[],\"panel\":null}");
        assertFalse(UiState.empty(snap));
        assertTrue(UiState.empty(AgentStore.JSON.readTree("{\"items\":[],\"guides\":[],\"panel\":null}")));
        JsonNode state = UiState.state(snap, "origin", 9L, false);
        assertEquals("o1", state.path("snap").path("items").get(0).path("id").asText());
        assertEquals("o2", state.path("snap").path("items").get(1).path("id").asText());
        String script = UiState.preloadScript(state);
        assertTrue(script.contains("restoreWhenReady(STATE)"), script.substring(0, 200));
        assertFalse(script.contains("</script"), "a closing tag in state must be escaped");
    }
}
