package tools.dscode.browser.proto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** PKB-HCR 2026-11-28 item 14: the downloads dir followed the run id that started the event host. */
class CallerRunTest {
    @Test
    void theCallersRunIdWinsOverTheHostsRun() {
        assertEquals("agent", ContextCommands.callerRun(Map.of("_run", "agent"), "helper"));
        assertEquals("agent", ContextCommands.callerRun(Map.of("run-id", "agent"), "helper"));
        assertEquals("helper", ContextCommands.callerRun(Map.of(), "helper"), "no caller run keeps the host's");
        assertEquals("helper", ContextCommands.callerRun(Map.of("_run", "../x"), "helper"), "a path is not a run id");
    }
}
