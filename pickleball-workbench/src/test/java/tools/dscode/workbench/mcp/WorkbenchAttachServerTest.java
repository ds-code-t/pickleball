package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.WorkbenchController;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;
import tools.dscode.workbench.player.LiveScenarioPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchAttachServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void writesLocalhostAttachStateAndRequiresTheSessionToken() throws Exception {
        try (WorkbenchController controller = new WorkbenchController(project);
             WorkbenchAttachServer server = WorkbenchAttachServer.start(controller, project)) {
            Path stateFile = WorkbenchAttachServer.attachStateFile(project);
            assertTrue(Files.isRegularFile(stateFile));
            JsonNode state = JSON.readTree(Files.readString(stateFile));
            assertEquals(server.url(), state.get("url").asText());
            assertEquals(server.token(), state.get("token").asText());
            assertTrue(server.url().startsWith("http://127.0.0.1:"));
            assertEquals("ui-attach", state.get("mode").asText());

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> denied = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/lease")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(401, denied.statusCode());

            HttpResponse<String> lease = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/lease"))
                            .header("Authorization", "Bearer " + server.token())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, lease.statusCode());
            assertTrue(lease.body().contains("\"holder\":\"HUMAN\""));

            HttpResponse<String> requested = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/tools/workbench_request_control"))
                            .header("Authorization", "Bearer " + server.token())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"agentName\":\"Copilot\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, requested.statusCode());
            assertTrue(requested.body().contains("\"holder\":\"AGENT\""));
            assertTrue(requested.body().contains("Copilot"));
        }
        assertTrue(Files.notExists(WorkbenchAttachServer.attachStateFile(project)));
    }

    @Test
    void mutatingToolWithoutLeaseFailsClearly() throws Exception {
        try (WorkbenchController controller = new WorkbenchController(project);
             WorkbenchAttachServer server = WorkbenchAttachServer.start(controller, project)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/tools/workbench_player_replace_document"))
                            .header("X-Workbench-Token", server.token())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"Given stay\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(400, response.statusCode());
            assertTrue(response.body().contains("workbench_request_control"));
        }
    }

    @Test
    void headlessStdioLeaseDoesNotRequireABanner() {
        try (WorkbenchController controller = new WorkbenchController(project)) {
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> controller.requestControl("stdio"));
            assertTrue(controller.controlLeaseSnapshot().agentHolds());
            org.junit.jupiter.api.Assertions.assertFalse(controller.controlLeaseSnapshot().uiAttached());
        }
    }

    @Test
    void cliSessionWritesDedicatedStateFileAndQueuesExecuteStep() throws Exception {
        try (WorkbenchController controller = new WorkbenchController(project);
             WorkbenchAttachServer server = WorkbenchAttachServer.startCliSession(controller, project)) {
            Path sessionFile = WorkbenchAttachServer.cliSessionStateFile(project);
            assertTrue(Files.isRegularFile(sessionFile));
            assertTrue(Files.notExists(WorkbenchAttachServer.attachStateFile(project)));
            JsonNode state = JSON.readTree(Files.readString(sessionFile));
            assertEquals(server.url(), state.get("url").asText());
            assertEquals("cli-session", state.get("mode").asText());
            assertEquals(server.token(), state.get("token").asText());

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> ack = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/commands"))
                            .header("Authorization", "Bearer " + server.token())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"op\":\"execute-step\",\"text\":\"Given stay\",\"id\":\"step-1\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, ack.statusCode());
            JsonNode body = JSON.readTree(ack.body());
            assertEquals(true, body.get("ack").asBoolean());
            assertEquals("step-1", body.get("id").asText());
            assertTrue("QUEUED".equals(body.get("status").asText())
                    || "RUNNING".equals(body.get("status").asText()));

            JsonNode done = null;
            for (int i = 0; i < 50; i++) {
                HttpResponse<String> status = client.send(
                        HttpRequest.newBuilder(URI.create(server.url() + "/commands/step-1"))
                                .header("X-Workbench-Token", server.token())
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertEquals(200, status.statusCode());
                done = JSON.readTree(status.body());
                String value = done.get("status").asText();
                if ("SUCCESS".equals(value) || "FAILED".equals(value) || "TIMEOUT".equals(value)) break;
                Thread.sleep(20);
            }
            assertEquals("step-1", done.get("id").asText());
            assertEquals("FAILED", done.get("status").asText());
        }
        assertTrue(Files.notExists(WorkbenchAttachServer.cliSessionStateFile(project)));
    }

    @Test
    void uiAttachAcceptsTheSameSessionCommandsAndStopLeavesTheEndpointUp() throws Exception {
        try (WorkbenchController controller = new WorkbenchController(project);
             WorkbenchAttachServer server = WorkbenchAttachServer.start(controller, project)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> unknown = post(client, server, "{\"op\":\"not-a-command\"}");
            assertEquals(400, unknown.statusCode());

            HttpResponse<String> pause = post(client, server, "{\"op\":\"pause\",\"id\":\"pause-1\"}");
            assertEquals(200, pause.statusCode());
            JsonNode ack = JSON.readTree(pause.body());
            assertEquals("pause-1", ack.get("id").asText());

            JsonNode done = await(client, server, "pause-1");
            assertEquals("SUCCESS", done.get("status").asText());

            HttpResponse<String> stop = post(client, server, "{\"op\":\"stop\"}");
            assertEquals(200, stop.statusCode());
            assertTrue(stop.body().contains("STOPPING"));
            HttpResponse<String> health = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, health.statusCode());
        }
    }

    @Test
    void playerAndWorkerVerbsRunOnTheRealCliSession() throws Exception {
        Path feature = project.resolve("src/test/resources/features/shop.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Shop
                  Scenario: Buy
                    When buy a
                """);
        try (WorkbenchController controller = new WorkbenchController(project);
             WorkbenchAttachServer server = WorkbenchAttachServer.startCliSession(controller, project)) {
            WorkbenchCallContext.runAs(WorkbenchLeaseHolder.AGENT, () -> controller.requestControl("real-session"));
            assertEquals("cli-session", JSON.readTree(Files.readString(server.stateFile())).get("mode").asText());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            JsonNode opened = command(client, server, "open-1",
                    "{\"op\":\"open-scenario\",\"id\":\"open-1\",\"feature\":\"shop.feature\",\"name\":\"Buy\"}");
            assertEquals("SUCCESS", opened.get("status").asText(), opened.toString());
            assertEquals("Buy", opened.get("result").get("scenario").asText());
            assertEquals("open-scenario", opened.get("result").get("op").asText());
            assertEquals("Buy", controller.playback().origin().scenarioName());
            assertTrue(controller.player().documentText().contains("When buy a"), controller.player().documentText());

            JsonNode played = command(client, server, "play-1", "{\"op\":\"play\",\"id\":\"play-1\"}");
            assertEquals("FAILED", played.get("status").asText(), played.toString());
            assertEquals("play", played.get("result").get("op").asText());
            assertTrue(played.get("result").get("message").asText().contains("paused interactive worker"), played.toString());
            assertEquals(LiveScenarioPlayer.State.RUNNING, controller.player().state());

            JsonNode paused = command(client, server, "pause-real", "{\"op\":\"pause\",\"id\":\"pause-real\"}");
            assertEquals("SUCCESS", paused.get("status").asText(), paused.toString());
            assertEquals("PAUSED", paused.get("result").get("playback").asText());
            assertEquals(LiveScenarioPlayer.State.PAUSED, controller.player().state());

            JsonNode saved = command(client, server, "save-1", "{\"op\":\"save\",\"id\":\"save-1\"}");
            assertEquals("SUCCESS", saved.get("status").asText(), saved.toString());
            assertEquals("WRITTEN", saved.get("result").get("save").asText());
            assertTrue(Files.readString(feature).contains("When buy a"));

            JsonNode refreshed = command(client, server, "refresh-1", "{\"op\":\"refresh\",\"id\":\"refresh-1\"}");
            assertEquals("SUCCESS", refreshed.get("status").asText(), refreshed.toString());
            assertEquals(false, refreshed.get("result").get("running").asBoolean());
            assertEquals("refresh", refreshed.get("result").get("op").asText());

            JsonNode stopped = command(client, server, "worker-stop-1", "{\"op\":\"worker-stop\",\"id\":\"worker-stop-1\"}");
            assertEquals("SUCCESS", stopped.get("status").asText(), stopped.toString());
            assertEquals("worker-stop", stopped.get("result").get("op").asText());
            assertEquals(false, stopped.get("result").get("running").asBoolean());
            assertEquals(LiveScenarioPlayer.State.STOPPED, controller.player().state());

            JsonNode started = command(client, server, "worker-start-1", "{\"op\":\"worker-start\",\"id\":\"worker-start-1\"}");
            assertEquals("FAILED", started.get("status").asText(), started.toString());
            assertTrue(started.get("result").get("message").asText().contains("Run sync first"), started.toString());

            JsonNode restarted = command(client, server, "worker-restart-1",
                    "{\"op\":\"worker-restart\",\"id\":\"worker-restart-1\"}");
            assertEquals("FAILED", restarted.get("status").asText(), restarted.toString());
            assertTrue(restarted.get("result").get("message").asText().contains("Run sync first"), restarted.toString());
        }
    }

    private static JsonNode command(HttpClient client, WorkbenchAttachServer server, String id, String body) throws Exception {
        HttpResponse<String> ack = post(client, server, body);
        assertEquals(200, ack.statusCode(), ack.body());
        assertEquals(id, JSON.readTree(ack.body()).get("id").asText());
        return await(client, server, id);
    }

    private static HttpResponse<String> post(HttpClient client, WorkbenchAttachServer server, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(server.url() + "/commands"))
                        .header("Authorization", "Bearer " + server.token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static JsonNode await(HttpClient client, WorkbenchAttachServer server, String id) throws Exception {
        JsonNode done = null;
        for (int i = 0; i < 50; i++) {
            HttpResponse<String> status = client.send(
                    HttpRequest.newBuilder(URI.create(server.url() + "/commands/" + id))
                            .header("X-Workbench-Token", server.token())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, status.statusCode());
            done = JSON.readTree(status.body());
            String value = done.get("status").asText();
            if ("SUCCESS".equals(value) || "FAILED".equals(value) || "TIMEOUT".equals(value)) return done;
            Thread.sleep(20);
        }
        return done;
    }
}
