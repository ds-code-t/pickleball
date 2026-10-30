package tools.dscode.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.Workspace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent-usability repros. A confusing command, flag, or error is a failure
 * even when the underlying call would have worked.
 */
class WorkbenchAgentUsabilityTest {
    @TempDir
    Path temp;

    @BeforeEach
    void captureSeed() {
        WorkspaceLaunch.testCapture = workspace -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : Workspace.CONTEXT_KEYS) values.put(key, key);
            values.put("pkb_browser", "CHROME_HEADLESS");
            values.put("pkb_parallel", "auto");
            Workspace.writeRunVars(workspace, values);
        };
    }

    @AfterEach
    void clearSeed() {
        WorkspaceLaunch.testCapture = null;
        AgentCoordination.clearCurrent();
    }

    @Test
    void unknownCommandNamesTheCorrectionAndUsage() {
        String message = WorkbenchCliHelp.unknown("disover");
        assertTrue(message.contains("Unknown Workbench command: disover"), message);
        assertTrue(message.contains("Did you mean: discover"), message);
        assertTrue(message.contains("Usage: help"), message);
        assertTrue(message.contains("Usage: help <command>"), message);
        assertEquals(2, PickleballWorkbenchLauncher.rejectUnknown(
                WorkbenchCommandLine.parse(new String[]{"disover", temp.toString()}),
                new PrintStream(OutputStream.nullOutputStream())
        ));
        Capture help = agent("help", "not-a-command");
        assertEquals(2, help.code);
        assertTrue(help.err.contains("Usage: help"), help.err);
    }

    @Test
    void helpListsEveryCommand() {
        String catalog = WorkbenchCliHelp.catalog();
        int all = catalog.indexOf("\nall:");
        assertTrue(all > 0, catalog);
        String listed = catalog.substring(all);
        for (String command : WorkbenchCommandLine.AGENT_CORE_COMMANDS) {
            assertTrue(listed.contains(" " + command), command);
        }
        for (String command : WorkbenchCommandLine.SESSION_CLIENT_COMMANDS) {
            assertTrue(listed.contains(" " + command), command);
        }
        for (String command : WorkbenchCommandLine.FORWARDED_COMMANDS) {
            assertTrue(listed.contains(" " + command), command);
        }
        assertTrue(catalog.contains("PKB_WORKBENCH_TIMING=1"), catalog);
        assertTrue(catalog.contains("--json"), catalog);
        assertTrue(catalog.contains("decide: discover, then plan, then run"), catalog);
    }

    @Test
    void deprecatedFlagAliasesNormalizeToTheCanonicalFlag() throws Exception {
        assertEquals("alias-1", parse("discover", "--runId=alias-1").coordination().runId());
        assertEquals("ada", parse("note", "--agentId=ada", "--text=hi").coordination().agentId());
        assertEquals("any", parse("inbox", "--to=all", "--text=hi").coordination().inboxTo());
        WorkbenchCommandLine.Parsed session = parse("session", "info", "--run-id=alias-1");
        assertEquals("session-info", session.command());
        assertEquals("alias-1", session.coordination().id());
        String unknown = WorkbenchCliHelp.unknown("disover");
        assertTrue(unknown.contains("fix: Usage: help <command>"), unknown);
        String golden = Files.readString(Path.of("src/test/resources/workbench-help-golden.txt"));
        String catalog = WorkbenchCliHelp.catalog();
        for (String line : golden.lines().map(String::strip).filter(item -> !item.isEmpty()).toList()) {
            String haystack = line.startsWith("fix:") ? unknown : line.startsWith("Usage: query") ? WorkbenchCliHelp.usage("query") : catalog;
            assertTrue(haystack.contains(line), line);
        }
    }

    private static WorkbenchCommandLine.Parsed parse(String... args) {
        return WorkbenchCommandLine.parse(args);
    }

    @Test
    void diagnosticRunAcceptsRunIdAsThePack() throws Exception {
        try (LiveSession session = LiveSession.open(temp, "pack-1")) {
            Capture capture = sessionCommand(
                    "diagnostic-run", temp.toString(), "--run-id=pack-1", "--ack-only"
            );
            assertEquals(0, capture.code, capture.err + capture.out);
            assertTrue(session.body().contains("\"run\":\"pack-1\""), session.body());
            assertFalse(capture.err.contains("Usage:"), capture.err);
        }
    }

    @Test
    void diagnosticRunExplainsRunVersusRunId() {
        Capture capture = sessionCommand("diagnostic-run", temp.toString());
        assertEquals(2, capture.code);
        assertTrue(capture.err.contains("Usage: diagnostic-run --run=<id>"), capture.err);
        assertTrue(capture.err.contains("--run-id selects the live session"), capture.err);
        String page = WorkbenchCliHelp.forTopic("--run");
        assertTrue(page.contains("Diagnostic pack"), page);
        assertTrue(page.contains("--run-id"), page);
    }

    @Test
    void workspaceShowJsonIsOneObject() {
        Capture seeded = agent("workspace-seed", temp.toString(), "--run-id=json-1");
        assertEquals(0, seeded.code, seeded.err);
        Capture shown = agent("workspace-show", temp.toString(), "--run-id=json-1", "--json");
        assertEquals(0, shown.code, shown.err);
        assertFalse(shown.out.contains("\nworkspace="), shown.out);
        assertTrue(shown.out.startsWith("{"), shown.out);
        assertTrue(shown.out.contains("\"present\":true"), shown.out);
        assertTrue(shown.out.contains("\"fingerprint\":"), shown.out);
        assertEquals(1, shown.out.strip().lines().count());
        Capture missing = agent("workspace-show", temp.toString());
        assertEquals(2, missing.code);
        assertTrue(missing.err.contains("Usage: workspace-show --run-id=<id> [--json]"), missing.err);
    }

    @Test
    void resolveRunVarsJsonOmitsThePreamble() {
        Capture plain = agent("resolve-runvars", temp.toString());
        assertTrue(plain.out.contains("Dry-run resolve"), plain.out);
        Capture json = agent("resolve-runvars", temp.toString(), "--json");
        assertEquals(0, json.code, json.err);
        assertFalse(json.out.contains("Dry-run resolve"), json.out);
        assertTrue(json.out.contains("\"workbenchReportingModeDefault\":\"diagnostic\""), json.out);
        assertTrue(json.out.contains("Trust record.json after discover"), json.out);
        assertEquals(1, json.out.strip().lines().count());
    }

    @Test
    void missingRunVarStatesUsage() {
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"discover", "--runvar"})
        );
        assertTrue(failure.getMessage().contains("Usage: --runvar=key=value"), failure.getMessage());
        assertTrue(failure.getMessage().contains("pkb_browser=CHROME_HEADLESS"), failure.getMessage());
    }

    @Test
    void launchTimingStaysOffStderrUnlessAsked() throws Exception {
        String previous = System.getProperty("pkb_workbench_timing");
        PrintStream original = System.err;
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
        System.setProperty("pkb_workbench_timing", "false");
        try {
            WorkbenchLaunchTiming.record(temp, "launcher", "agent-usability-quiet", 4, "files=1");
            assertFalse(stderr.toString(StandardCharsets.UTF_8).contains("agent-usability-quiet"));
            String file = Files.readString(temp.resolve(WorkbenchLaunchTiming.FILE_NAME));
            assertTrue(file.contains("stage=agent-usability-quiet"), file);
            System.setProperty("pkb_workbench_timing", "true");
            WorkbenchLaunchTiming.record(temp, "launcher", "agent-usability-loud", 5, "");
            assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("stage=agent-usability-loud"));
        } finally {
            System.setErr(original);
            if (previous == null) System.clearProperty("pkb_workbench_timing");
            else System.setProperty("pkb_workbench_timing", previous);
        }
    }

    @Test
    void statusAndEventsJsonOmitTimingProse() throws Exception {
        try (LiveSession session = LiveSession.open(temp, null)) {
            Capture status = sessionCommand("status", temp.toString(), "--json");
            assertEquals(0, status.code, status.err + status.out);
            assertFalse(status.out.contains("SESSION pid="), status.out);
            assertFalse(status.out.contains("elapsed-run-ms="), status.out);
            assertTrue(status.out.contains("\"mode\":\"cli-session\""), status.out);
            assertEquals(1, status.out.strip().lines().count());

            Capture events = sessionCommand("events", temp.toString(), "--json");
            assertEquals(0, events.code, events.err + events.out);
            assertFalse(events.out.contains("elapsed-run-ms="), events.out);
            assertFalse(events.out.contains("sleep-gap="), events.out);
            assertTrue(events.out.contains("\"events\""), events.out);

            Capture prose = sessionCommand("events", temp.toString());
            assertTrue(prose.out.contains("elapsed-run-ms="), prose.out);
            assertTrue(prose.out.contains("sleep-gap="), prose.out);
        }
    }

    private Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private Capture sessionCommand(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                launch -> new ProcessBuilder("true").start(),
                java.net.http.HttpClient.newHttpClient(),
                (project, runId, agentId) -> {
                    throw new IOException("ui starter not used");
                }
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }

    private static final class LiveSession implements AutoCloseable {
        private final HttpServer http;
        private final Path stateFile;
        private final StringBuilder seen = new StringBuilder();

        private LiveSession(HttpServer http, Path stateFile) {
            this.http = http;
            this.stateFile = stateFile;
        }

        static LiveSession open(Path project, String runId) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LiveSession[] box = new LiveSession[1];
            http.createContext("/health", exchange -> write(exchange, "{\"status\":\"ok\"}"));
            http.createContext("/tools/workbench_events", exchange -> {
                exchange.getRequestBody().readAllBytes();
                write(exchange, "{\"events\":[]}");
            });
            http.createContext("/commands", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (box[0] != null) box[0].seen.append(body);
                write(exchange, "{\"id\":\"step-held\",\"status\":\"QUEUED\"}");
            });
            http.start();
            Path stateFile = runId == null || runId.isBlank()
                    ? PickleballLocalLayout.cliSessionState(project)
                    : AgentCoordination.runDirectory(project, runId).resolve("session").resolve("cli-session.json");
            Files.createDirectories(stateFile.getParent());
            LiveSession session = new LiveSession(http, stateFile);
            box[0] = session;
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            Files.writeString(stateFile, """
                    {"url":"%s","token":"test-token","pid":%d,"mode":"cli-session"}
                    """.formatted(url, ProcessHandle.current().pid()));
            return session;
        }

        String body() {
            return seen.toString();
        }

        @Override
        public void close() {
            http.stop(0);
        }

        private static void write(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
