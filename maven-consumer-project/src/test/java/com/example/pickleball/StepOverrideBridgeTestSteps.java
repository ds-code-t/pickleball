package com.example.pickleball;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import tools.dscode.control.bridge.ControlBridgeBootstrap;
import tools.dscode.control.bridge.ControlBridgeCallResult;
import tools.dscode.control.bridge.ControlBridgeDescriptor;
import tools.dscode.control.bridge.ControlBridgeScenarioStatus;
import tools.dscode.control.bridge.ControlBridgeStepOverride;
import tools.dscode.control.bridge.ControlBridgeStepOverrideResult;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class StepOverrideBridgeTestSteps {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Path sessionDirectory;
    private String token;
    private ControlBridgeDescriptor descriptor;
    private String targetScenarioId;
    private CompletableFuture<Outcome> client;

    @Given("^BEGIN STEP OVERRIDE BRIDGE TEST$")
    public void begin() throws Exception {
        sessionDirectory = Files.createTempDirectory("pkb-step-override-bridge-");
        token = "test-" + UUID.randomUUID();
        descriptor = ControlBridgeBootstrap.start(
                sessionDirectory, UUID.randomUUID().toString(), token, false
        );
        targetScenarioId = getCurrentScenarioState().id.toString();
        String scenarioId = targetScenarioId;

        client = CompletableFuture.supplyAsync(() -> {
            try {
                ControlBridgeScenarioStatus scenario = awaitScenario(scenarioId);
                ControlBridgeCallResult paused = post(
                        "/v1/pause",
                        Map.of("scenarioId", scenarioId, "waitSeconds", 10, "leaseSeconds", 30)
                );

                ControlBridgeStepOverrideResult first = compile(
                        scenarioId, "generated", "first-"
                );
                ControlBridgeCallResult firstExecution = execute(
                        scenarioId, "GENERATED BRIDGE alpha"
                );
                ControlBridgeValueResult firstValue = mappingGet(
                        scenarioId, "generatedBridgeValue"
                );

                ControlBridgeStepOverrideResult second = compile(
                        scenarioId, "generated", "second-"
                );
                ControlBridgeCallResult secondExecution = execute(
                        scenarioId, "GENERATED BRIDGE beta"
                );
                ControlBridgeValueResult secondValue = mappingGet(
                        scenarioId, "generatedBridgeValue"
                );
                List<ControlBridgeStepOverride> listed = overrides(scenarioId);

                boolean removed = postMap(
                        "/v1/step-overrides/remove",
                        Map.of("scenarioId", scenarioId, "id", "generated")
                ).get("removed").asBoolean();
                ControlBridgeCallResult afterRemoval = execute(
                        scenarioId, "GENERATED BRIDGE gamma"
                );

                post("/v1/resume", Map.of("scenarioId", scenarioId));
                return new Outcome(
                        paused, first, firstExecution, firstValue,
                        second, secondExecution, secondValue,
                        listed, removed, afterRemoval
                );
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    @Given("^STEP OVERRIDE BRIDGE SYNC POINT$")
    public void syncPoint() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (ControlBridgeScenarioStatus scenario :
                    List.of(getTyped("/v1/scenarios", ControlBridgeScenarioStatus[].class))) {
                if (targetScenarioId.equals(scenario.scenarioId()) && scenario.pauseRequested()) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "Step Override bridge client did not request the scenario pause before the sync boundary."
        );
    }

    @Given("^VERIFY STEP OVERRIDE BRIDGE TEST$")
    public void verify() throws Exception {
        Outcome outcome = client.get(30, TimeUnit.SECONDS);

        assertEquals("SUCCESS", outcome.paused().status());
        assertEquals("SUCCESS", outcome.first().status());
        assertEquals("SUCCESS", outcome.firstExecution().status());
        assertMappingValue(outcome.firstValue(), "first-alpha");

        assertEquals("SUCCESS", outcome.second().status());
        assertEquals("SUCCESS", outcome.secondExecution().status());
        assertMappingValue(outcome.secondValue(), "second-beta");
        assertNotEquals(
                outcome.first().override().handlerClass(),
                outcome.second().override().handlerClass()
        );

        assertEquals(1, outcome.listed().size());
        assertEquals("generated", outcome.listed().getFirst().id());
        assertTrue(outcome.removed());
        assertEquals("FAILED", outcome.afterRemoval().status());
        assertTrue(!getCurrentScenarioState().isScenarioFailed());
    }

    @After("@step-override-bridge")
    public void cleanup() {
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

    private ControlBridgeStepOverrideResult compile(
            String scenarioId,
            String id,
            String prefix
    ) throws Exception {
        String source = """
                package com.example.pickleball.generated;
                import tools.dscode.control.api.MappingControl;
                import tools.dscode.control.override.StepOverrideContext;
                import tools.dscode.control.override.StepOverrideHandler;

                public final class {{CLASS_NAME}} implements StepOverrideHandler {
                    public Object execute(StepOverrideContext context) {
                        MappingControl.put(
                            "OVERRIDE",
                            "generatedBridgeValue",
                            "%s" + context.captures().getFirst()
                        );
                        return null;
                    }
                }
                """.formatted(prefix);

        return postTyped(
                "/v1/step-overrides/compile",
                Map.of(
                        "scenarioId", scenarioId,
                        "id", id,
                        "patternType", "REGEX",
                        "pattern", "^GENERATED BRIDGE ([A-Za-z]+)$",
                        "source", source
                ),
                ControlBridgeStepOverrideResult.class
        );
    }

    private ControlBridgeCallResult execute(String scenarioId, String text) throws Exception {
        return post(
                "/v1/steps/execute",
                Map.of(
                        "scenarioId", scenarioId,
                        "text", text,
                        "argument", "",
                        "timeoutSeconds", 10
                )
        );
    }

    private ControlBridgeValueResult mappingGet(String scenarioId, String key) throws Exception {
        return postTyped(
                "/v1/mappings/get",
                Map.of(
                        "scenarioId", scenarioId,
                        "mapReference", "OVERRIDE",
                        "key", key,
                        "timeoutSeconds", 10
                ),
                ControlBridgeValueResult.class
        );
    }

    private List<ControlBridgeStepOverride> overrides(String scenarioId) throws Exception {
        String path = "/v1/step-overrides?scenarioId="
                + URLEncoder.encode(scenarioId, StandardCharsets.UTF_8);
        return List.of(getTyped(path, ControlBridgeStepOverride[].class));
    }

    private ControlBridgeScenarioStatus awaitScenario(String scenarioId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (ControlBridgeScenarioStatus active :
                    List.of(getTyped("/v1/scenarios", ControlBridgeScenarioStatus[].class))) {
                if (scenarioId.equals(active.scenarioId())) return active;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Control bridge did not publish scenario " + scenarioId);
    }

    private ControlBridgeCallResult post(String path, Object body) throws Exception {
        return postTyped(path, body, ControlBridgeCallResult.class);
    }

    private com.fasterxml.jackson.databind.JsonNode postMap(String path, Object body) throws Exception {
        return postTyped(path, body, com.fasterxml.jackson.databind.JsonNode.class);
    }

    private <T> T postTyped(String path, Object body, Class<T> type) throws Exception {
        HttpResponse<byte[]> response = send("POST", path, body);
        assertEquals(200, response.statusCode(), path + " HTTP status");
        return json.readValue(response.body(), type);
    }

    private <T> T getTyped(String path, Class<T> type) throws Exception {
        HttpResponse<byte[]> response = send("GET", path, null);
        assertEquals(200, response.statusCode(), path + " HTTP status");
        return json.readValue(response.body(), type);
    }

    private HttpResponse<byte[]> send(String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
        if ("GET".equals(method)) {
            request.GET();
        } else {
            request.header("Content-Type", "application/json");
            request.POST(body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create(
                "http://" + descriptor.host() + ":" + descriptor.port() + path
        );
    }

    private static void assertMappingValue(
            ControlBridgeValueResult result,
            String expected
    ) {
        assertEquals("SUCCESS", result.status());
        assertEquals(expected, result.value().jsonValue());
    }

    private record Outcome(
            ControlBridgeCallResult paused,
            ControlBridgeStepOverrideResult first,
            ControlBridgeCallResult firstExecution,
            ControlBridgeValueResult firstValue,
            ControlBridgeStepOverrideResult second,
            ControlBridgeCallResult secondExecution,
            ControlBridgeValueResult secondValue,
            List<ControlBridgeStepOverride> listed,
            boolean removed,
            ControlBridgeCallResult afterRemoval
    ) {
    }
}
