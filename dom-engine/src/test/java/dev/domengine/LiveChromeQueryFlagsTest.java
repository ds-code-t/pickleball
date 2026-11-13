package dev.domengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.cli.CliResult;
import dev.domengine.cli.DomEngineMain;
import dev.domengine.json.Jsons;
import dev.domengine.log.EngineLog;
import dev.domengine.session.BrowserSession;
import dev.domengine.session.SessionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live Chrome proof for wait, hover, displayed filter, frames, node identity, all-frames, and batched eval.
 * {@code --nth} stays 0-based, so {@code --nth 1} is the second visible hit.
 */
class LiveChromeQueryFlagsTest {
    @AfterEach
    void clean() {
        SessionRegistry.resetForTests();
        EngineLog.resetForTests();
    }

    @Test
    void reliedOnFlagsWorkOnALivePage() throws Exception {
        assumeTrue(BrowserSession.findChrome() != null, "skipped: Chrome is not present");
        byte[] frame = page("""
                <!DOCTYPE html><body>
                <button id="inner">Inner</button>
                </body>
                """);
        byte[] deep = page("""
                <!DOCTYPE html><body><button id="deep">Deep</button></body>
                """);
        byte[] nest = page("""
                <!DOCTYPE html><body><iframe id="mid" src="/deep"></iframe></body>
                """);
        HttpServer cross = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cross.createContext("/", exchange -> write(exchange, page("""
                <!DOCTYPE html><body><button id="xinner">X</button></body>
                """)));
        cross.start();
        String crossUrl = "http://127.0.0.1:" + cross.getAddress().getPort() + "/";
        byte[] main = page("""
                <!DOCTYPE html><html><head>
                <style>
                  #b2:hover { color: rgb(1, 2, 3); }
                  .item { display: block; width: 30px; height: 16px; }
                </style></head><body>
                <button id="b1">B1</button>
                <button id="b2">B2</button>
                <button id="b4">B4</button>
                <div id="late" style="display:none">Late</div>
                <div class="item" id="hid" style="display:none">H</div>
                <div class="item" id="vis" style="visibility:hidden">V</div>
                <div class="item" id="opa" style="opacity:0">O</div>
                <div class="item" id="zero" style="width:0;height:0;overflow:hidden"></div>
                <div class="item" id="anc" style="display:none"><span class="item" id="nested">N</span></div>
                <div class="item" id="ok1">Ok1</div>
                <div class="item" id="ok2">Ok2</div>
                <a class="del" id="d1" href="#">Del1</a>
                <a class="del" id="d2" href="#">Del2</a>
                <div id="hoverme">nohover</div>
                <input id="box" type="checkbox">
                <select id="sel"><option id="o1">A</option><option id="o2">B</option></select>
                <fieldset disabled><input id="fs"></fieldset>
                <input id="focusme">
                <iframe id="frame" src="/frame"></iframe>
                <iframe id="slow"></iframe>
                <iframe id="cross" src="%s"></iframe>
                <iframe id="nest" src="/nest"></iframe>
                <div id="host"></div>
                <div id="status">idle</div>
                <script>
                  document.getElementById('box').checked = true;
                  document.getElementById('sel').selectedIndex = 1;
                  document.getElementById('host').attachShadow({mode:'open'}).innerHTML = '<span id="shadowed">Shadow</span>';
                  document.getElementById('d1').addEventListener('click', function (e) { e.preventDefault(); document.getElementById('status').textContent = 'd1'; });
                  document.getElementById('d2').addEventListener('click', function (e) { e.preventDefault(); document.getElementById('status').textContent = 'd2'; });
                  document.getElementById('hoverme').addEventListener('mouseover', function () { this.textContent = 'hovered'; });
                  window.reveal = function () { setTimeout(function () { document.getElementById('late').style.display = 'block'; }, 400); };
                  window.addNode = function () { setTimeout(function () { var n = document.createElement('div'); n.id = 'added'; document.body.appendChild(n); }, 300); };
                  window.drop = function () { setTimeout(function () { var n = document.getElementById('b4'); if (n) n.remove(); }, 300); };
                  window.armSlow = function () { setTimeout(function () { document.getElementById('slow').src = '/frame'; }, 300); };
                  window.hideLate = function () { document.getElementById('late').style.display = 'none'; };
                </script>
                </body></html>
                """.formatted(crossUrl));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> write(exchange, main));
        server.createContext("/frame", exchange -> write(exchange, frame));
        server.createContext("/deep", exchange -> write(exchange, deep));
        server.createContext("/nest", exchange -> write(exchange, nest));
        server.start();
        Path dir = Files.createTempDirectory("domengine-live-flags");
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            CliResult created = DomEngineMain.dispatch(List.of(
                    "session", "create", "--id", "live-flags", "--url", url, "--session-dir", dir.toString()));
            assertEquals(0, created.exit(), created.stderr());

            assertEquals(List.of("ok1", "ok2"), ids(query("--css", ".item", "--filter", "displayed")));
            JsonNode counted = query("--css", ".item", "--filter", "displayed", "--count-only");
            assertEquals(2, counted.path("count").asInt());
            assertEquals(List.of("ok2"), ids(query("--css", ".item", "--filter", "displayed", "--nth", "1")));
            assertEquals(2, query("--css", "button", "--limit", "2").path("nodes").size());
            Path logDir = Files.createTempDirectory("engine-eval-log");
            EngineLog.configure(Map.of(
                    "DOMENGINE_LOG", "cdp",
                    "DOMENGINE_LOG_LEVEL", "debug",
                    "DOMENGINE_LOG_DIR", logDir.toString()));
            JsonNode buttons = query("--css", "button", "--limit", "3");
            StringBuilder nodeIds = new StringBuilder();
            for (JsonNode node : buttons.path("nodes")) {
                if (!nodeIds.isEmpty()) nodeIds.append(',');
                nodeIds.append(node.path("backend_node_id").asText());
            }
            String before = logs(logDir);
            CliResult batched = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-flags", "--node-ids", nodeIds.toString(), "--source",
                    "function(){ return this.id; }"));
            assertEquals(0, batched.exit(), batched.stderr());
            JsonNode values = Jsons.MAPPER.readTree(batched.stdout()).path("values");
            assertEquals(3, values.size(), batched.stdout());
            assertEquals(ids(buttons), List.of(
                    values.get(0).path("value").asText(),
                    values.get(1).path("value").asText(),
                    values.get(2).path("value").asText()));
            String after = logs(logDir);
            int calls = count(after, "Runtime.callFunctionOn") - count(before, "Runtime.callFunctionOn");
            assertEquals(1, calls, after);
            EngineLog.resetForTests();

            assertTrue(states("#hid").contains("hidden"), states("#hid").toString());
            assertTrue(states("#box").contains("checked"), states("#box").toString());
            assertTrue(states("#o2").contains("selected"), states("#o2").toString());
            assertTrue(states("#fs").contains("disabled"), states("#fs").toString());
            eval("function(){ document.getElementById('focusme').focus(); return true; }");
            assertTrue(states("#focusme").contains("focused"), states("#focusme").toString());

            hover("--css", "#hoverme");
            assertEquals("hovered", query("--css", "#hoverme").path("nodes").get(0).path("text").asText());
            hover("--css", "#b2", "--force-hover");
            JsonNode color = eval("function(){ return getComputedStyle(document.getElementById('b2')).color; }");
            assertTrue(color.path("value").asText().contains("1, 2, 3") || color.path("value").asText().contains("1,2,3"), color.toString());

            JsonNode dels = query("--css", ".del");
            assertEquals(2, dels.path("nodes").size());
            String second = dels.path("nodes").get(1).path("backend_node_id").asText();
            CliResult clicked = DomEngineMain.dispatch(List.of(
                    "click", "--session", "live-flags", "--node-id", second));
            assertEquals(0, clicked.exit(), clicked.stderr());
            assertEquals("d2", query("--css", "#status").path("nodes").get(0).path("text").asText());
            CliResult live = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-flags", "--node-id", second, "--source",
                    "function(){ return this.isConnected; }"));
            assertEquals(0, live.exit(), live.stderr());
            assertTrue(Jsons.MAPPER.readTree(live.stdout()).path("value").asBoolean());
            eval("function(){ document.getElementById('d2').remove(); return true; }");
            CliResult stale = DomEngineMain.dispatch(List.of(
                    "eval", "--session", "live-flags", "--node-id", second, "--source", "function(){ return 1; }"));
            assertEquals(1, stale.exit(), stale.stderr());
            assertEquals("stale", Jsons.MAPPER.readTree(stale.stderr()).path("code").asText());

            assertEquals(List.of("inner"), ids(query("--frame", "frame", "--css", "#inner")));
            CliResult missing = DomEngineMain.dispatch(List.of(
                    "query", "--session", "live-flags", "--frame", "missing", "--css", "#b1"));
            assertEquals(1, missing.exit(), missing.stderr());
            assertEquals("not_found", Jsons.MAPPER.readTree(missing.stderr()).path("code").asText());
            assertEquals(0, query("--css", "#shadowed").path("nodes").size());
            assertEquals(List.of("shadowed"), ids(query("--css", "#shadowed", "--pierce", "open")));
            assertEquals(List.of("shadowed"), ids(query("--shadow", "host", "--css", "#shadowed")));

            JsonNode all = query("--css", "button", "--all-frames");
            List<String> allIds = ids(all);
            assertTrue(allIds.contains("b1"), allIds.toString());
            assertTrue(allIds.contains("inner"), allIds.toString());
            assertTrue(allIds.contains("deep"), allIds.toString());
            assertTrue(allIds.indexOf("b1") < allIds.indexOf("inner"), allIds.toString());
            assertTrue(allIds.indexOf("inner") < allIds.indexOf("deep"), allIds.toString());
            String innerFrame = frameOf(all, "inner");
            String deepFrame = frameOf(all, "deep");
            assertFalse(innerFrame.isBlank(), all.toString());
            assertFalse(deepFrame.isBlank(), all.toString());
            assertTrue(frameOf(all, "b1").isBlank());

            assertEquals(List.of("xinner"), ids(query("--frame", "cross", "--css", "#xinner")));

            eval("function(){ window.armSlow(); return true; }");
            long slowStart = System.nanoTime();
            JsonNode slow = query("--frame", "slow", "--css", "#inner", "--wait-for", "attached", "--timeout-ms", "3000", "--poll-ms", "50");
            assertTrue(System.nanoTime() - slowStart >= 200_000_000L, "late frame returned too soon");
            assertEquals("inner", slow.path("nodes").get(0).path("attributes").path("id").asText());

            eval("function(){ window.reveal(); return true; }");
            long start = System.nanoTime();
            JsonNode visible = query("--css", "#late", "--wait-for", "visible", "--timeout-ms", "3000", "--poll-ms", "50");
            long elapsed = System.nanoTime() - start;
            assertTrue(elapsed >= 350_000_000L, "visible wait returned in " + elapsed);
            assertEquals(1, visible.path("nodes").size());
            CliResult timeout = DomEngineMain.dispatch(List.of(
                    "query", "--session", "live-flags", "--css", "#never", "--wait-for", "visible",
                    "--timeout-ms", "300", "--poll-ms", "50"));
            assertEquals(1, timeout.exit(), timeout.stderr());
            assertEquals("timeout", Jsons.MAPPER.readTree(timeout.stderr()).path("code").asText());
            eval("function(){ window.addNode(); return true; }");
            assertEquals(1, query("--css", "#added", "--wait-for", "attached", "--timeout-ms", "3000", "--poll-ms", "50").path("nodes").size());
            eval("function(){ window.drop(); return true; }");
            assertEquals(0, query("--css", "#b4", "--wait-for", "detached", "--timeout-ms", "3000", "--poll-ms", "50").path("nodes").size());
        } finally {
            server.stop(0);
            cross.stop(0);
        }
    }

    private static byte[] page(String html) {
        return html.getBytes(StandardCharsets.UTF_8);
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static JsonNode query(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("query");
        command.add("--session");
        command.add("live-flags");
        command.addAll(List.of(args));
        CliResult result = DomEngineMain.dispatch(command);
        assertEquals(0, result.exit(), result.stderr());
        return Jsons.MAPPER.readTree(result.stdout());
    }

    private static void hover(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("hover");
        command.add("--session");
        command.add("live-flags");
        command.addAll(List.of(args));
        CliResult result = DomEngineMain.dispatch(command);
        assertEquals(0, result.exit(), result.stderr());
    }

    private static JsonNode eval(String source) throws Exception {
        CliResult result = DomEngineMain.dispatch(List.of(
                "eval", "--session", "live-flags", "--css", "body", "--source", source));
        assertEquals(0, result.exit(), result.stderr());
        return Jsons.MAPPER.readTree(result.stdout());
    }

    private static List<String> ids(JsonNode json) {
        List<String> ids = new ArrayList<>();
        for (JsonNode node : json.path("nodes")) ids.add(node.path("attributes").path("id").asText());
        return ids;
    }

    private static List<String> states(String css) throws Exception {
        JsonNode json = query("--css", css);
        List<String> states = new ArrayList<>();
        json.path("composite").get(0).path("states").forEach(item -> states.add(item.asText()));
        return states;
    }

    private static String frameOf(JsonNode json, String id) {
        for (JsonNode node : json.path("nodes")) {
            if (id.equals(node.path("attributes").path("id").asText())) return node.path("frame").asText("");
        }
        return "";
    }

    private static String logs(Path dir) throws Exception {
        StringBuilder text = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) text.append(Files.readString(file));
        }
        return text.toString();
    }

    private static int count(String text, String token) {
        int n = 0;
        int at = 0;
        while (at >= 0) {
            at = text.indexOf(token, at);
            if (at < 0) break;
            n++;
            at += token.length();
        }
        return n;
    }
}
