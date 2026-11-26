package tools.dscode.common.agentfast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** P4 reply fields: tier, caller, and additive error codes. The human error text stays. */
class ConsumerFixesP4EnvelopeTest {
    @TempDir
    Path project;

    @Test
    void tierAndCallerFollowTheVerbAndTheRunId() {
        Map<String, Object> snap = AgentEnvelope.apply(ok(), 0, "snap", project, "helper", null);
        assertEquals("human", snap.get("tier"));
        assertEquals("helper", snap.get("caller"));
        assertEquals("snap", snap.get("op"));

        Map<String, Object> eval = AgentEnvelope.apply(ok(), 0, "eval", project, "agent", "other");
        assertEquals("control", eval.get("tier"));
        assertEquals("agent", eval.get("caller"));

        Map<String, Object> blank = AgentEnvelope.apply(ok(), 0, "snap", project, "", null);
        assertEquals("human", blank.get("tier"));
        assertFalse(blank.containsKey("caller"));

        Map<String, Object> agentOnly = AgentEnvelope.apply(ok(), 0, "find", project, null, "agent-9");
        assertEquals("human", agentOnly.get("tier"));
        assertEquals("agent-9", agentOnly.get("caller"));

        Map<String, Object> alias = AgentEnvelope.apply(ok(), 0, "wait-for", project, "helper");
        assertEquals("wait", alias.get("op"));
        assertEquals("human", alias.get("tier"));
    }

    @Test
    void errorCodesStayBesideTheHumanMessage() {
        assertCode("element not found", "E_NOT_FOUND", true);
        assertCode("ambiguous", "E_AMBIGUOUS", false);
        assertCode("stale_ref", "E_STALE_REF", true);
        assertCode("stale ref", "E_STALE_REF", true);
        assertCode("eval is a control-tier verb and this session is human-locked.", "E_TIER", false);
        assertCode("act --kind=attach is not human input and this session is human-locked", "E_TIER", false);
        assertCode("unsupported on attached target", "E_UNSUPPORTED_TARGET", false);
        assertCode("value not verified", "E_NOT_APPLIED_UNVERIFIED", true);
        assertCode("not applied", "E_NOT_APPLIED_UNVERIFIED", true);
        assertCode("budget exceeded", "E_BUDGET", true);

        Map<String, Object> timeout = failed("timeout");
        assertFalse(timeout.containsKey("code"), timeout.toString());
        assertFalse(timeout.containsKey("retryable"), timeout.toString());
        assertEquals("timeout", err(timeout).get("code"));

        Map<String, Object> other = failed("boom");
        assertFalse(other.containsKey("code"), other.toString());
        assertEquals("boom", err(other).get("code"));
    }

    @Test
    void anExistingStableCodeIsKept() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "ambiguous");
        body.put("code", "E_NOT_FOUND");
        Map<String, Object> wrapped = AgentEnvelope.apply(body, 1, "act", project, "helper");
        assertEquals("E_NOT_FOUND", wrapped.get("code"));
        assertEquals("ambiguous", err(wrapped).get("code"));
        Map<String, Object> again = AgentEnvelope.apply(wrapped, 1, "act", project, "helper");
        assertEquals("E_NOT_FOUND", again.get("code"));
        assertEquals("ambiguous", err(again).get("code"));
        assertEquals("human", again.get("tier"));
        assertEquals("helper", again.get("caller"));
    }

    private Map<String, Object> ok() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sentence", "ok");
        return body;
    }

    private void assertCode(String error, String code, boolean retryable) {
        Map<String, Object> wrapped = failed(error);
        assertEquals(code, wrapped.get("code"), error);
        assertEquals(retryable, wrapped.get("retryable"), error);
        assertEquals(error, err(wrapped).get("code"), error);
        assertEquals(error, err(wrapped).get("msg"), error);
        assertEquals(error, wrapped.get("error"));
    }

    private Map<String, Object> failed(String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        return AgentEnvelope.apply(body, 1, "act", project, "helper");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> err(Map<String, Object> wrapped) {
        return (Map<String, Object>) wrapped.get("err");
    }
}
