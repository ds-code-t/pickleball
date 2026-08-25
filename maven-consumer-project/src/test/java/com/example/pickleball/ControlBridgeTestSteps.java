package com.example.pickleball;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import tools.dscode.control.bridge.ControlBridgeBootstrap;
import tools.dscode.control.protocol.*;
import tools.dscode.coredefinitions.BrowserSteps;

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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Path sessionDirectory;
    private String token;
    private ControlBridgeDescriptor descriptor;
    private CompletableFuture<ClientOutcome> client;

    @Given("^BEGIN CONTROL BRIDGE IPC TEST$")
    public void beginControlBridgeIpcTest() throws Exception {
        ControlApiTestSteps.reset();
        BrowserSteps.getCurrentDriver();
        sessionDirectory = Files.createTempDirectory("pkb-control-bridge-");
        token = "test-" + UUID.randomUUID();
        descriptor = ControlBridgeBootstrap.start(
                sessionDirectory, UUID.randomUUID().toString(), token, false
        );
        String expectedScenarioId = getCurrentScenarioState().id.toString();

        client = CompletableFuture.supplyAsync(() -> {
            try {
                int unauthorizedStatus = send("GET", "/v1/status", null, null).statusCode();
                ControlBridgeScenarioStatus scenario = awaitScenario(expectedScenarioId);
                ControlBridgeCallResult wrongTarget = post(
                        "/v1/pause",
                        Map.of("scenarioId", UUID.randomUUID().toString(), "waitSeconds", 1, "leaseSeconds", 30)
                );
                ControlBridgeCallResult paused = post(
                        "/v1/pause",
                        Map.of("scenarioId", scenario.scenarioId(), "waitSeconds", 10, "leaseSeconds", 30)
                );

                ControlBridgeBrowserPageResult browserPage = postTyped(
                        "/v1/browser/page",
                        Map.of("scenarioId", scenario.scenarioId(), "timeoutSeconds", 10),
                        ControlBridgeBrowserPageResult.class
                );
                ControlBridgeBrowserScreenshotResult browserScreenshot = postTyped(
                        "/v1/browser/screenshot",
                        Map.of("scenarioId", scenario.scenarioId(), "timeoutSeconds", 10),
                        ControlBridgeBrowserScreenshotResult.class
                );
                ControlBridgeElementInspectionResult elements = postTyped(
                        "/v1/browser/elements",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "category", "Button",
                                "operation", "DEFAULT",
                                "maxElements", 5,
                                "timeoutSeconds", 10
                        ),
                        ControlBridgeElementInspectionResult.class
                );
                ControlBridgeServiceCallResult failedServiceCall = postTyped(
                        "/v1/services/call",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "selector", "%phase3h-missing-service-call",
                                "timeoutSeconds", 10
                        ),
                        ControlBridgeServiceCallResult.class
                );
                ControlBridgeServiceCallResult serviceCall = postTyped(
                        "/v1/services/call",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "selector", "%health-full-url",
                                "timeoutSeconds", 10
                        ),
                        ControlBridgeServiceCallResult.class
                );

                ControlBridgeBreakpoint breakpoint = postTyped(
                        "/v1/breakpoints/add",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "hook", "AFTER_STEP",
                                "stepContains", "CONTROL BRIDGE IPC SYNC POINT",
                                "oneShot", true,
                                "leaseSeconds", 30
                        ),
                        ControlBridgeBreakpoint.class
                );
                List<ControlBridgeBreakpoint> breakpointsBefore = breakpoints();

                ControlBridgeValueResult baseline = postValue(
                        "/v1/mappings/put",
                        Map.of(
                                "scenarioId", scenario.scenarioId(), "mapReference", "OVERRIDE",
                                "key", "controlBridgeIpcValue", "value", "baseline", "timeoutSeconds", 10
                        )
                );
                ControlBridgeMappingSnapshotResult snapshot = postTyped(
                        "/v1/mappings/snapshot",
                        Map.of("scenarioId", scenario.scenarioId(), "mapReference", "OVERRIDE", "timeoutSeconds", 10),
                        ControlBridgeMappingSnapshotResult.class
                );
                ControlBridgeValueResult written = postValue(
                        "/v1/mappings/put",
                        Map.of(
                                "scenarioId", scenario.scenarioId(), "mapReference", "OVERRIDE",
                                "key", "controlBridgeIpcValue", "value", "bridge-value", "timeoutSeconds", 10
                        )
                );
                ControlBridgeValueResult read = postValue(
                        "/v1/mappings/get",
                        Map.of(
                                "scenarioId", scenario.scenarioId(), "mapReference", "OVERRIDE",
                                "key", "controlBridgeIpcValue", "timeoutSeconds", 10
                        )
                );
                ControlBridgeValueResult resolved = postValue(
                        "/v1/mappings/resolve",
                        Map.of("scenarioId", scenario.scenarioId(), "input", "<controlBridgeIpcValue>", "timeoutSeconds", 10)
                );
                ControlBridgeCallResult restored = post(
                        "/v1/mappings/restore",
                        Map.of("scenarioId", scenario.scenarioId(), "snapshot", snapshot.snapshot(), "timeoutSeconds", 10)
                );
                ControlBridgeValueResult restoredRead = postValue(
                        "/v1/mappings/get",
                        Map.of(
                                "scenarioId", scenario.scenarioId(), "mapReference", "OVERRIDE",
                                "key", "controlBridgeIpcValue", "timeoutSeconds", 10
                        )
                );
                ControlBridgeCallResult failed = post(
                        "/v1/steps/execute",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "text", ", verify \"left\" equals \"right\"", "argument", "", "timeoutSeconds", 10
                        )
                );
                ControlBridgeCallResult succeeded = post(
                        "/v1/steps/execute",
                        Map.of(
                                "scenarioId", scenario.scenarioId(),
                                "text", "CONTROL API TEST STEP", "argument", "", "timeoutSeconds", 10
                        )
                );

                // Capture the runtime-wide event sequence immediately before resuming.
                // The recorder is bounded across the whole runtime, so pre-existing
                // scenario events may legitimately be evicted during a large @all run.
                long eventCursor = events(scenario.scenarioId(), 0L, 1).latestSequence();

                ControlBridgeCallResult firstResume = post(
                        "/v1/resume", Map.of("scenarioId", scenario.scenarioId())
                );
                ControlBridgeScenarioStatus breakpointPause = awaitPaused(scenario.scenarioId());

                // The one-shot breakpoint pauses on AFTER_STEP for the sync marker.
                // Read from the pre-resume cursor while that scenario is still paused,
                // so this validates an event generated by the exact boundary under test.
                ControlBridgeEventPage events = events(scenario.scenarioId(), eventCursor, 100);
                ControlBridgeEventPage cursorEvents = events(
                        scenario.scenarioId(), events.nextSequence(), 100
                );
                List<ControlBridgeBreakpoint> breakpointsAfterHit = breakpoints();
                ControlBridgeCallResult secondResume = post(
                        "/v1/resume", Map.of("scenarioId", scenario.scenarioId())
                );

                return new ClientOutcome(
                        unauthorizedStatus, scenario, wrongTarget, paused,
                        browserPage, browserScreenshot, elements, failedServiceCall, serviceCall,
                        breakpoint, breakpointsBefore, breakpointPause, breakpointsAfterHit,
                        events, cursorEvents, baseline, snapshot, written, read, resolved,
                        restored, restoredRead, failed, succeeded, firstResume, secondResume
                );
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @Given("^CONTROL BRIDGE IPC SYNC POINT$")
    public void controlBridgeIpcSyncPoint() {
        // Marker step: the Phase 3H one-shot AFTER_STEP breakpoint pauses after this method returns.
    }

    @Given("^VERIFY CONTROL BRIDGE IPC TEST$")
    public void verifyControlBridgeIpcTest() throws Exception {
        ClientOutcome outcome = client.get(25, TimeUnit.SECONDS);
        assertEquals(
                ControlProtocol.CURRENT_VERSION,
                descriptor.protocolVersion(),
                "descriptor protocol version"
        );
        assertEquals(
                ControlProtocol.MINIMUM_COMPATIBLE_VERSION,
                descriptor.minimumCompatibleProtocolVersion(),
                "descriptor minimum protocol version"
        );
        assertEquals(ProcessHandle.current().pid(), descriptor.pid(), "consumer runtime PID");
        assertEquals("127.0.0.1", descriptor.host(), "loopback host");
        assertTrue(
                descriptor.runtimeCodeSource() != null
                        && !descriptor.runtimeCodeSource().isBlank()
                        && !"unknown".equals(descriptor.runtimeCodeSource()),
                "consumer runtime code source"
        );
        assertTrue(
                descriptor.capabilities().containsAll(ControlProtocol.WORKER_CAPABILITIES),
                "descriptor capabilities"
        );
        assertEquals(401, outcome.unauthorizedStatus(), "wrong/missing token status");
        assertEquals(getCurrentScenarioState().id.toString(), outcome.scenario().scenarioId(), "targeted scenario id");
        assertEquals("UNAVAILABLE", outcome.wrongTarget().status(), "wrong scenario target");
        assertEquals("SUCCESS", outcome.paused().status(), "pause result");
        assertTrue(outcome.paused().runtime().paused(), "runtime should report paused");

        assertEquals("SUCCESS", outcome.browserPage().status(), "browser page status");
        assertTrue(!outcome.browserPage().page().pageSource().isBlank(), "browser page DOM source");
        assertEquals("SUCCESS", outcome.browserScreenshot().status(), "browser screenshot status");
        assertEquals("image/png", outcome.browserScreenshot().screenshot().mimeType(), "browser screenshot MIME type");
        assertTrue(outcome.browserScreenshot().screenshot().byteSize() > 0, "browser screenshot byte size");

        assertEquals("SUCCESS", outcome.elements().status(), "element inspection status");
        assertEquals("Button", outcome.elements().inspection().category(), "element inspection category");
        assertTrue(outcome.elements().inspection().resolvedXPath() != null, "element inspection resolved XPath");
        assertTrue(outcome.elements().inspection().matchCount() >= 0, "element match count");

        assertEquals("FAILED", outcome.failedServiceCall().status(), "retry-friendly failed service-call");
        assertEquals("SUCCESS", outcome.serviceCall().status(), "service-call control status");
        assertEquals("%health-full-url", outcome.serviceCall().evidence().selector(), "service-call selector");
        assertEquals(200, outcome.serviceCall().evidence().statusCode(), "service-call status code");
        assertTrue(outcome.serviceCall().evidence().request().value() != null, "service-call request evidence");
        assertTrue(outcome.serviceCall().evidence().response().value() != null, "service-call response evidence");

        assertTrue(
                outcome.breakpointsBefore().stream().anyMatch(bp -> bp.breakpointId().equals(outcome.breakpoint().breakpointId())),
                "breakpoint should be listed before hit"
        );
        assertTrue(outcome.breakpointPause().paused(), "breakpoint should pause the scenario");
        assertEquals("AFTER_STEP", outcome.breakpointPause().lastHook(), "breakpoint hook");
        assertTrue(
                outcome.breakpointsAfterHit().stream().noneMatch(bp -> bp.breakpointId().equals(outcome.breakpoint().breakpointId())),
                "one-shot breakpoint should be removed after hit"
        );

        assertTrue(
                outcome.events().events().stream().anyMatch(event ->
                        "AFTER_STEP".equals(event.hook())
                                && event.stepText() != null
                                && event.stepText().contains("CONTROL BRIDGE IPC SYNC POINT")
                ),
                "semantic event history should contain the sync-point AFTER_STEP event"
        );
        assertTrue(
                outcome.events().events().stream().allMatch(event ->
                        outcome.scenario().scenarioId().equals(event.scenarioId())
                ),
                "scenario-filtered event history"
        );
        assertIncreasing(outcome.events().events());
        assertTrue(
                outcome.cursorEvents().events().isEmpty(),
                "cursor read while sync-point scenario is paused should not repeat retained events"
        );

        assertMappingValue(outcome.baseline(), "baseline", "mapping baseline");
        assertEquals("SUCCESS", outcome.snapshot().status(), "mapping snapshot status");
        assertTrue(outcome.snapshot().snapshot().restorable(), "OVERRIDE snapshot should be restorable");
        assertMaterializedMappingValue(outcome.snapshot().snapshot().values().get("controlBridgeIpcValue"), "baseline", "mapping snapshot value");
        assertMappingValue(outcome.written(), "bridge-value", "mapping write");
        assertMappingValue(outcome.read(), "bridge-value", "mapping read");
        assertMappingValue(outcome.resolved(), "bridge-value", "mapping resolve");
        assertEquals("SUCCESS", outcome.restored().status(), "mapping restore status");
        assertMappingValue(outcome.restoredRead(), "baseline", "mapping restored value");
        assertEquals("FAILED", outcome.failed().status(), "retry-friendly failing step");
        assertEquals("SUCCESS", outcome.succeeded().status(), "successful retry step");
        assertEquals("SUCCESS", outcome.firstResume().status(), "resume before breakpoint");
        assertEquals("SUCCESS", outcome.secondResume().status(), "resume after breakpoint");
        assertEquals(1, ControlApiTestSteps.invocationCount(), "detached successful invocation count");
        assertTrue(!getCurrentScenarioState().isScenarioFailed(), "detached failures must not fail scenario");
    }

    @After("@control-bridge")
    public void cleanupControlBridge() {
        try {
            if (descriptor != null && token != null) post("/v1/resume", Map.of());
        } catch (Exception ignored) {
        } finally {
            ControlBridgeBootstrap.stop();
            if (sessionDirectory != null) {
                try { Files.deleteIfExists(sessionDirectory); } catch (IOException ignored) { }
            }
        }
    }

    private ControlBridgeScenarioStatus awaitScenario(String scenarioId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (ControlBridgeScenarioStatus active : scenarios()) {
                if (scenarioId.equals(active.scenarioId())) return active;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Control bridge did not publish scenario " + scenarioId);
    }

    private ControlBridgeScenarioStatus awaitPaused(String scenarioId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (ControlBridgeScenarioStatus active : scenarios()) {
                if (scenarioId.equals(active.scenarioId()) && active.paused()) return active;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Semantic breakpoint did not pause scenario " + scenarioId);
    }

    private List<ControlBridgeScenarioStatus> scenarios() throws Exception {
        return List.of(getTyped("/v1/scenarios", ControlBridgeScenarioStatus[].class));
    }

    private List<ControlBridgeBreakpoint> breakpoints() throws Exception {
        ControlBridgeBreakpoint[] values = getTyped("/v1/breakpoints", ControlBridgeBreakpoint[].class);
        return List.of(values);
    }

    private ControlBridgeEventPage events(String scenarioId, long afterSequence, int limit) throws Exception {
        String path = "/v1/events?scenarioId=" + URLEncoder.encode(scenarioId, StandardCharsets.UTF_8)
                + "&afterSequence=" + afterSequence + "&limit=" + limit;
        return getTyped(path, ControlBridgeEventPage.class);
    }

    private ControlBridgeCallResult post(String path, Object body) throws Exception {
        return postTyped(path, body, ControlBridgeCallResult.class);
    }

    private ControlBridgeValueResult postValue(String path, Object body) throws Exception {
        return postTyped(path, body, ControlBridgeValueResult.class);
    }

    private <T> T postTyped(String path, Object body, Class<T> type) throws Exception {
        HttpResponse<byte[]> response = send("POST", path, body, token);
        assertEquals(200, response.statusCode(), path + " HTTP status");
        return json.readValue(response.body(), type);
    }

    private <T> T getTyped(String path, Class<T> type) throws Exception {
        HttpResponse<byte[]> response = send("GET", path, null, token);
        assertEquals(200, response.statusCode(), path + " HTTP status");
        return json.readValue(response.body(), type);
    }

    private ControlBridgeStatus status() throws Exception {
        return getTyped("/v1/status", ControlBridgeStatus.class);
    }

    private HttpResponse<byte[]> send(String method, String path, Object body, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
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
        return URI.create("http://" + descriptor.host() + ":" + descriptor.port() + path);
    }

    private static void assertIncreasing(List<ControlBridgeEvent> events) {
        long previous = 0;
        for (ControlBridgeEvent event : events) {
            assertTrue(event.sequence() > previous, "event sequence should increase");
            previous = event.sequence();
        }
    }

    private static void assertMaterializedMappingValue(Object value, String expected, String label) {
        assertTrue(value instanceof List<?>, label + " should be a materialized collection");
        List<?> values = (List<?>) value;
        assertTrue(!values.isEmpty(), label + " should not be empty");
        assertEquals(expected, values.getLast(), label);
    }

    private static void assertMappingValue(ControlBridgeValueResult result, Object expected, String label) {
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
        if (!value) throw new AssertionError(label);
    }

    private record ClientOutcome(
            int unauthorizedStatus,
            ControlBridgeScenarioStatus scenario,
            ControlBridgeCallResult wrongTarget,
            ControlBridgeCallResult paused,
            ControlBridgeBrowserPageResult browserPage,
            ControlBridgeBrowserScreenshotResult browserScreenshot,
            ControlBridgeElementInspectionResult elements,
            ControlBridgeServiceCallResult failedServiceCall,
            ControlBridgeServiceCallResult serviceCall,
            ControlBridgeBreakpoint breakpoint,
            List<ControlBridgeBreakpoint> breakpointsBefore,
            ControlBridgeScenarioStatus breakpointPause,
            List<ControlBridgeBreakpoint> breakpointsAfterHit,
            ControlBridgeEventPage events,
            ControlBridgeEventPage cursorEvents,
            ControlBridgeValueResult baseline,
            ControlBridgeMappingSnapshotResult snapshot,
            ControlBridgeValueResult written,
            ControlBridgeValueResult read,
            ControlBridgeValueResult resolved,
            ControlBridgeCallResult restored,
            ControlBridgeValueResult restoredRead,
            ControlBridgeCallResult failed,
            ControlBridgeCallResult succeeded,
            ControlBridgeCallResult firstResume,
            ControlBridgeCallResult secondResume
    ) { }
}
