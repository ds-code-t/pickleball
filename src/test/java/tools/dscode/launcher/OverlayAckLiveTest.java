package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A click on a shown overlay in the real page sets {@code read_at} on {@code overlay-poll}.
 * The overlay and the poll run in a separate {@link PickleballWorkbenchLauncher} process.
 */
class OverlayAckLiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path project;

    private HttpServer site;

    @AfterEach
    void stop() {
        ConsumerReadyCommands.stopQuiet(project);
        if (site != null) site.stop(0);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void overlayClickSetsReadAtThroughTheRuntime() throws Exception {
        String browser = BrowserSession.findChrome() != null ? "chrome-headless"
                : BrowserSession.findEdge() != null ? "edge-headless" : null;
        assertNotNull(browser, "locally installed Chrome or Edge is required");

        site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        site.createContext("/fixture", exchange -> {
            byte[] body = "<!DOCTYPE html><html><body><p id=\"page\">page</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        site.start();
        String url = "http://127.0.0.1:" + site.getAddress().getPort() + "/fixture";

        String session = session();
        Capture started = inProcess("start", "--url=" + url, "--browser=" + browser, "--session=" + session);
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);

        Capture shown = cli("overlay", "--text=Hello", "--x=40", "--y=40", "--session=" + session);
        assertEquals(0, shown.code, shown.out);
        String id = JSON.readTree(shown.out).path("id").asText("");
        assertFalse(id.isBlank(), shown.out);
        await("document.querySelectorAll('[data-pkb-kind=\"overlay\"]').length", "1");

        String point = eval("(function(){var node=document.querySelector('[data-pkb-kind=\"overlay\"]');"
                + "var r=node.getBoundingClientRect(); return (r.x+r.width/2)+','+(r.y+r.height/2);})()");
        String[] parts = point.split(",");
        try (CdpConnection cdp = connect()) {
            cdp.call("Input.dispatchMouseEvent", mouse("mousePressed", Double.parseDouble(parts[0]), Double.parseDouble(parts[1])), Duration.ofSeconds(5));
            cdp.call("Input.dispatchMouseEvent", mouse("mouseReleased", Double.parseDouble(parts[0]), Double.parseDouble(parts[1])), Duration.ofSeconds(5));
        }
        Thread.sleep(300);

        Capture polled = cli("overlay-poll", "--session=" + session);
        assertEquals(0, polled.code, polled.out);
        assertTrue(hasReadAt(JSON.readTree(polled.out), id), polled.out);
    }

    private static boolean hasReadAt(JsonNode body, String id) {
        for (String field : new String[]{"events", "items"}) {
            JsonNode list = body.path(field);
            if (!list.isArray()) continue;
            for (JsonNode event : list) {
                if (!id.equals(event.path("id").asText(""))) continue;
                String type = event.path("type").asText("");
                if (!"click".equals(type) && !"read".equals(type) && !"ack".equals(type)) continue;
                JsonNode readAt = event.get("read_at");
                if (readAt != null && !readAt.isNull() && !readAt.asText("").isBlank()) return true;
            }
        }
        return false;
    }

    private String session() throws Exception {
        Capture created = inProcess("spine", "session", "new");
        assertEquals(0, created.code, created.err + created.out);
        return JSON.readTree(created.out).path("state").path("session").asText();
    }

    private Capture inProcess(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = project.toString();
        if (args.length > 1) System.arraycopy(args, 1, command, 2, args.length - 1);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private Capture cli(String... args) throws Exception {
        Path argFile = project.resolve(".pickleball").resolve("cli-" + System.nanoTime() + ".args");
        Files.createDirectories(argFile.getParent());
        String classpath = System.getProperty("java.class.path", "");
        StringBuilder body = new StringBuilder();
        body.append("-cp\n").append(quote(classpath)).append('\n');
        body.append("tools.dscode.launcher.PickleballWorkbenchLauncher\n");
        for (String arg : args) body.append(quote(arg)).append('\n');
        body.append(quote(project.toAbsolutePath().normalize().toString())).append('\n');
        Files.writeString(argFile, body.toString(), StandardCharsets.UTF_8);
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(javaBin.toString(), "@" + argFile.toAbsolutePath())
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            return new Capture(1, output + "\ncli timed out", "");
        }
        return new Capture(process.exitValue(), output, "");
    }

    private static String quote(String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void await(String expression, String expected) throws Exception {
        String last = "";
        for (int attempt = 0; attempt < 40; attempt++) {
            last = eval(expression);
            if (expected.equals(last)) return;
            Thread.sleep(250);
        }
        throw new AssertionError(expression + " stayed " + last + " wanted " + expected);
    }

    private String eval(String expression) throws Exception {
        try (CdpConnection cdp = connect()) {
            ObjectNode params = Jsons.object();
            params.put("expression", expression);
            params.put("returnByValue", true);
            JsonNode value = cdp.call("Runtime.evaluate", params, Duration.ofSeconds(5));
            return value.path("result").path("value").asText("");
        }
    }

    private static ObjectNode mouse(String type, double x, double y) {
        ObjectNode node = Jsons.object();
        node.put("type", type);
        node.put("x", x);
        node.put("y", y);
        node.put("button", "left");
        node.put("clickCount", 1);
        return node;
    }

    private CdpConnection connect() throws Exception {
        LiveHostFile.Host host = LiveHostFile.read(project).orElseThrow();
        HttpRequest health = HttpRequest.newBuilder(URI.create(trim(host.url()) + "/health"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + host.token())
                .GET()
                .build();
        HttpResponse<String> body = HTTP.send(health, HttpResponse.BodyHandlers.ofString());
        String socket = JSON.readTree(body.body()).path("cdp").asText("");
        assertFalse(socket.isBlank(), body.body());
        return CdpConnection.connect(URI.create(socket), Duration.ofSeconds(10));
    }

    private static String trim(String url) {
        if (url.endsWith("/")) return url.substring(0, url.length() - 1);
        return url;
    }

    private record Capture(int code, String out, String err) {
    }
}
