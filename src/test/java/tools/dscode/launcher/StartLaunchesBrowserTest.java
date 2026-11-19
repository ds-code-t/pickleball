package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3: start opens Chrome and waits until the host health check is ok. */
class StartLaunchesBrowserTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    @AfterEach
    void stopLiveBrowser() {
        ConsumerReadyCommands.stopQuiet(temp);
    }

    @Test
    void startOpensHeadlessChromeAndDoctorReportsIt() throws Exception {
        Capture started = run("start", "--url=about:blank", "--browser=chrome", temp.toString());
        assertEquals(0, started.code, started.err + started.out);
        assertTrue(started.out.contains("live=true"), started.out);
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        assertTrue(started.out.contains(headless ? "headed=false" : "headed=true"), started.out);
        assertTrue(started.out.contains("browser=chrome"), started.out);
        Path cli = temp.resolve(".pickleball/live-browser/current.json");
        assertTrue(Files.isRegularFile(cli), cli.toString());
        assertFalse(Files.isRegularFile(temp.resolve(".pickleball/workbench/cli-session.json")));
        JsonNode node = JSON.readTree(cli.toFile());
        long pid = node.path("pid").asLong(0);
        long chromePid = node.path("chromePid").asLong(0);
        int port = node.path("url").asText("").replaceAll(".*:", "").isEmpty()
                ? 0
                : Integer.parseInt(node.path("url").asText("").replaceAll(".*:", ""));
        assertTrue(pid > 0, "host pid");
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "host pid alive");
        assertTrue(chromePid > 0, "chrome pid");
        assertTrue(port > 0 && port != 8765, "port " + port);
        String token = node.path("token").asText("");
        assertFalse(token.isBlank());
        assertFalse(started.out.contains(token), "token must stay off stdout");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(2))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"status\":\"ok\""), response.body());
        Capture doctor = run("doctor", temp.toString());
        assertEquals(0, doctor.code, doctor.err);
        assertTrue(doctor.out.contains("chrome=present"), doctor.out);
        assertTrue(doctor.out.contains("health=ok"), doctor.out);
        assertTrue(doctor.out.contains(headless ? "headed=false" : "headed=true"), doctor.out);
        assertTrue(doctor.out.contains("bridge=present"), doctor.out);
        assertTrue(doctor.out.contains("worker=absent"), doctor.out);
        assertTrue(doctor.out.contains("page=attached"), doctor.out);
        assertTrue(started.out.contains("navigated=true"), started.out);
        assertFalse(doctor.out.contains(token), "token must stay off doctor stdout");
    }

    @Test
    void liveStartIsTheStartAlias() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(new String[] {
                "live-start", "--url=about:blank", "--browser=chrome", temp.toString()
        });
        assertEquals("start", parsed.command());
        assertTrue(WorkbenchCommandLine.aliasNotices().contains("notice: live-start is start"));
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
