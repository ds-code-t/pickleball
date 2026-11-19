package tools.dscode.launcher;

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
import tools.dscode.control.protocol.LiveHostFile;
import tools.dscode.control.protocol.speed.ProcessTree;

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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3: start's host is a page the forwarded verbs can drive, and live-stop
 * leaves a Workbench worker alone.
 */
class StartDrivesPageTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void startThenPanelHighlightScreenshotAndObserve() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("save.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><body>
                <button id="save" data-pkb-handle="save">Save</button>
                </body></html>
                """);
        String url = page.toUri().toString();
        Capture started = run("start", "--url=" + url, "--browser=chrome", temp.toString());
        assertEquals(0, started.code, detail(started));
        assertTrue(started.out.contains("navigated=true"), detail(started));
        assertTrue(started.out.contains("live=true"), started.out);
        Path current = temp.resolve(".pickleball/live-browser/current.json");
        assertTrue(Files.isRegularFile(current), current.toString());
        assertFalse(Files.isRegularFile(temp.resolve(".pickleball/workbench/cli-session.json")));
        JsonNode node = JSON.readTree(current.toFile());
        String sessionId = node.path("sessionId").asText("");
        assertFalse(sessionId.isBlank());
        assertFalse(Files.exists(temp.resolve(".pickleball/live-browser").resolve(sessionId).resolve("args.txt")),
                "args.txt must be deleted after the host reads it");
        String token = node.path("token").asText("");
        assertFalse(started.out.contains(token));

        Capture panel = run("panel-open", "--target=page", temp.toString());
        assertEquals(0, panel.code, detail(panel));
        assertTrue(panel.out.contains("\"op\":\"panel-open\""), panel.out);
        assertTrue(panel.out.contains("\"target\":\"page\""), panel.out);
        assertTrue(panel.out.contains("\"panel\""), panel.out);

        Capture highlight = run("highlight", "--handle=save", "--preset=yellow", temp.toString());
        assertEquals(0, highlight.code, detail(highlight));
        assertTrue(highlight.out.contains("\"handle\":\"save\""), highlight.out);

        Path shot = temp.resolve("shot.png");
        Capture screenshot = run("screenshot", "--out=" + shot, temp.toString());
        assertEquals(0, screenshot.code, detail(screenshot));
        byte[] png = Files.readAllBytes(shot);
        assertTrue(png.length > 8, "png bytes " + png.length);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
        assertEquals((byte) 0x4E, png[2]);
        assertEquals((byte) 0x47, png[3]);

        Capture observe = run("observe", temp.toString());
        assertEquals(0, observe.code, detail(observe));
        assertTrue(observe.out.contains("\"source\":\"cdp-session\""), observe.out);
        assertFalse(observe.out.contains("no engine CDP Session"), observe.out);

        long chromePid = node.path("chromePid").asLong(0);
        int port = node.path("port").asInt(0);
        ProcessTree.kill(chromePid);
        String health = waitForDown(port, token);
        assertTrue(health.contains("\"status\":\"down\""), health);
        assertTrue(health.contains("browser_lost"), health);
        String again = healthBody(port, token);
        String events = Files.readString(temp.resolve(".pickleball/agent-events.jsonl"));
        int lost = 0;
        for (String line : events.split("\n")) {
            if (line.contains("\"event\":\"browser_lost\"")) lost++;
        }
        assertEquals(1, lost, events + "\nsecond=" + again);
        Capture doctor = run("doctor", temp.toString());
        assertEquals(0, doctor.code, doctor.err);
        assertTrue(doctor.out.contains("health=down"), doctor.out);
        assertTrue(doctor.out.contains("page=absent"), doctor.out);
        Capture stopped = run("live-stop", temp.toString());
        assertEquals(0, stopped.code, stopped.err + stopped.out);
        assertTrue(stopped.out.contains("stopped=true"), stopped.out);
        assertFalse(Files.isRegularFile(current));
    }

    @Test
    void startRefusesALiveWorkerAndForwardsThatSession() throws Exception {
        Process worker = new ProcessBuilder("sleep", "180").redirectErrorStream(true).start();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> posted = new AtomicReference<>("");
        server.createContext("/v1/agent/command", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"exit\":0,\"body\":{\"ok\":true}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            LiveBridgeFile.write(temp, new LiveBridgeFile.Bridge(
                    "127.0.0.1", port, "worker-token", worker.pid(), "worker-session"
            ));
            Capture started = run("start", "--url=about:blank", "--browser=chrome", temp.toString());
            assertEquals(2, started.code, detail(started));
            assertEquals(
                    "error: a Workbench worker is live\nfix: use the worker's browser or stop it\n",
                    started.err
            );
            assertTrue(worker.isAlive(), "start killed the workbench worker");
            assertFalse(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
            assertFalse(Files.isRegularFile(temp.resolve(".pickleball/consumer-session.json")));
            assertTrue(Files.isRegularFile(temp.resolve(".pickleball/workbench/live-bridge.json")));

            Capture highlight = run("highlight", "--handle=save", "--preset=yellow", temp.toString());
            assertEquals(0, highlight.code, detail(highlight));
            assertTrue(posted.get().contains("\"runId\":\"worker-session\""), posted.get());

            Capture stopped = run("live-stop", temp.toString());
            assertEquals(0, stopped.code, stopped.err + stopped.out);
            assertTrue(stopped.out.contains("stopped=false"), stopped.out);
            assertFalse(stopped.err.contains("notice: live-stop stops the start host"), stopped.err);
            assertTrue(worker.isAlive(), "live-stop killed the workbench worker");
            assertTrue(Files.isRegularFile(temp.resolve(".pickleball/workbench/live-bridge.json")));
        } finally {
            server.stop(0);
            worker.destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void doctorAfterChromeDiesAppendsBrowserLost() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Capture started = run("start", "--url=about:blank", "--browser=chrome", temp.toString());
        assertEquals(0, started.code, detail(started));
        JsonNode node = JSON.readTree(temp.resolve(".pickleball/live-browser/current.json").toFile());
        ProcessTree.kill(node.path("chromePid").asLong(0));
        Capture doctor = run("doctor", temp.toString());
        assertEquals(0, doctor.code, doctor.err + doctor.out);
        assertTrue(doctor.out.contains("health=down"), doctor.out);
        assertTrue(doctor.out.contains("page=absent"), doctor.out);
        String events = Files.readString(temp.resolve(".pickleball/agent-events.jsonl"));
        assertEquals(1, countEvent(events, "browser_lost"), events);
        Capture again = run("doctor", temp.toString());
        assertEquals(0, again.code, again.err);
        String twice = Files.readString(temp.resolve(".pickleball/agent-events.jsonl"));
        assertEquals(1, countEvent(twice, "browser_lost"), twice);
        Capture border = run("border", "--handle=save", temp.toString());
        assertTrue(border.code != 0, border.out + border.err);
        assertTrue((border.out + border.err).contains("no live browser"), border.out + border.err);
        assertFalse((border.out + border.err).contains("unknown handle"), border.out + border.err);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void selftestCpuLeavesTheHostPage() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Path page = temp.resolve("save.html");
        Files.writeString(page, """
                <!DOCTYPE html><html><body>
                <button id="save" data-pkb-handle="save">Save</button>
                </body></html>
                """);
        Capture started = run("start", "--url=" + page.toUri(), "--browser=chrome", temp.toString());
        assertEquals(0, started.code, detail(started));
        Capture selftest = run("selftest", temp.toString(), "ui", "--cpu");
        assertFalse(Files.exists(temp.resolve(".pickleball/selftest-profile")), selftest.out + selftest.err);
        Capture panel = run("panel-open", "--target=page", temp.toString());
        assertEquals(0, panel.code, detail(panel) + selftest.out + selftest.err);
        assertTrue(panel.out.contains("\"target\":\"page\""), panel.out);
        int before = placedOnHost();
        Capture border = run("border", "--handle=save", temp.toString());
        assertEquals(0, border.code, detail(border));
        assertFalse(border.out.contains("\"target\":\"popup\""), border.out);
        assertEquals(before + 1, placedOnHost(), border.out);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void chatPollAfterChromeDiesReturnsBrowserLostOnce() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Capture started = run("start", "--url=about:blank", "--browser=chrome-headless", temp.toString());
        assertEquals(0, started.code, detail(started));
        JsonNode node = JSON.readTree(temp.resolve(".pickleball/live-browser/current.json").toFile());
        ProcessTree.kill(node.path("chromePid").asLong(0));
        Capture poll = run("chat-poll", temp.toString());
        assertEquals(0, poll.code, poll.out + poll.err);
        assertFalse((poll.out + poll.err).contains("no live browser"), poll.out + poll.err);
        assertEquals(1, countAgentEvent(poll.out, "browser_lost"), poll.out);
        Capture again = run("chat-poll", temp.toString());
        assertEquals(0, again.code, again.out + again.err);
        assertEquals(0, countAgentEvent(again.out, "browser_lost"), again.out);
        String events = Files.readString(temp.resolve(".pickleball/agent-events.jsonl"));
        assertEquals(1, countEvent(events, "browser_lost"), events);
        Capture border = run("border", "--handle=save", temp.toString());
        assertTrue(border.code != 0, border.out + border.err);
        assertTrue((border.out + border.err).contains("no live browser"), border.out + border.err);
        assertFalse((border.out + border.err).contains("unknown handle"), border.out + border.err);
        run("live-stop", temp.toString());
        lostAfterChromeDies("ui-poll");
        run("live-stop", temp.toString());
        lostAfterChromeDies("ui-catalog");
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void liveStopKillsTheHostAndChrome() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Capture started = run("start", "--url=about:blank", "--browser=chrome-headless", temp.toString());
        assertEquals(0, started.code, detail(started));
        Path current = temp.resolve(".pickleball/live-browser/current.json");
        JsonNode node = JSON.readTree(current.toFile());
        long hostPid = node.path("pid").asLong(0);
        long chromePid = node.path("chromePid").asLong(0);
        String sessionId = node.path("sessionId").asText("");
        assertTrue(node.path("pidStarted").asLong(0) > 0, node.toString());
        assertTrue(node.path("chromeStarted").asLong(0) > 0, node.toString());
        assertTrue(alive(hostPid));
        assertTrue(alive(chromePid));
        Capture stopped = run("live-stop", temp.toString());
        assertEquals(0, stopped.code, stopped.err + stopped.out);
        assertTrue(stopped.out.contains("stopped=true"), stopped.out);
        assertTrue(stopped.err.contains("notice: live-stop stops the start host"), stopped.err);
        assertFalse(alive(hostPid), "host pid still alive");
        assertFalse(alive(chromePid), "chrome pid still alive");
        assertFalse(Files.isRegularFile(current));
        assertFalse(Files.exists(temp.resolve(".pickleball/live-browser").resolve(sessionId)), sessionId);
    }

    @Test
    void liveStopLeavesAProcessWhoseStartTimeDoesNotMatch() throws Exception {
        Process host = new ProcessBuilder("sleep", "180").redirectErrorStream(true).start();
        Process chrome = new ProcessBuilder("sleep", "180").redirectErrorStream(true).start();
        try {
            LiveHostFile.write(temp, new LiveHostFile.Host(
                    "http://127.0.0.1:9", "token", 9, host.pid(), chrome.pid(), "sess",
                    false, "chrome", true, "", false, 1L, 1L
            ));
            Capture stopped = run("live-stop", temp.toString());
            assertEquals(0, stopped.code, stopped.err + stopped.out);
            assertTrue(stopped.out.contains("stopped=false"), stopped.out);
            assertFalse(stopped.err.contains("notice: live-stop stops the start host"), stopped.err);
            assertTrue(host.isAlive(), "live-stop killed an unrelated host pid");
            assertTrue(chrome.isAlive(), "live-stop killed an unrelated chrome pid");
            assertFalse(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        } finally {
            host.destroyForcibly();
            chrome.destroyForcibly();
        }
    }

    @Test
    void liveStopDoesNotKillPidsWithNoRecordedStartTimes() throws Exception {
        Process host = new ProcessBuilder("sleep", "180").redirectErrorStream(true).start();
        Process chrome = new ProcessBuilder("sleep", "180").redirectErrorStream(true).start();
        try {
            LiveHostFile.write(temp, new LiveHostFile.Host(
                    "http://127.0.0.1:9", "token", 9, host.pid(), chrome.pid(), "sess",
                    false, "chrome", true, "", false, 0L, 0L
            ));
            Capture stopped = run("live-stop", temp.toString());
            assertEquals(0, stopped.code, stopped.err + stopped.out);
            assertTrue(stopped.out.contains("stopped=false"), stopped.out);
            assertTrue(stopped.err.contains("notice: live-stop deleted a state file with no recorded start times"), stopped.err);
            assertFalse(stopped.err.contains("notice: live-stop stops the start host"), stopped.err);
            assertTrue(host.isAlive(), "live-stop killed a pid with no recorded start time");
            assertTrue(chrome.isAlive(), "live-stop killed a chrome pid with no recorded start time");
            assertFalse(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        } finally {
            host.destroyForcibly();
            chrome.destroyForcibly();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void chromeHeadlessStaysHeadless() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: chrome is not on PATH");
        Capture started = run("start", "--url=about:blank", "--browser=chrome-headless", temp.toString());
        assertEquals(0, started.code, detail(started));
        assertTrue(started.out.contains("headed=false"), started.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertTrue(started.out.contains("browser=chrome-headless"), started.out);
    }

    @Test
    void firefoxAndMissingEdgeDoNotLaunchChrome() throws Exception {
        Capture firefox = run("start", "--url=about:blank", "--browser=firefox", temp.toString());
        assertEquals(2, firefox.code, firefox.err);
        assertTrue(firefox.err.contains("error: unsupported --browser=firefox"), firefox.err);
        assertFalse(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        if (BrowserSession.findEdge() == null) {
            Capture edge = run("start", "--url=about:blank", "--browser=edge", temp.toString());
            assertEquals(2, edge.code, edge.err);
            assertTrue(edge.err.contains("error: unsupported --browser=edge"), edge.err);
            assertFalse(Files.isRegularFile(temp.resolve(".pickleball/live-browser/current.json")));
        }
    }

    @Test
    void liveStopWithNoHostPrintsStopped() {
        Capture stopped = run("live-stop", temp.toString());
        assertEquals(0, stopped.code, stopped.err + stopped.out);
        assertTrue(stopped.out.contains("stopped=false"), stopped.out);
        assertFalse(stopped.err.contains("notice: live-stop stops the start host"), stopped.err);
    }

    private void lostAfterChromeDies(String command) throws Exception {
        Capture started = run("start", "--url=about:blank", "--browser=chrome-headless", temp.toString());
        assertEquals(0, started.code, detail(started));
        JsonNode node = JSON.readTree(temp.resolve(".pickleball/live-browser/current.json").toFile());
        ProcessTree.kill(node.path("chromePid").asLong(0));
        Capture poll = run(command, temp.toString());
        assertEquals(0, poll.code, poll.out + poll.err);
        assertFalse((poll.out + poll.err).contains("cdp connection is closed"), poll.out + poll.err);
        assertFalse((poll.out + poll.err).contains("no live browser"), poll.out + poll.err);
        assertEquals(1, countAgentEvent(poll.out, "browser_lost"), poll.out);
        Capture again = run(command, temp.toString());
        assertEquals(0, again.code, again.out + again.err);
        assertEquals(0, countAgentEvent(again.out, "browser_lost"), again.out);
    }

    private int placedOnHost() throws Exception {
        String expr = "(function(){function n(r){var c=r.querySelectorAll('[data-pkb-id]').length;"
                + "r.querySelectorAll('*').forEach(function(e){if(e.shadowRoot)c+=n(e.shadowRoot)});return c}"
                + "return n(document)})()";
        Capture counted = run(
                "eval", "--target=browser:auto", "--world=main", "--expr=" + expr, temp.toString());
        assertEquals(0, counted.code, detail(counted));
        return Integer.parseInt(JSON.readTree(counted.out).path("value").asText("").trim());
    }

    private static int countAgentEvent(String body, String name) throws Exception {
        int count = 0;
        JsonNode events = JSON.readTree(body).path("agentEvents");
        if (!events.isArray()) return 0;
        for (JsonNode event : events) {
            if (name.equals(event.path("event").asText(""))) count++;
        }
        return count;
    }

    private static int countEvent(String events, String name) {
        int count = 0;
        for (String line : events.split("\n")) {
            if (line.contains("\"event\":\"" + name + "\"")) count++;
        }
        return count;
    }

    private String waitForDown(int port, String token) throws Exception {
        String body = "";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            body = healthBody(port, token);
            if (body.contains("\"status\":\"down\"")) return body;
            Thread.sleep(200L);
        }
        return body;
    }

    private String healthBody(int port, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static boolean alive(long pid) {
        return pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private String detail(Capture capture) {
        String log = "";
        try {
            Path file = temp.resolve(".pickleball/logs/live-browser.log");
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file);
                log = text.substring(Math.max(0, text.length() - 1600));
            }
        } catch (java.io.IOException ignored) {
            log = "";
        }
        return capture.err + "\n" + capture.out + "\n" + log;
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
