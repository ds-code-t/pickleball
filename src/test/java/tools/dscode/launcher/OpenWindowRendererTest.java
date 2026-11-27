package tools.dscode.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.runtime.ProcessTrees;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code open-window} goes through {@link VerbDispatch} and starts the Node
 * renderer. A cli-session that answers {@code playback=NODE} must not fall
 * through to the controller {@code ui} stub.
 */
class OpenWindowRendererTest {
    private static final String STUB = "The Node renderer is started by the pickleball launcher";

    @TempDir
    Path project;

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void openWindowStartsTheRendererWhenPlaybackIsNode() throws Exception {
        Path run = project.resolve(".pickleball").resolve("runs").resolve("alpha");
        Files.createDirectories(run.resolve("reports"));
        Files.createDirectories(run.resolve("config"));
        Files.writeString(run.resolve("record.json"), "{\"runId\":\"alpha\",\"marker\":\"ALPHA-RECORD\"}\n");
        Files.writeString(run.resolve("alpha.log"), "ALPHA-RECORD\n");
        long pid = -1;
        try (CliNode cli = CliNode.start(project)) {
            Result opened = dispatch("open-window", "--run-id=alpha", "--agent=agent-1");
            assertEquals(0, opened.code, opened.err + opened.out);
            assertTrue(opened.out.contains("ACK WINDOW opened"), opened.out);
            assertTrue(opened.out.contains("ALPHA-RECORD"), opened.out);
            assertTrue(opened.out.contains("does not start a test"), opened.out);
            Path attach = PickleballLocalLayout.attachFile(project);
            assertTrue(Files.isRegularFile(attach), "attach.json");
            String attachText = Files.readString(attach);
            assertTrue(attachText.contains("\"mode\"") && attachText.contains("ui-attach"), attachText);
            pid = pidOf(attachText);
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "renderer pid " + pid);
            String executable = ProcessHandle.of(pid).flatMap(handle -> handle.info().command()).orElse("");
            String command = ProcessHandle.of(pid).flatMap(handle -> handle.info().commandLine()).orElse("");
            Path args = PickleballLocalLayout.workbenchStateRoot(project).resolve("renderer.args");
            String argText = Files.isRegularFile(args) ? Files.readString(args) : "";
            assertTrue(executable.toLowerCase(java.util.Locale.ROOT).contains("java"), executable);
            assertTrue(argText.contains("WorkbenchRendererHost"), argText);
            assertFalse(argText.contains("WorkbenchApplication"), argText);
            if (!command.isBlank()) {
                assertTrue(command.contains("renderer.args") || command.contains("WorkbenchRendererHost"), command);
                assertFalse(command.contains("WorkbenchApplication"), command);
            }
            Path log = PickleballLocalLayout.workbenchStateRoot(project).resolve("window.log");
            String logged = Files.isRegularFile(log) ? Files.readString(log) : "";
            assertFalse(logged.contains(STUB), logged);
            String health = health(urlOf(attachText));
            assertTrue(health.contains("\"status\":\"ok\"") || health.contains("\"status\": \"ok\""), health);
            Path host = project.resolve(".pickleball").resolve("workbench-ui").resolve("host.json");
            assertTrue(Files.isRegularFile(host), "renderer host.json");
            String page = page(Files.readString(host));
            assertTrue(page.contains("id=\"monaco\""), "renderer page");
            long first = pid;
            Result shown = dispatch("show-run", "--run-id=alpha", "--agent=agent-1");
            assertEquals(0, shown.code, shown.err + shown.out);
            assertTrue(shown.out.contains("ACK WINDOW shown"), shown.out);
            assertEquals(first, pidOf(Files.readString(attach)));
            assertTrue(cli.sawOpenUi, "open-ui was not posted to the cli-session");
            Result closed = dispatch("close-window");
            assertEquals(0, closed.code, closed.err + closed.out);
            assertTrue(closed.out.contains("ACK WINDOW closed"), closed.out);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                Thread.sleep(100);
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "renderer still alive");
        } finally {
            if (pid > 0) {
                ProcessHandle.of(pid).ifPresent(ProcessTrees::killTree);
            }
        }
    }

    private Result dispatch(String... tokens) {
        String[] argv = new String[tokens.length + 1];
        argv[0] = tokens[0];
        argv[1] = project.toAbsolutePath().toString();
        System.arraycopy(tokens, 1, argv, 2, tokens.length - 1);
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);
        int code = VerbDispatch.run(tokens[0], VerbArgs.capture(argv), out, err);
        return new Result(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    private static long pidOf(String json) {
        int at = json.indexOf("\"pid\"");
        int colon = json.indexOf(':', at);
        int end = colon;
        while (end < json.length() && !Character.isDigit(json.charAt(end))) end++;
        int start = end;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private static String urlOf(String json) {
        int at = json.indexOf("\"url\"");
        int quote = json.indexOf('"', json.indexOf(':', at) + 1);
        int end = json.indexOf('"', quote + 1);
        return json.substring(quote + 1, end);
    }

    private static String health(String url) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url + "/health")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        return response.statusCode() + " " + response.body();
    }

    private static String page(String hostJson) throws Exception {
        int portAt = hostJson.indexOf("\"port\"");
        int portColon = hostJson.indexOf(':', portAt);
        int portEnd = portColon + 1;
        while (portEnd < hostJson.length() && !Character.isDigit(hostJson.charAt(portEnd))) portEnd++;
        int portStart = portEnd;
        while (portEnd < hostJson.length() && Character.isDigit(hostJson.charAt(portEnd))) portEnd++;
        String port = hostJson.substring(portStart, portEnd);
        int tokenAt = hostJson.indexOf("\"token\"");
        int tokenQuote = hostJson.indexOf('"', hostJson.indexOf(':', tokenAt) + 1);
        int tokenEnd = hostJson.indexOf('"', tokenQuote + 1);
        String token = hostJson.substring(tokenQuote + 1, tokenEnd);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/?t=" + token))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode(), "renderer home");
        return response.body();
    }

    private record Result(int code, String out, String err) {
    }

    /**
     * Healthy cli-session whose {@code open-ui} result is {@code playback=NODE}.
     */
    private static final class CliNode implements AutoCloseable {
        private final HttpServer http;
        private volatile boolean sawOpenUi;

        private CliNode(HttpServer http) {
            this.http = http;
        }

        static CliNode start(Path project) throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CliNode node = new CliNode(http);
            http.createContext("/health", exchange -> write(exchange, 200, "{\"status\":\"ok\"}"));
            http.createContext("/commands", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("POST".equals(exchange.getRequestMethod()) && "/commands".equals(path)) {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (body.contains("open-ui")) node.sawOpenUi = true;
                    write(exchange, 200, "{\"ack\":true,\"id\":\"open-1\",\"status\":\"QUEUED\"}");
                    return;
                }
                exchange.getRequestBody().readAllBytes();
                write(exchange, 200, "{\"id\":\"open-1\",\"status\":\"SUCCESS\",\"result\":{\"playback\":\"NODE\"}}");
            });
            http.start();
            String url = "http://127.0.0.1:" + http.getAddress().getPort();
            Path file = PickleballLocalLayout.cliSessionState(project);
            Files.createDirectories(file.getParent());
            Files.writeString(file, """
                    {
                      "url": "%s",
                      "token": "cli-token",
                      "pid": %d,
                      "project": "%s",
                      "mode": "cli-session",
                      "bind": "127.0.0.1"
                    }
                    """.formatted(url, ProcessHandle.current().pid(), project.toAbsolutePath().toString().replace("\\", "\\\\")));
            return node;
        }

        @Override
        public void close() {
            http.stop(0);
        }

        private static void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }
    }
}
