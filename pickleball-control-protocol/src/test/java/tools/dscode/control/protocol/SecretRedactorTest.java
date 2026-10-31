package tools.dscode.control.protocol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretRedactorTest {
    @AfterEach
    void clear() {
        SecretRedactor.clear();
    }

    @Test
    void displayKeepsStoredReferencesAndRedactsLiterals() {
        assertEquals("${protected:custom_slot}", SecretRedactor.display("pkb_rp_api_key", "${protected:custom_slot}"));
        assertEquals("$(protected:pkb_token)", SecretRedactor.display("pkb_token", "$(protected:pkb_token)"));
        assertEquals("<redacted>", SecretRedactor.display("pkb_password", "hunter2-workspace-secret"));
        assertEquals("CHROME_HEADLESS", SecretRedactor.display("pkb_browser", "CHROME_HEADLESS"));
        assertEquals("", SecretRedactor.display("pkb_password", ""));
        assertEquals("\"\"", OutputFormatter.shown("pkb_password", ""));
    }

    @Test
    void launchEchoDoesNotRewriteTheStoredReference() {
        Map<String, String> stored = new LinkedHashMap<>();
        stored.put("pkb_browser", "CHROME_HEADLESS");
        stored.put("pkb_password", "hunter2-workspace-secret");
        stored.put("pkb_rp_api_key", "${protected:custom_slot}");
        stored.put("pkb_token", "$(protected:pkb_token)");
        String echo = OutputFormatter.launchEcho(stored);
        assertTrue(echo.contains("pkb_rp_api_key=${protected:custom_slot}"), echo);
        assertFalse(echo.contains("${protected:pkb_rp_api_key}"), echo);
        assertTrue(echo.contains("pkb_password=<redacted>"), echo);
        assertFalse(echo.contains("hunter2-workspace-secret"), echo);
        assertTrue(echo.contains("pkb_token=$(protected:pkb_token)"), echo);
        assertTrue(echo.contains("pkb_browser=CHROME_HEADLESS"), echo);
    }

    @Test
    void scrubRemovesANotedLiteralFromFreeText() {
        SecretRedactor.note("pkb_password", "hunter2-workspace-secret");
        assertEquals("saw <redacted> end", SecretRedactor.scrub("saw hunter2-workspace-secret end"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pkb_password", "hunter2-workspace-secret");
        body.put("note", "token hunter2-workspace-secret");
        @SuppressWarnings("unchecked")
        Map<String, Object> redacted = (Map<String, Object>) OutputFormatter.redactValue(body);
        assertEquals("<redacted>", redacted.get("pkb_password"));
        assertEquals("token <redacted>", redacted.get("note"));
    }
}
