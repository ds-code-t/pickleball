package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.SessionIds;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PKB-HCR 2026-11-28 item 13: a short id from another run id gave no hint; wait-any now warns. */
class ShortIdScopeWarningTest {
    @TempDir
    Path project;

    @Test
    void aForeignShortIdIsNamedInAWarning() {
        String human = SessionIds.intern("hcr13-human", 101, "ref:human-go");
        String agent = SessionIds.intern("hcr13-agent", 202, "ref:agent-go");
        assertEquals("hcr13-human", SessionIds.ownerOf(human));
        assertTrue(SessionIds.foreignWarning(human, "hcr13-agent").contains("belongs to run id hcr13-human"));
        assertEquals("", SessionIds.foreignWarning(agent, "hcr13-agent"), "its own short id has no warning");
        assertEquals("", SessionIds.foreignWarning("s9999.h1", "hcr13-agent"), "an id unknown here has no warning");
        assertEquals("", SessionIds.foreignWarning("ref:x", "hcr13-agent"));
    }

    @Test
    void waitAnyCarriesTheWarningOnTimeout() {
        String human = SessionIds.intern("hcr13-h2", 303, "ref:h2-go");
        AgentControl.Result result = AgentControl.execute(project, "hcr13-a2", "wait-any",
                Map.of("watch", human + ":click", "timeout", "100"), List.of(), "agent-1");
        assertEquals(1, result.exit(), String.valueOf(result.body()));
        assertTrue(String.valueOf(result.body().get("warning")).contains("hcr13-h2"), String.valueOf(result.body()));
        AgentControl.Result own = AgentControl.execute(project, "hcr13-h2", "wait-any",
                Map.of("watch", human + ":click", "timeout", "100"), List.of(), "agent-1");
        assertFalse(own.body().containsKey("warning"), String.valueOf(own.body()));
    }
}
