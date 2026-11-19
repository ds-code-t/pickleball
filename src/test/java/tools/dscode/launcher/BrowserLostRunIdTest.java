package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.AgentEvents;
import tools.dscode.control.protocol.speed.ProcessTree;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A host-level {@code browser_lost} reaches polls that pass {@code --run-id}.
 * Kills "events filtered by run".
 */
class BrowserLostRunIdTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp.resolve("ui-poll"));
        ConsumerReadyCommands.stopQuiet(temp.resolve("ui-catalog"));
        ConsumerReadyCommands.stopQuiet(temp.resolve("chat-poll"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void browserLostReachesEachPollOnce() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        lostOnce("ui-poll", true);
        lostOnce("ui-catalog", false);
        lostOnce("chat-poll", false);
    }

    /**
     * @param alsoBare a poll with no {@code --run-id} uses the host session cursor,
     *                 which is a different file from the {@code --run-id} cursor.
     */
    private void lostOnce(String command, boolean alsoBare) throws Exception {
        Path project = temp.resolve(command);
        Files.createDirectories(project);
        String runId = "lost-" + command;
        Capture started = run(
                "start", "--url=about:blank", "--browser=chrome-headless",
                "--run-id=" + runId, project.toString());
        assertEquals(0, started.code, started.err + started.out);
        JsonNode node = JSON.readTree(project.resolve(".pickleball/live-browser/current.json").toFile());
        String hostSession = node.path("sessionId").asText("");
        assertFalse(hostSession.isBlank(), node.toString());
        assertFalse(runId.equals(hostSession), hostSession);
        AgentEvents.append(project, "session_ended", "other-session");
        ProcessTree.kill(node.path("chromePid").asLong(0));

        Capture poll = run(command, "--run-id=" + runId, project.toString());
        assertEquals(0, poll.code, poll.out + poll.err);
        assertOneHostLost(poll.out, hostSession);
        assertFalse(poll.out.contains("other-session"), poll.out);

        Capture again = run(command, "--run-id=" + runId, project.toString());
        assertEquals(0, again.code, again.out + again.err);
        assertEquals(0, countAgentEvent(again.out, "browser_lost"), again.out);

        if (alsoBare) {
            Capture bare = run(command, project.toString());
            assertEquals(0, bare.code, bare.out + bare.err);
            assertOneHostLost(bare.out, hostSession);
            Capture bareAgain = run(command, project.toString());
            assertEquals(0, bareAgain.code, bareAgain.out + bareAgain.err);
            assertEquals(0, countAgentEvent(bareAgain.out, "browser_lost"), bareAgain.out);
        }

        String events = Files.readString(project.resolve(".pickleball/agent-events.jsonl"));
        assertEquals(1, countEvent(events, "browser_lost"), events);
        assertTrue(events.contains("\"sessionId\":\"" + hostSession + "\""), events);
        assertFalse(events.contains("\"sessionId\":\"" + runId + "\""), events);
        run("live-stop", project.toString());
    }

    private static void assertOneHostLost(String body, String hostSession) throws Exception {
        JsonNode events = JSON.readTree(body).path("agentEvents");
        assertTrue(events.isArray(), body);
        assertEquals(1, events.size(), body);
        assertEquals("browser_lost", events.get(0).path("event").asText(""), body);
        assertEquals(hostSession, events.get(0).path("sessionId").asText(""), body);
    }

    private static int countAgentEvent(String body, String name) throws Exception {
        int count = 0;
        JsonNode events = JSON.readTree(body).path("agentEvents");
        if (!events.isArray()) return 0;
        for (JsonNode event : events) {
            if (name.equals(event.path("event").asText(""))) count++;
        }
        return count;
    }

    private static int countEvent(String events, String name) {
        int count = 0;
        for (String line : events.split("\n")) {
            if (line.contains("\"event\":\"" + name + "\"")) count++;
        }
        return count;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
