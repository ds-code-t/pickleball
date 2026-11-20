package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.PickleballLocalLayout;
import tools.dscode.launcher.WorkbenchAgentCommands;
import tools.dscode.launcher.WorkbenchSessionCommands;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingFieldsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopHost() {
        WorkbenchAgentCommands.run(
                new String[]{"live-stop", temp.toString()},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
    }

    @Test
    void freshCliWarmVerbAndExecuteStepCarryElapsedMs() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger posts = new AtomicInteger();
        server.createContext("/v1/agent/command", exchange -> {
            posts.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true,\"timing\":{\"totalMs\":3,\"jvmMs\":1}}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/health", exchange -> {
            byte[] response = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/commands", exchange -> {
            byte[] response = "{\"id\":\"c1\",\"status\":\"QUEUED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.createContext("/commands/c1", exchange -> {
            String text = "s".repeat(3000);
            byte[] response = ("{\"status\":\"SUCCESS\",\"result\":{\"text\":\"" + text + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            LiveBridgeFile.write(temp, new LiveBridgeFile.Bridge(
                    "127.0.0.1", port, "token-warm", ProcessHandle.current().pid(), "sess-warm"
            ));
            long[] samples = new long[2];
            long[] verbs = new long[2];
            for (int sample = 0; sample < samples.length; sample++) {
                long started = System.nanoTime();
                Process process = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        "tools.dscode.launcher.PickleballWorkbenchLauncher",
                        "highlight",
                        temp.toString(),
                        "--handle=save",
                        "--preset=yellow"
                ).redirectErrorStream(true).directory(workDir()).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                int code = process.waitFor();
                samples[sample] = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
                assertEquals(0, code, output);
                verbs[sample] = elapsedOf(output);
            }
            long warmMs = Math.min(samples[0], samples[1]);
            long verbMs = Math.min(verbs[0], verbs[1]);
            System.out.println("MEASURE warmMs=" + samples[0] + " " + samples[1] + " min=" + warmMs
                    + " elapsedMs=" + verbs[0] + " " + verbs[1] + " min=" + verbMs);
            assertTrue(posts.get() >= 2);
            // Process wall is a fresh JVM. GitHub macOS measured 2021 and 2058 ms on run 44683079221.
            // 4000 ms only rejects a stuck spawn. The verb target is elapsedMs.
            assertTrue(warmMs <= 4000, "warmMs=" + samples[0] + "," + samples[1]);
            assertTrue(verbMs <= 1000, "elapsedMs=" + verbs[0] + "," + verbs[1]);

            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int catalog = WorkbenchAgentCommands.run(
                    new String[]{"ui-catalog", temp.toString()},
                    new PrintStream(stdout, true, StandardCharsets.UTF_8),
                    new PrintStream(stderr, true, StandardCharsets.UTF_8)
            );
            String catalogOut = stdout.toString(StandardCharsets.UTF_8);
            assertEquals(0, catalog, catalogOut + stderr);
            assertTrue(catalogOut.contains("\"elapsedMs\""), catalogOut);

            LiveBridgeFile.delete(temp);
            Path session = PickleballLocalLayout.cliSessionState(temp);
            Files.createDirectories(session.getParent());
            Files.writeString(session,
                    "{\"url\":\"http://127.0.0.1:" + port + "\",\"token\":\"token-warm\",\"pid\":"
                            + ProcessHandle.current().pid() + ",\"mode\":\"cli-session\"}\n");
            Path stepFile = temp.resolve("step.txt");
            Files.writeString(stepFile, "Then the \"Save\" button is visible\n");
            ByteArrayOutputStream stepOut = new ByteArrayOutputStream();
            ByteArrayOutputStream stepErr = new ByteArrayOutputStream();
            int step = WorkbenchSessionCommands.run(
                    new String[]{"execute-step", "--text-file=" + stepFile, temp.toString()},
                    new PrintStream(stepOut, true, StandardCharsets.UTF_8),
                    new PrintStream(stepErr, true, StandardCharsets.UTF_8)
            );
            String stepText = stepOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, step, stepText + stepErr);
            assertTrue(stepText.contains("elapsedMs"), stepText);
            String log = Files.readString(temp.resolve(".pickleball/logs/step-timing.jsonl"));
            assertTrue(log.contains("\"id\":\"c1\""), log);
            assertTrue(log.contains("\"elapsedMs\""), log);
            assertTrue(log.contains("\"at\":"), log);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void liveHostPageVerbCarriesElapsedMs() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("save.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><body>
                <button id="save" data-pkb-handle="save">Save</button>
                </body></html>
                """);
        ByteArrayOutputStream startOut = new ByteArrayOutputStream();
        ByteArrayOutputStream startErr = new ByteArrayOutputStream();
        int started = WorkbenchAgentCommands.run(
                new String[]{"start", "--url=" + page.toUri(), "--browser=chrome", temp.toString()},
                new PrintStream(startOut, true, StandardCharsets.UTF_8),
                new PrintStream(startErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, started, startOut.toString(StandardCharsets.UTF_8) + startErr.toString(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"highlight", "--handle=save", "--preset=yellow", temp.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out + stderr);
        assertTrue(out.contains("\"elapsedMs\""), out);
        assertTrue(out.contains("\"timing\""), out);
        assertTrue(out.contains("\"totalMs\""), out);
        assertTrue(out.contains("\"jvmMs\""), out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void realChromeWarmVerbPrintsElapsed() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("warm.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><body>
                <button id="save" data-pkb-handle="save">Save</button>
                </body></html>
                """);
        ByteArrayOutputStream startOut = new ByteArrayOutputStream();
        ByteArrayOutputStream startErr = new ByteArrayOutputStream();
        int started = WorkbenchAgentCommands.run(
                new String[]{"start", "--url=" + page.toUri(), "--browser=chrome-headless", temp.toString()},
                new PrintStream(startOut, true, StandardCharsets.UTF_8),
                new PrintStream(startErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, started, startOut.toString(StandardCharsets.UTF_8) + startErr.toString(StandardCharsets.UTF_8));
        long[] samples = new long[3];
        long[] verbs = new long[3];
        for (int sample = 0; sample < samples.length; sample++) {
            long began = System.nanoTime();
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "tools.dscode.launcher.PickleballWorkbenchLauncher",
                    "highlight",
                    temp.toString(),
                    "--handle=save",
                    "--preset=yellow"
            ).redirectErrorStream(true).directory(workDir()).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = process.waitFor();
            samples[sample] = Math.max(0L, (System.nanoTime() - began) / 1_000_000L);
            assertEquals(0, code, output);
            assertTrue(output.contains("elapsedMs"), output);
            JsonNode body = JSON.readTree(output);
            assertTrue(body.path("ok").asBoolean(), output);
            assertEquals("style_modified", body.path("codes").path(0).asText(""), output);
            verbs[sample] = body.path("elapsedMs").asLong(Long.MAX_VALUE);
        }
        long realWarmMs = Math.min(samples[0], Math.min(samples[1], samples[2]));
        long verbMs = Math.min(verbs[0], Math.min(verbs[1], verbs[2]));
        System.out.println("MEASURE realWarmMs=" + samples[0] + " " + samples[1] + " " + samples[2]
                + " min=" + realWarmMs + " elapsedMs=" + verbs[0] + " " + verbs[1] + " " + verbs[2] + " min=" + verbMs);
        // Process wall is JVM spawn plus the verb. GitHub macOS run 44683079221 measured
        // 2028, 2205, and 1514 ms, so a 1500 ms wall fails a fast verb on a slow spawn.
        // 4000 ms only rejects a stuck spawn. The 1 second target is elapsedMs.
        assertTrue(realWarmMs <= 4000, "realWarmMs=" + samples[0] + "," + samples[1] + "," + samples[2]);
        assertTrue(verbMs <= 1000, "elapsedMs=" + verbs[0] + "," + verbs[1] + "," + verbs[2]);
        String paint = eval(
                "document.getElementById('save').getAttribute('data-pkb-style')"
                        + "+'|'+getComputedStyle(document.getElementById('save')).backgroundColor");
        assertEquals("style_modified|rgb(255, 243, 160)", paint);
    }

    private static java.io.File workDir() {
        return Path.of(System.getProperty("java.io.tmpdir", ".")).toFile();
    }

    private static long elapsedOf(String output) {
        int at = output.indexOf("\"elapsedMs\"");
        assertTrue(at >= 0, output);
        int end = output.indexOf(':', at) + 1;
        while (end < output.length() && Character.isWhitespace(output.charAt(end))) end++;
        int start = end;
        while (end < output.length() && Character.isDigit(output.charAt(end))) end++;
        assertTrue(end > start, output);
        return Long.parseLong(output.substring(start, end));
    }

    private String eval(String expression) throws Exception {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"eval", "--target=browser:auto", "--expr=" + expression, "--world=main", temp.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out + stderr.toString(StandardCharsets.UTF_8));
        return JSON.readTree(out).path("value").asText("");
    }
}
