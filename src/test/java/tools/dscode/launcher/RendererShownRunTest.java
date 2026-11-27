package tools.dscode.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
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
 * {@code open-window --run-id} and {@code show-run} put that run on the Node
 * renderer page. The marker is read from the served page, not from CLI stdout.
 */
class RendererShownRunTest {
    @TempDir
    Path project;

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void rendererPageShowsTheRunAndSwitchesOnShowRun() throws Exception {
        writeRun("alpha", "ALPHA-RECORD");
        writeRun("beta", "BETA-RECORD");
        SpineSession.create(project, "agent-1");
        long pid = -1;
        try (CliNode ignored = CliNode.start(project)) {
            Result opened = dispatch("open-window", "--run-id=alpha", "--agent=agent-1");
            assertEquals(0, opened.code, opened.err + opened.out);
            Path attach = PickleballLocalLayout.attachFile(project);
            assertTrue(Files.isRegularFile(attach), "attach.json");
            pid = pidOf(Files.readString(attach));
            String first = page();
            assertTrue(first.contains("id=\"shown-run\""), first);
            assertTrue(first.contains("data-run-id=\"alpha\""), first);
            assertTrue(first.contains("ALPHA-RECORD"), first);

            Result shown = dispatch("show-run", "--run-id=beta", "--agent=agent-1");
            assertEquals(0, shown.code, shown.err + shown.out);
            String second = page();
            assertTrue(second.contains("id=\"shown-run\""), second);
            assertTrue(second.contains("data-run-id=\"beta\""), second);
            assertTrue(second.contains("BETA-RECORD"), second);
            assertFalse(second.contains("ALPHA-RECORD"), second);
            assertFalse(second.contains("data-run-id=\"alpha\""), second);
        } finally {
            if (pid > 0) ProcessHandle.of(pid).ifPresent(ProcessTrees::killTree);
        }
    }

    private void writeRun(String id, String marker) throws IOException {
        Path run = project.resolve(".pickleball").resolve("runs").resolve(id);
        Files.createDirectories(run.resolve("reports"));
        Files.createDirectories(run.resolve("config"));
        Files.writeString(run.resolve("record.json"), "{\"runId\":\"" + id + "\",\"marker\":\"" + marker + "\"}\n");
        Files.writeString(run.resolve(id + ".log"), marker + "\n");
    }

    private String page() throws Exception {
        String hostJson = Files.readString(project.resolve(".pickleball").resolve("workbench-ui").resolve("host.json"));
        int portAt = hostJson.indexOf("\"port\"");
        int portEnd = hostJson.indexOf(':', portAt) + 1;
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
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private Result dispatch(String... tokens) {
        String[] argv = new String[tokens.length + 1];
        argv[0] = tokens[0];
        argv[1] = project.toAbsolutePath().toString();
        System.arraycopy(tokens, 1, argv, 2, tokens.length - 1);
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = VerbDispatch.run(
                tokens[0],
                VerbArgs.capture(argv),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        return new Result(code, outBytes.toString(StandardCharsets.UTF_8), errBytes.toString(StandardCharsets.UTF_8));
    }

    private static long pidOf(String json) {
        int at = json.indexOf("\"pid\"");
        int end = json.indexOf(':', at);
        while (end < json.length() && !Character.isDigit(json.charAt(end))) end++;
        int start = end;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private record Result(int code, String out, String err) {
    }

    private static final class CliNode implements AutoCloseable {
        private final HttpServer http;

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
                    exchange.getRequestBody().readAllBytes();
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
