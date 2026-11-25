package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P1 Windows repro for typing and select inside a cross-origin iframe (D8, D9),
 * and for short ids on highlight, label, arrow, step-badge, unstep-badge and guide (D13, D14, D16, L1).
 */
class ConsumerFixesFrameLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TYPE_RUNGS = Set.of("cdp-insertText", "cdp-keys", "input-chain");

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void crossOriginFrameTypeSelectAndShortIdPlacements() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        HttpServer child = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        child.createContext("/child", exchange -> write(exchange, """
                <!DOCTYPE html><html><body>
                <label for="city">City</label>
                <input id="city" name="city">
                <label for="color">Color</label>
                <select id="color">
                  <option value="">Pick</option>
                  <option value="Red">Red</option>
                  <option value="Blue">Blue</option>
                </select>
                <button id="go" type="button">Inner Go</button>
                </body></html>
                """.getBytes(StandardCharsets.UTF_8)));
        child.start();
        String childUrl = "http://127.0.0.1:" + child.getAddress().getPort() + "/child";
        HttpServer parent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        parent.createContext("/parent", exchange -> write(exchange, ("""
                <!DOCTYPE html><html><head><title>Parent</title></head><body>
                <p>Parent page</p>
                <iframe id="guest" src="%s"></iframe>
                </body></html>
                """).formatted(childUrl).getBytes(StandardCharsets.UTF_8)));
        parent.start();
        try {
            Capture started = run(
                    "start", "--url=http://127.0.0.1:" + parent.getAddress().getPort() + "/parent",
                    "--browser=chrome-headless", "--run-id=frame-fix");
            assertEquals(0, started.code, started.err + started.out);

            JsonNode city = awaitRole("textbox", "City");
            assertFalse(city.path("frame").asText("").isBlank(), city.toString());
            String cityId = city.path("id").asText();
            assertTrue(cityId.matches("s\\d+\\.h\\d+"), city.toString());

            Capture typed = run(
                    "act", "--target=browser:auto", "--kind=type", "--query=" + cityId,
                    "--value=Austin", "--run-id=frame-fix");
            assertEquals(0, typed.code, typed.err + typed.out);
            JsonNode typeBody = JSON.readTree(typed.out);
            assertTrue(typeBody.path("verified").asBoolean(false), typed.out);
            assertTrue(typeBody.path("applied").asBoolean(false), typed.out);
            assertEquals("Austin", typeBody.path("value").asText(), typed.out);
            assertTrue(TYPE_RUNGS.contains(typeBody.path("via").asText()), typed.out);

            JsonNode color = awaitRole("combobox", "Color");
            Capture selected = run(
                    "act", "--target=browser:auto", "--kind=select", "--query=" + color.path("id").asText(),
                    "--value=Red", "--run-id=frame-fix");
            assertEquals(0, selected.code, selected.err + selected.out);
            JsonNode selectBody = JSON.readTree(selected.out);
            assertTrue(selectBody.path("verified").asBoolean(false), selected.out);
            assertTrue(selectBody.path("applied").asBoolean(false), selected.out);
            assertEquals("Red", selectBody.path("value").asText(), selected.out);

            JsonNode go = awaitRole("button", "Inner Go");
            String id = go.path("id").asText();
            assertTrue(id.matches("s\\d+\\.h\\d+"), go.toString());
            assertFalse(go.path("frame").asText("").isBlank(), go.toString());

            Capture painted = run(
                    "highlight", "--query=" + id, "--preset=yellow", "--target=browser:auto", "--run-id=frame-fix");
            assertEquals(0, painted.code, painted.err + painted.out);
            assertTrue(JSON.readTree(painted.out).path("applied").asBoolean(false), painted.out);

            for (String[] verb : new String[][]{
                    {"label", "--text=Here", "--handle=" + id},
                    {"arrow", "--handle=" + id, "--direction=up"},
                    {"step-badge", "--handle=" + id, "--number=1", "--text=Go"}
            }) {
                Capture placed = run(withRun(verb));
                assertEquals(0, placed.code, verb[0] + " " + placed.err + placed.out);
                assertFalse((placed.out + placed.err).contains("unknown handle"), verb[0] + " " + placed.out);
            }
            Capture removed = run("unstep-badge", "--handle=" + id, "--run-id=frame-fix");
            assertEquals(0, removed.code, removed.err + removed.out);
            assertFalse((removed.out + removed.err).contains("unknown handle"), removed.out);

            Capture guided = run(
                    "guide",
                    "--steps=[{\"t\":\"" + id + "\",\"say\":\"Press Inner Go\",\"do\":\"click\"}]",
                    "--id=g-short",
                    "--run-id=frame-fix");
            assertEquals(0, guided.code, guided.err + guided.out);
            assertFalse(guided.out.contains("\"unresolved\":true"), guided.out);

            Capture byRole = run(
                    "guide",
                    "--steps=[{\"role\":\"textbox\",\"name\":\"City\",\"say\":\"Type the city\",\"do\":\"input\"}]",
                    "--id=g-role",
                    "--run-id=frame-fix");
            assertEquals(0, byRole.code, byRole.err + byRole.out);
            assertFalse(byRole.out.contains("\"unresolved\":true"), byRole.out);
        } finally {
            parent.stop(0);
            child.stop(0);
        }
    }

    private JsonNode awaitRole(String role, String name) throws Exception {
        JsonNode last = JSON.createObjectNode();
        for (int attempt = 0; attempt < 40; attempt++) {
            Capture found = run(
                    "find", "--target=browser:auto", "--role=" + role, "--name=" + name,
                    "--frame=all", "--run-id=frame-fix");
            if (found.code == 0) {
                JsonNode items = JSON.readTree(found.out).path("items");
                if (items.size() > 0 && name.equals(items.get(0).path("name").asText())) return items.get(0);
                last = JSON.readTree(found.out);
            } else {
                last = JSON.readTree(found.out.isBlank() ? "{}" : found.out);
            }
            Thread.sleep(250);
        }
        assertNotNull(last);
        throw new AssertionError("missing " + role + " " + name + " " + last);
    }

    private static String[] withRun(String[] verb) {
        String[] args = new String[verb.length + 1];
        args[0] = verb[0];
        System.arraycopy(verb, 1, args, 1, verb.length - 1);
        args[args.length - 1] = "--run-id=frame-fix";
        return args;
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private Capture run(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
