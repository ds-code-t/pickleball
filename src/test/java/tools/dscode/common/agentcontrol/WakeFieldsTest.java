package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import tools.dscode.common.agentfast.PushWake;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28 item 27: chat-turn and watch replies had no wake / wakeMs. */
class WakeFieldsTest {
    @Test
    void chatTurnAndWatchRepliesCarryWakeAndWakeMs() {
        assertTrue(AgentControl.WAKE_OPS.contains("chat-turn"));
        assertTrue(AgentControl.WAKE_OPS.contains("watch"));
        PushWake.signal();
        Map<String, Object> body = new LinkedHashMap<>();
        AgentControl.wakeFields(body);
        assertEquals(PushWake.mode(), body.get("wake"));
        assertTrue(body.get("wakeMs") instanceof Long, body.toString());
        Map<String, Object> kept = new LinkedHashMap<>(Map.of("wake", "poll"));
        AgentControl.wakeFields(kept);
        assertEquals("poll", kept.get("wake"), "a verb's own wake value is kept");
    }
}
