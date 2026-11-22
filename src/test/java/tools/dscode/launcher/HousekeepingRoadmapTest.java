package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.common.agentfast.PushChannel;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Roadmap 5 remaining field-report items. No Chrome. */
class HousekeepingRoadmapTest {
    @TempDir
    Path temp;

    @Test
    void observeRequestAndResponseKeepMatchingTraffic() throws Exception {
        Path events = temp.resolve("events.json");
        Files.writeString(events, """
                {"events":[
                  {"method":"Network.requestWillBeSent","kind":"network","url":"https://example.test/api/orders","text":"","at":10,"httpMethod":"GET"},
                  {"method":"Network.requestWillBeSent","kind":"network","url":"https://other.test/static","text":"","at":20,"httpMethod":"GET"},
                  {"method":"Network.responseReceived","kind":"network","url":"https://example.test/api/orders","text":"","at":30,"status":200},
                  {"method":"Runtime.consoleAPICalled","kind":"console","url":"","text":"hello","at":40}
                ]}
                """);
        Capture capture = agent(
                "observe", temp.toString(),
                "--events=" + events,
                "--request=/api",
                "--response=*"
        );
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("/api/orders"), capture.out);
        assertTrue(capture.out.contains("responseReceived"), capture.out);
        assertFalse(capture.out.contains("other.test"), capture.out);
        assertFalse(capture.out.contains("consoleAPICalled"), capture.out);
        assertTrue(capture.out.contains("\"count\":2") || capture.out.contains("\"count\": 2"), capture.out);
    }

    @Test
    void feedbackSelftestDoesNotNeedChrome() {
        Capture capture = agent("selftest", temp.toString(), "ui", "--component=feedback");
        assertEquals(0, capture.code, capture.out + capture.err);
        assertTrue(capture.out.contains("sending"), capture.out);
        assertTrue(capture.out.contains("delivered"), capture.out);
        assertTrue(capture.out.contains("read"), capture.out);
        assertFalse(capture.out.contains("no Chrome"), capture.out);
    }

    @Test
    void seleniumEngineIsRejected() {
        Capture engine = agent("start", temp.toString(), "--url=https://example.test", "--browser=chrome", "--engine=selenium");
        assertEquals(2, engine.code, engine.out + engine.err);
        assertTrue(engine.err.contains("Chrome DevTools"), engine.err);
        assertTrue(engine.err.contains("--engine=selenium"), engine.err);
        Capture browser = agent("start", temp.toString(), "--url=https://example.test", "--browser=selenium");
        assertEquals(2, browser.code, browser.out + browser.err);
        assertTrue(browser.err.contains("not supported"), browser.err);
        assertTrue(page("start").contains("Chrome DevTools"));
        assertTrue(page("live-start").contains("--engine=selenium is rejected"));
        assertTrue(page("launch").contains("Selenium Manager"));
    }

    @Test
    void eventsFollowReturnsTheFirstMatchingLine() {
        PushChannel.accept(temp, Map.of("type", "chat", "source", "human", "text", "alpha ready"));
        PushChannel.accept(temp, Map.of("type", "chat", "source", "human", "text", "beta later"));
        Capture capture = session(
                "events", temp.toString(), "--follow", "--timeout=0", "--until=text=alpha", "--type=chat");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("alpha ready"), capture.out);
        assertFalse(capture.out.contains("beta later"), capture.out);
        assertTrue(!AgentHop.shouldHop("events", new String[]{"events", "--follow"}));
    }

    @Test
    void logTailFollowReturnsInProcess() throws Exception {
        Path history = temp.resolve(".pickleball").resolve("history.log");
        Files.createDirectories(history.getParent());
        Files.writeString(history, "tail-line\n");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchLogIfAny(
                new String[]{"log-tail", "--project=" + temp, "--file=history.log", "--follow", "--timeout=0"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("tail-line"), stdout.toString(StandardCharsets.UTF_8));
        assertTrue(tools.dscode.common.composite.DomLogCli.help().contains("does not leave a process"));
    }

    @Test
    void liveStopInsideTheControllerDefersExitUntilAfterTheReply() {
        String previous = System.getProperty(AgentHop.INSIDE);
        System.setProperty(AgentHop.INSIDE, "true");
        AgentController.clearStopAfterReplyForTests();
        try {
            Capture capture = agent("live-stop", "--all", temp.toString());
            assertEquals(0, capture.code, capture.err + capture.out);
            assertTrue(capture.out.contains("controller=stopped"), capture.out);
            assertTrue(AgentController.stopAfterReply());
            assertTrue(!AgentHop.shouldHop("start", new String[]{"live-stop", "--all"}));
        } finally {
            AgentController.clearStopAfterReplyForTests();
            if (previous == null) System.clearProperty(AgentHop.INSIDE);
            else System.setProperty(AgentHop.INSIDE, previous);
        }
    }

    @Test
    void leanRepliesUnescapeQuotesAndDropLongHandles() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "s1.h2");
        row.put("name", "Say \\\"hi\\\"");
        row.put("ref", "r".repeat(80));
        row.put("handle", "h".repeat(60));
        Map<String, Object> numeric = new LinkedHashMap<>();
        numeric.put("id", "s1.h3");
        numeric.put("handle", 42L);
        numeric.put("ref", "ref:1:1");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("elapsedMs", 1);
        body.put("lines", List.of(row, numeric));
        Map<String, Object> wrapped = AgentEnvelope.apply(body, 0, "snap", temp, "run");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) wrapped.get("items");
        assertEquals("Say \"hi\"", items.get(0).get("name"));
        assertFalse(items.get(0).containsKey("ref"));
        assertEquals("s1.h2", items.get(0).get("handle"));
        assertEquals(42L, items.get(1).get("handle"));
        assertEquals("ref:1:1", items.get(1).get("ref"));
    }

    @Test
    void agentRepliesDropLogNoise() {
        String cleaned = QuietWarnings.dropNoise("PKB-AGENT ready\nWARN: boom\n{\"ok\":true,\"text\":\"WARN: keep\"}\nWARNING: later\n");
        assertFalse(cleaned.contains("PKB-AGENT"));
        assertFalse(cleaned.contains("WARN: boom"));
        assertFalse(cleaned.contains("WARNING:"));
        assertTrue(cleaned.contains("\"text\":\"WARN: keep\""), cleaned);
    }

    private static String page(String command) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchCliHelp.answer(
                new String[]{"help", command},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, command);
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private static Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static Capture session(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
