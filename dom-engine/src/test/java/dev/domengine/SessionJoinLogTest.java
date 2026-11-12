package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.mcp.McpServer;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code session join} and {@code session log [--follow]} match the Rust composite CLI. */
class SessionJoinLogTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
    }

    @Test
    void capabilitiesAndHelpAdvertiseJoinAndLog() throws Exception {
        JsonNode caps = Jsons.MAPPER.readTree(DomEngineMain.dispatch(List.of("capabilities")).stdout());
        String verbs = caps.path("session_verbs").toString();
        assertTrue(verbs.contains("join"), verbs);
        assertTrue(verbs.contains("log"), verbs);
        assertTrue(verbs.contains("attach"), verbs);
        String help = DomEngineMain.dispatch(List.of("help")).stdout();
        assertTrue(help.contains("session join"), help);
        assertTrue(help.contains("session log"), help);
        assertTrue(help.contains("--follow"), help);
    }

    @Test
    void joinSelectsTheDefaultAndLogReadsEvents() throws Exception {
        Path dir = Files.createTempDirectory("domengine-join");
        try (FakeCdp alpha = FakeCdp.start(); FakeCdp beta = FakeCdp.start()) {
            assertEquals(0, session(dir, "attach", "--id", "alpha", "--cdp", alpha.http(), "--i-accept-local-attach").exit());
            assertEquals(0, session(dir, "attach", "--id", "beta", "--cdp", beta.http(), "--i-accept-local-attach").exit());

            JsonNode listed = Jsons.MAPPER.readTree(session(dir, "list").stdout());
            assertEquals("alpha", listed.path("joined").asText());
            assertEquals(2, listed.path("count").asInt());

            CliResult joined = session(dir, "join", "--id", "beta");
            assertEquals(0, joined.exit(), joined.stderr());
            JsonNode body = Jsons.MAPPER.readTree(joined.stdout());
            assertEquals("beta", body.path("joined").asText());
            assertEquals("beta", body.path("session").path("id").asText());
            assertFalse(body.path("session").path("launched").asBoolean());

            CliResult click = DomEngineMain.dispatch(List.of("click", "--css", "#go"));
            assertEquals(0, click.exit(), click.stderr());
            assertTrue(beta.methods.contains("Input.dispatchMouseEvent"), beta.methods.toString());
            assertFalse(alpha.methods.contains("Input.dispatchMouseEvent"), alpha.methods.toString());

            JsonNode events = Jsons.MAPPER.readTree(session(dir, "log", "--id", "beta").stdout());
            assertEquals("beta", events.path("id").asText());
            assertTrue(events.path("count").asInt() >= 2, events.toString());
            assertTrue(events.toString().contains("\"kind\" : \"join\"") || events.toString().contains("\"kind\":\"join\""), events.toString());
            assertTrue(events.toString().contains("click"), events.toString());
            assertTrue(Files.isRegularFile(dir.resolve("registry.json")));
            assertTrue(Files.isRegularFile(dir.resolve("beta.events.jsonl")));

            CliResult missing = session(dir, "join");
            assertEquals(1, missing.exit(), missing.stderr());
            assertEquals("invalid", Jsons.MAPPER.readTree(missing.stderr()).path("code").asText());
            assertTrue(Jsons.MAPPER.readTree(missing.stderr()).path("error").asText().contains("needs --id or --cdp"));

            CliResult unknown = session(dir, "join", "--id", "missing");
            assertEquals(1, unknown.exit(), unknown.stderr());
            assertEquals("not_found", Jsons.MAPPER.readTree(unknown.stderr()).path("code").asText());

            CliResult byCdp = session(dir, "join", "--cdp", alpha.http());
            assertEquals(0, byCdp.exit(), byCdp.stderr());
            assertEquals("alpha", Jsons.MAPPER.readTree(byCdp.stdout()).path("joined").asText());

            CliResult closed = session(dir, "close", "--id", "beta");
            assertEquals(0, closed.exit(), closed.stderr());
            assertFalse(Jsons.MAPPER.readTree(closed.stdout()).path("killed").asBoolean());
            JsonNode after = Jsons.MAPPER.readTree(session(dir, "log", "--id", "beta").stdout());
            assertTrue(after.toString().contains("close"), after.toString());
        }
    }

    @Test
    @Timeout(10)
    void followStreamsUntilTheSessionLeavesTheRegistry() throws Exception {
        Path dir = Files.createTempDirectory("domengine-follow");
        try (FakeCdp cdp = FakeCdp.start()) {
            assertEquals(0, session(dir, "attach", "--id", "alpha", "--cdp", cdp.http(), "--i-accept-local-attach").exit());
            AtomicReference<CliResult> followed = new AtomicReference<>();
            Thread stream = new Thread(() -> followed.set(session(dir, "log", "--id", "alpha", "--follow")));
            stream.start();
            Thread.sleep(250);
            CliResult closed = session(dir, "close", "--id", "alpha");
            assertEquals(0, closed.exit(), closed.stderr());
            stream.join(5000);
            assertFalse(stream.isAlive(), "session log --follow did not return after close");
            CliResult result = followed.get();
            assertEquals(0, result.exit(), result.stderr());
            assertTrue(result.stdout().contains("\"kind\":\"close\"") || result.stdout().contains("\"close\""), result.stdout());
        }
    }

    @Test
    void followIsRefusedInsideServeStdio() throws Exception {
        String request = "{\"id\":\"h9\",\"argv\":[\"session\",\"log\",\"--id\",\"ghost\",\"--follow\"]}\n";
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        DomEngineMain.serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), sink);
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("does not run inside serve --stdio"), text);
        assertTrue(text.contains("\"exit\":1"), text);
    }

    @Test
    void mcpForwardsSessionJoinAndLog() throws Exception {
        Path dir = Files.createTempDirectory("domengine-mcp-join");
        try (FakeCdp cdp = FakeCdp.start()) {
            assertEquals(0, session(dir, "attach", "--id", "alpha", "--cdp", cdp.http(), "--i-accept-local-attach").exit());
            String request = """
                    {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"domengine","arguments":{"argv":["session","join","--id","alpha","--session-dir","%s"]}}}
                    {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"domengine","arguments":{"argv":["session","log","--id","alpha","--session-dir","%s"]}}}
                    """.formatted(dir, dir);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            McpServer.serve(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
            String text = out.toString(StandardCharsets.UTF_8);
            assertTrue(text.contains("alpha"), text);
            assertTrue(text.contains("joined"), text);
            assertTrue(text.contains("events") || text.contains("join"), text);
            assertFalse(text.contains("isError"), text);
        }
    }

    @Test
    void joinByCdpAttachesWhenTheEndpointIsNew() throws Exception {
        Path dir = Files.createTempDirectory("domengine-join-cdp");
        try (FakeCdp cdp = FakeCdp.start()) {
            CliResult refused = session(dir, "join", "--cdp", cdp.http());
            assertEquals(1, refused.exit(), refused.stderr());
            assertEquals("invalid", Jsons.MAPPER.readTree(refused.stderr()).path("code").asText());
            assertTrue(Jsons.MAPPER.readTree(refused.stderr()).path("error").asText().contains("i-accept-local-attach"));

            CliResult joined = session(dir, "join", "--cdp", cdp.http(), "--i-accept-local-attach");
            assertEquals(0, joined.exit(), joined.stderr());
            JsonNode body = Jsons.MAPPER.readTree(joined.stdout());
            assertFalse(body.path("joined").asText().isBlank());
            assertEquals(body.path("joined").asText(), body.path("session").path("id").asText());
            assertFalse(body.path("session").path("launched").asBoolean());
        }
    }

    private static CliResult session(Path dir, String... args) {
        java.util.ArrayList<String> argv = new java.util.ArrayList<>();
        argv.add("session");
        argv.addAll(List.of(args));
        argv.add("--session-dir");
        argv.add(dir.toString());
        return DomEngineMain.dispatch(argv);
    }
}
