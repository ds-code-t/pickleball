package tools.dscode.browser.proto;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.browser.BrowserLog;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteModifyBodyTest {
    private static final String BODY = "{\"a\":1}";

    @AfterEach
    void clearLog() {
        System.clearProperty(BrowserLog.KEY);
        BrowserLog.resetForTests();
    }

    @Test
    void modifySendsTheRequestBodyOnEveryBackend() throws Exception {
        System.setProperty(BrowserLog.KEY, "playwright");
        BrowserLog.resetForTests();
        RouteCommands.Rule rule = rule(BODY, Map.of("Accept", "application/json", "X-Pkb", "yes"));

        Route.ResumeOptions playwright = RouteCommands.playwrightResume(Map.of("Accept", "text/plain"), rule);
        assertEquals(BODY, field(playwright, "postData"));
        @SuppressWarnings("unchecked")
        Map<String, String> playwrightHeaders = (Map<String, String>) field(playwright, "headers");
        assertEquals("application/json", playwrightHeaders.get("Accept"));
        assertEquals("yes", playwrightHeaders.get("X-Pkb"));

        Map<String, Object> cdp = RouteCommands.cdpContinue("req-1", Map.of("accept", "text/plain"), rule);
        assertEquals(BODY, cdp.get("postData"));
        assertEquals("application/json", headerValue(cdp.get("headers"), "Accept"));
        assertEquals("yes", headerValue(cdp.get("headers"), "X-Pkb"));
        assertFalse(String.valueOf(cdp.get("headers")).contains("text/plain"));

        ArrayNode requestHeaders = JsonNodeFactory.instance.arrayNode();
        ObjectNode accept = requestHeaders.addObject();
        accept.put("name", "Accept");
        accept.putObject("value").put("type", "string").put("value", "text/plain");
        Map<String, Object> bidi = RouteCommands.bidiContinue(requestHeaders, Map.of("X-Extra", "1"), rule);
        @SuppressWarnings("unchecked")
        Map<String, String> bidiBody = (Map<String, String>) bidi.get("body");
        assertEquals("string", bidiBody.get("type"));
        assertEquals(BODY, bidiBody.get("value"));
        assertEquals("application/json", bidiHeader(bidi.get("headers"), "Accept"));
        assertEquals("yes", bidiHeader(bidi.get("headers"), "X-Pkb"));
        assertEquals("1", bidiHeader(bidi.get("headers"), "X-Extra"));

        String joined = String.join("\n", BrowserLog.linesForTests());
        assertTrue(joined.contains("modify body-bytes=7"), joined);
        assertFalse(joined.contains(BODY), joined);
        assertEquals(3, BrowserLog.linesForTests().size(), joined);
    }

    @Test
    void nullBodyStaysHeaderOnlyAndIsNotLogged() throws Exception {
        System.setProperty(BrowserLog.KEY, "playwright");
        BrowserLog.resetForTests();
        RouteCommands.Rule rule = rule(null, Map.of("X-Pkb", "yes"));

        Route.ResumeOptions playwright = RouteCommands.playwrightResume(Map.of("Accept", "text/plain"), rule);
        assertNull(field(playwright, "postData"));
        @SuppressWarnings("unchecked")
        Map<String, String> playwrightHeaders = (Map<String, String>) field(playwright, "headers");
        assertEquals("text/plain", playwrightHeaders.get("Accept"));
        assertEquals("yes", playwrightHeaders.get("X-Pkb"));

        Map<String, Object> cdp = RouteCommands.cdpContinue("req-1", Map.of("Accept", "text/plain"), rule);
        assertFalse(cdp.containsKey("postData"));
        assertEquals("yes", headerValue(cdp.get("headers"), "X-Pkb"));

        Map<String, Object> bidi = RouteCommands.bidiContinue(JsonNodeFactory.instance.arrayNode(), Map.of(), rule);
        assertFalse(bidi.containsKey("body"));
        assertEquals("yes", bidiHeader(bidi.get("headers"), "X-Pkb"));
        assertTrue(BrowserLog.linesForTests().isEmpty(), BrowserLog.linesForTests().toString());
    }

    @Test
    void emptyBodyIsSentAndLoggedAsZeroBytes() throws Exception {
        System.setProperty(BrowserLog.KEY, "playwright");
        BrowserLog.resetForTests();
        RouteCommands.Rule rule = rule("", Map.of());
        Route.ResumeOptions playwright = RouteCommands.playwrightResume(Map.of(), rule);
        assertEquals("", field(playwright, "postData"));
        Map<String, Object> cdp = RouteCommands.cdpContinue("req-2", Map.of(), rule);
        assertEquals("", cdp.get("postData"));
        Map<String, Object> bidi = RouteCommands.bidiContinue(JsonNodeFactory.instance.arrayNode(), Map.of(), rule);
        @SuppressWarnings("unchecked")
        Map<String, String> bidiBody = (Map<String, String>) bidi.get("body");
        assertEquals("", bidiBody.get("value"));
        String joined = String.join("\n", BrowserLog.linesForTests());
        assertTrue(joined.contains("modify body-bytes=0"), joined);
    }

    private static RouteCommands.Rule rule(String body, Map<String, String> headers) {
        return new RouteCommands.Rule("r1", "https://example.test/", "modify", 200, body, headers, 0, -1);
    }

    private static Object field(Object target, String name) throws Exception {
        Field found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return found.get(target);
    }

    @SuppressWarnings("unchecked")
    private static String headerValue(Object headers, String name) {
        for (Map<String, String> header : (List<Map<String, String>>) headers) {
            if (name.equalsIgnoreCase(header.get("name"))) return header.get("value");
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String bidiHeader(Object headers, String name) {
        for (Map<String, ?> header : (List<Map<String, ?>>) headers) {
            if (!name.equalsIgnoreCase(String.valueOf(header.get("name")))) continue;
            Object value = header.get("value");
            if (value instanceof Map<?, ?> typed) return String.valueOf(typed.get("value"));
            return String.valueOf(value);
        }
        return null;
    }
}
