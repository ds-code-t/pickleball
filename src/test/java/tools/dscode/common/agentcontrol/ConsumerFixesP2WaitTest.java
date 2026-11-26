package tools.dscode.common.agentcontrol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P2: wait --from and a missing guide file. No browser. */
class ConsumerFixesP2WaitTest {
    @TempDir
    Path temp;

    @Test
    void waitSourceMatchesTheDeclaredFrom() {
        assertTrue(AgentControl.waitSource("agent", "agent"));
        assertFalse(AgentControl.waitSource("agent", "human"));
        assertFalse(AgentControl.waitSource("human", "agent"));
        assertTrue(AgentControl.waitSource("human", "human"));
        assertTrue(AgentControl.waitSource(null, "human"));
        assertTrue(AgentControl.waitSource("any", "agent"));
        assertTrue(AgentControl.waitSource("all", "system"));
    }

    @Test
    void guideMissingFileNamesTheBaseDirectory() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> AgentControl.guideSteps(temp, "@missing-steps.json"));
        assertTrue(missing.getMessage().contains("steps file not found"), missing.getMessage());
        assertTrue(missing.getMessage().contains(temp.toAbsolutePath().normalize().toString()), missing.getMessage());

        AgentControl.Result executed = AgentControl.execute(
                temp, "p2-guide", "guide", Map.of("steps", "@missing-steps.json"), List.of(), "p2");
        assertEquals(2, executed.exit());
        assertTrue(String.valueOf(executed.body().get("error")).contains("steps file not found"), executed.body().toString());
    }

    @Test
    void waitFromAgentSeesAnAgentMessageAndHumanIgnoresIt() throws Exception {
        String agentRun = "p2-from-agent";
        AtomicReference<AgentControl.Result> seen = new AtomicReference<>();
        Thread waiter = new Thread(() -> seen.set(AgentControl.execute(
                temp, agentRun, "wait",
                Map.of("condition", "new-message", "from", "agent", "timeout", "4000"),
                List.of(), "p2")));
        waiter.start();
        long deadline = System.currentTimeMillis() + 2500;
        int n = 0;
        while (System.currentTimeMillis() < deadline && seen.get() == null) {
            ChatLog.send(temp, agentRun, "run", "agent", "agent", "agent-note-" + (++n));
            Thread.sleep(100);
        }
        waiter.join(5000);
        AgentControl.Result agent = seen.get();
        assertTrue(agent != null && agent.exit() == 0, agent == null ? "wait did not return" : agent.body().toString());
        assertTrue(String.valueOf(agent.body().get("message")).contains("agent-note-"), agent.body().toString());

        String humanRun = "p2-from-human";
        Thread humanWaiter = new Thread(() -> seen.set(AgentControl.execute(
                temp, humanRun, "wait",
                Map.of("condition", "new-message", "from", "human", "timeout", "600"),
                List.of(), "p2")));
        seen.set(null);
        humanWaiter.start();
        Thread.sleep(100);
        ChatLog.send(temp, humanRun, "run", "agent", "agent", "only-an-agent");
        humanWaiter.join(5000);
        AgentControl.Result human = seen.get();
        assertTrue(human != null && human.exit() == 1, human == null ? "human wait did not return" : human.body().toString());
        assertTrue(String.valueOf(human.body().get("error")).contains("timeout"), human.body().toString());
    }
}
