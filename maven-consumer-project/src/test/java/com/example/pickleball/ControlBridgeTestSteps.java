package com.example.pickleball;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import tools.dscode.control.bridge.ControlBridgeBootstrap;
import tools.dscode.control.bridge.ControlBridgeCallResult;
import tools.dscode.control.bridge.ControlBridgeDescriptor;
import tools.dscode.control.bridge.ControlBridgeEvent;
import tools.dscode.control.bridge.ControlBridgeEventPage;
import tools.dscode.control.bridge.ControlBridgeScenarioStatus;
import tools.dscode.control.bridge.ControlBridgeStatus;
import tools.dscode.control.bridge.ControlBridgeValueResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;

public final class ControlBridgeTestSteps {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Path sessionDirectory;
    private String token;
    private ControlBridgeDescriptor descriptor;
    private CompletableFuture<ClientOutcome> client;

    @Given("^BEGIN CONTROL BRIDGE IPC TEST$")
    public void beginControlBridgeIpcTest() throws Exception {
        ControlApiTestSteps.reset();
        sessionDirectory = Files.createTempDirectory("pkb-control-bridge-");
        token = "test-" + UUID.randomUUID();
        descriptor = ControlBridgeBootstrap.start(
                sessionDirectory,
                UUID.randomUUID().toString(),
                token,
                false
        );

        client = CompletableFuture.supplyAsync(() -> {
            try {
                HttpResponse<byte[]> unauthorized = send(
                        "GET",
                        "/v1/status",
                        null,
                        null
                );

                ControlBridgeScenarioStatus scenario = awaitScenario();
                ControlBridgeCallResult wrongTarget = post(
                        "/v1/pause",
                        Map.of(
                                "scenarioId", UUID.randomUUID().toString(),
                                "waitSeconds", 1,
                                "leaseSeconds", 30
                        )
                );
                ControlBridgeCallResult paused = post(
                        "/v1/pause",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "waitSeconds", 10,
                                "leaseSeconds", 30
                        )
                );
                ControlBridgeEventPage events = events(
                        scenario.scenarioId(),
                        0L,
                        100
                );
                ControlBridgeEventPage cursorEvents = events(
                        scenario.scenarioId(),
                        events.nextSequence(),
                        100
                );
                ControlBridgeValueResult written = postValue(
                        "/v1/mappings/put",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "mapReference", "OVERRIDE",
                                "key", "controlBridgeIpcValue",
                                "value", "bridge-value",
                                "timeoutSeconds", 10
                        )
                );
                ControlBridgeValueResult read = postValue(
                        "/v1/mappings/get",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "mapReference", "OVERRIDE",
                                "key", "controlBridgeIpcValue",
                                "timeoutSeconds", 10
                        )
                );
                ControlBridgeValueResult resolved = postValue(
                        "/v1/mappings/resolve",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "input", "<controlBridgeIpcValue>",
                                "timeoutSeconds", 10
                        )
                );
                ControlBridgeCallResult failed = post(
                        "/v1/steps/execute",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "text", ", verify \"left\" equals \"right\"",
                                "argument", "",
                                "timeoutSeconds", 10
                        )
                );
                ControlBridgeCallResult succeeded = post(
                        "/v1/steps/execute",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "text", "CONTROL API TEST STEP",
                                "argument", "",
                                "timeoutSeconds", 10
                        )
                );
                ControlBridgeCallResult resumed = post(
                        "/v1/resume",
                        Map.of("scenarioId", scenario.scenarioId())
                );

                return new ClientOutcome(
                        unauthorized.statusCode(),
                        scenario,
                        wrongTarget,
                        paused,
                        events,
                        cursorEvents,
                        written,
                        read,
                        resolved,
                        failed,
                        succeeded,
                        resumed
                );
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @Given("^CONTROL BRIDGE IPC SYNC POINT$")
    public void controlBridgeIpcSyncPoint() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();

        while (System.nanoTime() < deadline) {
            if (client.isDone()) {
                return;
            }

            ControlBridgeStatus runtime = status();
            if (runtime.paused() || runtime.pauseRequested()) {
                return;
            }

            Thread.sleep(25);
        }

        throw new AssertionError(
                "Control bridge pause request was not registered before the IPC sync point completed"
        );
    }

    @Given("^VERIFY CONTROL BRIDGE IPC TEST$")
    public void verifyControlBridgeIpcTest() throws Exception {
        ClientOutcome outcome = client.get(20, TimeUnit.SECONDS);

        assertEquals(401, outcome.unauthorizedStatus(), "wrong/missing token status");
        assertEquals(
                getCurrentScenarioState().id.toString(),
                outcome.scenario().scenarioId(),
                "targeted scenario id"
        );
        assertEquals("UNAVAILABLE", outcome.wrongTarget().status(), "wrong scenario target");
        assertEquals("SUCCESS", outcome.paused().status(), "pause result");
        assertTrue(outcome.paused().runtime().paused(), "runtime should report paused");

        assertTrue(!outcome.events().events().isEmpty(), "semantic event history should not be empty");
        assertTrue(!outcome.events().gap(), "initial semantic event history should have no gap");
        assertTrue(
                outcome.events().events().stream()
                        .allMatch(event -> outcome.scenario().scenarioId().equals(event.scenarioId())),
                "scenario-filtered event history"
        );
        assertIncreasing(outcome.events().events());
        assertTrue(
                outcome.cursorEvents().events().isEmpty(),
                "cursor read while paused should not repeat retained events"
        );
        assertTrue(
                outcome.cursorEvents().nextSequence() >= outcome.events().nextSequence(),
                "event cursor should not move backwards"
        );

        assertMappingValue(outcome.written(), "bridge-value", "mapping write");
        assertMappingValue(outcome.read(), "bridge-value", "mapping read");
        assertMappingValue(outcome.resolved(), "bridge-value", "mapping resolve");
        assertEquals("FAILED", outcome.failed().status(), "retry-friendly failing step");
        assertEquals("SUCCESS", outcome.succeeded().status(), "successful retry step");
        assertEquals("SUCCESS", outcome.resumed().status(), "resume result");
        assertEquals(1, ControlApiTestSteps.invocationCount(), "detached successful invocation count");
        assertTrue(!getCurrentScenarioState().isScenarioFailed(), "detached failure must not fail scenario");
    }

    @After("@control-bridge")
    public void cleanupControlBridge() {
        try {
            if (descriptor != null && token != null) {
                post("/v1/resume", Map.of());
            }
        } catch (Exception ignored) {
        } finally {
            ControlBridgeBootstrap.stop();
            if (sessionDirectory != null) {
                try {
                    Files.deleteIfExists(sessionDirectory);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private ControlBridgeScenarioStatus awaitScenario() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            List<ControlBridgeScenarioStatus> active = scenarios();
            if (!active.isEmpty()) {
                return active.getFirst();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Control bridge did not publish an active scenario");
    }

    private List<ControlBridgeScenarioStatus> scenarios() throws Exception {
        HttpResponse<byte[]> response = send("GET", "/v1/scenarios", null, token);
        assertEquals(200, response.statusCode(), "/v1/scenarios HTTP status");
        return List.of(json.readValue(response.body(), ControlBridgeScenarioStatus[].class));
    }

    private ControlBridgeEventPage events(
            String scenarioId,
            long afterSequence,
            int limit
    ) throws Exception {
        String path = "/v1/events?scenarioId="
                + URLEncoder.encode(scenarioId, StandardCharsets.UTF_8)
                + "&afterSequence=" + afterSequence
                + "&limit=" + limit;
        HttpResponse<byte[]> response = send("GET", path, null, token);
        assertEquals(200, response.statusCode(), "/v1/events HTTP status");
        return json.readValue(response.body(), ControlBridgeEventPage.class);
    }

    private ControlBridgeCallResult post(String path, Object body) throws Exception {
        HttpResponse<byte[]> response = send("POST", path, body, token);
        assertEquals(200, response.statusCode(), path + " HTTP status");
        return json.readValue(response.body(), ControlBridgeCallResult.class);
    }

    private ControlBridgeValueResult postValue(String path, Object body) throws Exception {
        HttpResponse<byte[]> response = send("POST", path, body, token);
        assertEquals(200, response.statusCode(), path + " HTTP status");
        return json.readValue(response.body(), ControlBridgeValueResult.class);
    }

    private ControlBridgeStatus status() throws Exception {
        HttpResponse<byte[]> response = send("GET", "/v1/status", null, token);
        assertEquals(200, response.statusCode(), "/v1/status HTTP status");
        return json.readValue(response.body(), ControlBridgeStatus.class);
    }

    private HttpResponse<byte[]> send(
            String method,
            String path,
            Object body,
            String bearerToken
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }

        if ("GET".equals(method)) {
            request.GET();
        } else {
            byte[] bytes = body == null ? new byte[0] : json.writeValueAsBytes(body);
            request.header("Content-Type", "application/json");
            request.POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create(
                "http://" + descriptor.host() + ":" + descriptor.port() + path
        );
    }

    private static void assertIncreasing(List<ControlBridgeEvent> events) {
        long previous = 0;
        for (ControlBridgeEvent event : events) {
            assertTrue(event.sequence() > previous, "event sequence should increase");
            previous = event.sequence();
        }
    }

    private static void assertMappingValue(
            ControlBridgeValueResult result,
            Object expected,
            String label
    ) {
        assertEquals("SUCCESS", result.status(), label + " status");
        assertTrue(result.value().jsonCompatible(), label + " JSON compatibility");
        assertEquals(expected, result.value().jsonValue(), label + " value");
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + " but was " + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private record ClientOutcome(
            int unauthorizedStatus,
            ControlBridgeScenarioStatus scenario,
            ControlBridgeCallResult wrongTarget,
            ControlBridgeCallResult paused,
            ControlBridgeEventPage events,
            ControlBridgeEventPage cursorEvents,
            ControlBridgeValueResult written,
            ControlBridgeValueResult read,
            ControlBridgeValueResult resolved,
            ControlBridgeCallResult failed,
            ControlBridgeCallResult succeeded,
            ControlBridgeCallResult resumed
    ) {
    }
}
