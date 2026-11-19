package tools.dscode.launcher;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.StoreScope;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One behavior test per B1 verb. The coverage gate reads the covers_command_* names. */
class ConsumerReadyCliTest {
    private static final Pattern SESSION = Pattern.compile("session=(live[0-9a-f]+)");

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void covers_command_start() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        Capture missing = run("start", "--browser=chrome", temp.toString());
        assertEquals(2, missing.code, missing.err);
        assertTrue(missing.err.contains("error:"), missing.err);
        Capture started = run(
                "start",
                "--url=http://127.0.0.1:9/ready",
                "--browser=chrome",
                "--env=FOO=bar",
                temp.toString()
        );
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("live=true"), started.out);
        assertTrue(started.out.contains("navigated=false"), started.out);
        assertTrue(started.out.contains("error:"), started.out);
        assertTrue(started.out.contains("browser=chrome"), started.out);
        assertTrue(started.out.contains("env=FOO=bar"), started.out);
        String session = session(started.out);
        Capture chat = run("chat-send", "--text=hello-from-start", temp.toString());
        assertEquals(0, chat.code, chat.err);
        Path chatFile = temp.resolve(".pickleball/runs/" + session + "/agent/chat/run.jsonl");
        assertTrue(Files.isRegularFile(chatFile), chatFile.toString());
        assertTrue(Files.readString(chatFile).contains("hello-from-start"));
        assertFalse(Files.exists(temp.resolve(".pickleball/agent/chat/run.jsonl")));
    }

    @Test
    void covers_command_chat_wait() throws Exception {
        Capture started = run("start", "--url=http://127.0.0.1:9/", "--browser=chrome", temp.toString());
        assertEquals(0, started.code, started.err);
        Capture empty = run("chat_wait", "--timeout=0", temp.toString());
        assertEquals(1, empty.code, empty.out + empty.err);
        assertTrue(empty.err.contains("timed out"), empty.err);
        Capture sent = run("chat-send", "--text=wait-for-me", temp.toString());
        assertEquals(0, sent.code, sent.err);
        Capture waited = run("chat_wait", "--timeout=0", temp.toString());
        assertEquals(0, waited.code, waited.err);
        assertTrue(waited.out.contains("wait-for-me"), waited.out);
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
    }

    @Test
    void covers_command_history_summary() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        writeRun("left", "PASSED");
        writeRun("right", "FAILED");
        writeRun("skip", "SKIPPED");
        Capture summary = run("history-summary", temp.toString());
        assertEquals(0, summary.code, summary.err);
        assertTrue(summary.out.contains("runs=3"), summary.out);
        assertTrue(summary.out.contains("passed=1"), summary.out);
        assertTrue(summary.out.contains("failed=1"), summary.out);
        assertTrue(summary.out.contains("skipped=1"), summary.out);
    }

    @Test
    void covers_command_history_query() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        writeRun("samplehist", "PASSED");
        Capture listed = run("history-query", "runs", temp.toString());
        assertEquals(0, listed.code, listed.err + listed.out);
        assertTrue(listed.out.contains("samplehist"), listed.out);
    }

    @Test
    void covers_command_entity_lookup() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        StoreScope.clear();
        try {
            Path file = temp.resolve("store-entities/Account/ada.entity");
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    type: Account
                    id: ada
                    version: 1
                    payload: {"name":"Ada"}
                    """);
            Capture found = run("entity-lookup", "--ref=<entity:Account#ada.name>", temp.toString());
            assertEquals(0, found.code, found.err);
            assertTrue(found.out.contains("Ada"), found.out);
            Capture miss = run("entity-lookup", "--ref=entity:Account#missing.name", temp.toString());
            assertEquals(1, miss.code, miss.out);
            assertTrue(miss.out.contains("miss"), miss.out);
        } finally {
            StoreScope.clear();
        }
    }

    @Test
    void covers_command_open_run() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        writeRun("shown", "PASSED");
        Capture opened = run("open-run", "--run-id=shown", "--tab=tracking", temp.toString());
        assertEquals(0, opened.code, opened.err);
        assertTrue(opened.out.contains("tab=tracking"), opened.out);
        assertTrue(opened.out.contains("run-id=shown"), opened.out);
        String body = Files.readString(temp.resolve(".pickleball/workbench/open-run.json"));
        assertTrue(body.contains("\"tab\":\"tracking\""), body);
        assertTrue(body.contains("shown"), body);
        Capture bad = run("open-run", "--run-id=shown", "--tab=other", temp.toString());
        assertEquals(2, bad.code, bad.err);
    }

    @Test
    void covers_command_bridge_health() throws Exception {
        assertTrue(WorkbenchAgentCommands.class.getName().contains("WorkbenchAgentCommands"));
        Capture missing = run("bridge-health", temp.toString());
        assertEquals(2, missing.code, missing.err);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/health", exchange -> {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            Capture health = run("bridge-health", "--url=" + url, temp.toString());
            assertEquals(0, health.code, health.err);
            assertTrue(health.out.contains("status=200"), health.out);
            assertTrue(health.out.contains("ok"), health.out);
        } finally {
            server.stop(0);
        }
    }

    private void writeRun(String id, String status) throws Exception {
        Path record = temp.resolve(".pickleball/runs/" + id + "/record.json");
        Files.createDirectories(record.getParent());
        Files.writeString(record, "{\"status\":\"" + status + "\"}\n");
    }

    private static String session(String out) {
        Matcher matcher = SESSION.matcher(out);
        assertTrue(matcher.find(), out);
        return matcher.group(1);
    }

    private Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
