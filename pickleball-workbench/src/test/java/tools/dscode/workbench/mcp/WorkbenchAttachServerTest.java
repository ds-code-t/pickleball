package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.WorkbenchController;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;

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
}
