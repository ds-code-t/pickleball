package tools.dscode.studio.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class RuntimeBridgeClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    private final RuntimeBridgeDescriptor descriptor;
    private final String token;

    RuntimeBridgeClient(RuntimeBridgeDescriptor descriptor, String token) {
        this.descriptor = descriptor;
        this.token = token;
    }

    RuntimeBridgeStatus status() {
        return request("GET", "/v1/status", null, RuntimeBridgeStatus.class, Duration.ofSeconds(10));
    }

    List<RuntimeScenarioStatus> scenarios() {
        return List.of(request("GET", "/v1/scenarios", null, RuntimeScenarioStatus[].class, Duration.ofSeconds(10)));
    }

    RuntimeEventPage events(String scenarioId, Long afterSequence, Integer limit) {
        List<String> parameters = new ArrayList<>();
        if (scenarioId != null && !scenarioId.isBlank()) parameters.add("scenarioId=" + encode(scenarioId.trim()));
        if (afterSequence != null) parameters.add("afterSequence=" + afterSequence);
        if (limit != null) parameters.add("limit=" + limit);
        String path = "/v1/events" + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
        return request("GET", path, null, RuntimeEventPage.class, Duration.ofSeconds(10));
    }

    RuntimeControlResult pause(Integer waitSeconds, Integer leaseSeconds) {
        return pause(null, waitSeconds, leaseSeconds);
    }

    RuntimeControlResult pause(String scenarioId, Integer waitSeconds, Integer leaseSeconds) {
        int wait = waitSeconds == null ? 30 : waitSeconds;
        return request(
                "POST", "/v1/pause", new PauseRequest(scenarioId, waitSeconds, leaseSeconds),
                RuntimeControlResult.class, Duration.ofSeconds(Math.max(10, wait + 5L))
        );
    }

    RuntimeControlResult resume() { return resume(null); }

    RuntimeControlResult resume(String scenarioId) {
        return request("POST", "/v1/resume", new ResumeRequest(scenarioId), RuntimeControlResult.class, Duration.ofSeconds(10));
    }

    RuntimeControlResult executeStep(String text, String argument, Integer timeoutSeconds) {
        return executeStep(null, text, argument, timeoutSeconds);
    }

    RuntimeControlResult executeStep(String scenarioId, String text, String argument, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/steps/execute", new ExecuteStepRequest(scenarioId, text, argument, timeoutSeconds),
                RuntimeControlResult.class, commandDuration(timeout)
        );
    }

    RuntimeValueResult mappingGet(String scenarioId, String mapReference, String key, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/mappings/get", new MappingGetRequest(scenarioId, mapReference, key, timeoutSeconds),
                RuntimeValueResult.class, commandDuration(timeout)
        );
    }

    RuntimeValueResult mappingPut(
            String scenarioId, String mapReference, String key, String jsonValue, Integer timeoutSeconds
    ) {
        if (jsonValue == null || jsonValue.isBlank()) {
            throw new IllegalArgumentException("Mapping jsonValue must contain one JSON literal. Use null for a JSON null value.");
        }
        Object value;
        try {
            value = json.readValue(jsonValue, Object.class);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Mapping jsonValue is not valid JSON.", failure);
        }
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/mappings/put", new MappingPutRequest(scenarioId, mapReference, key, value, timeoutSeconds),
                RuntimeValueResult.class, commandDuration(timeout)
        );
    }

    RuntimeValueResult mappingResolve(String scenarioId, String input, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/mappings/resolve", new MappingResolveRequest(scenarioId, input, timeoutSeconds),
                RuntimeValueResult.class, commandDuration(timeout)
        );
    }

    RuntimeBrowserPageResult browserPage(String scenarioId, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/browser/page", new BrowserEvidenceRequest(scenarioId, timeoutSeconds),
                RuntimeBrowserPageResult.class, commandDuration(timeout)
        );
    }

    RuntimeBrowserScreenshotBridgeResult browserScreenshot(String scenarioId, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/browser/screenshot", new BrowserEvidenceRequest(scenarioId, timeoutSeconds),
                RuntimeBrowserScreenshotBridgeResult.class, commandDuration(timeout)
        );
    }

    RuntimeElementInspectionResult elementInspect(
            String scenarioId,
            String category,
            String text,
            String operation,
            Integer maxElements,
            Integer timeoutSeconds
    ) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/browser/elements",
                new ElementInspectionRequest(scenarioId, category, text, operation, maxElements, timeoutSeconds),
                RuntimeElementInspectionResult.class, commandDuration(timeout)
        );
    }

    RuntimeServiceCallResult serviceCall(String scenarioId, String selector, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/services/call", new ServiceCallRequest(scenarioId, selector, timeoutSeconds),
                RuntimeServiceCallResult.class, commandDuration(timeout)
        );
    }

    List<RuntimeBreakpoint> breakpoints() {
        return List.of(request("GET", "/v1/breakpoints", null, RuntimeBreakpoint[].class, Duration.ofSeconds(10)));
    }

    RuntimeBreakpoint addBreakpoint(
            String scenarioId,
            String hook,
            String signatureContains,
            String stepContains,
            String phraseContains,
            Boolean oneShot,
            Integer leaseSeconds
    ) {
        return request(
                "POST", "/v1/breakpoints/add",
                new BreakpointAddRequest(
                        scenarioId, hook, signatureContains, stepContains, phraseContains, oneShot, leaseSeconds
                ),
                RuntimeBreakpoint.class, Duration.ofSeconds(10)
        );
    }

    boolean removeBreakpoint(String breakpointId) {
        return request(
                "POST", "/v1/breakpoints/remove", new BreakpointIdRequest(breakpointId),
                BreakpointRemoval.class, Duration.ofSeconds(10)
        ).removed();
    }

    int clearBreakpoints() {
        return request(
                "POST", "/v1/breakpoints/clear", null,
                BreakpointClear.class, Duration.ofSeconds(10)
        ).removed();
    }

    RuntimeMappingStateResult mappingSnapshot(String scenarioId, String mapReference, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/mappings/snapshot", new MappingSnapshotRequest(scenarioId, mapReference, timeoutSeconds),
                RuntimeMappingStateResult.class, commandDuration(timeout)
        );
    }

    RuntimeControlResult mappingRestore(String scenarioId, RuntimeMappingState snapshot, Integer timeoutSeconds) {
        int timeout = timeoutSeconds == null ? 60 : timeoutSeconds;
        return request(
                "POST", "/v1/mappings/restore", new MappingRestoreRequest(scenarioId, snapshot, timeoutSeconds),
                RuntimeControlResult.class, commandDuration(timeout)
        );
    }

    private <T> T request(String method, String path, Object body, Class<T> responseType, Duration timeout) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .timeout(timeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if ("GET".equals(method)) {
            request.GET();
        } else if (body == null) {
            request.POST(HttpRequest.BodyPublishers.noBody());
        } else {
            try {
                request.header("Content-Type", "application/json");
                request.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
            } catch (IOException failure) {
                throw new IllegalStateException("Could not serialize runtime bridge request.", failure);
            }
        }

        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Runtime bridge returned HTTP " + response.statusCode() + ": "
                                + new String(response.body(), StandardCharsets.UTF_8)
                );
            }
            return json.readValue(response.body(), responseType);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Runtime bridge request was interrupted.", failure);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not communicate with runtime bridge " + descriptor.runtimeId() + ".", failure);
        }
    }

    private URI uri(String path) {
        if (!"127.0.0.1".equals(descriptor.host())) {
            throw new IllegalArgumentException("Runtime bridge descriptor is not loopback-bound: " + descriptor.host());
        }
        if (descriptor.protocolVersion() != 1) {
            throw new IllegalArgumentException("Unsupported runtime bridge protocol: " + descriptor.protocolVersion());
        }
        return URI.create("http://" + descriptor.host() + ":" + descriptor.port() + path);
    }

    private static Duration commandDuration(int timeout) {
        return Duration.ofSeconds(Math.max(10, timeout + 5L));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record PauseRequest(String scenarioId, Integer waitSeconds, Integer leaseSeconds) { }
    private record ResumeRequest(String scenarioId) { }
    private record ExecuteStepRequest(String scenarioId, String text, String argument, Integer timeoutSeconds) { }
    private record BrowserEvidenceRequest(String scenarioId, Integer timeoutSeconds) { }
    private record ElementInspectionRequest(
            String scenarioId, String category, String text, String operation,
            Integer maxElements, Integer timeoutSeconds
    ) { }
    private record ServiceCallRequest(String scenarioId, String selector, Integer timeoutSeconds) { }
    private record BreakpointAddRequest(
            String scenarioId, String hook, String signatureContains, String stepContains,
            String phraseContains, Boolean oneShot, Integer leaseSeconds
    ) { }
    private record BreakpointIdRequest(String breakpointId) { }
    private record BreakpointRemoval(boolean removed) { }
    private record BreakpointClear(int removed) { }
    private record MappingGetRequest(String scenarioId, String mapReference, String key, Integer timeoutSeconds) { }
    private record MappingPutRequest(String scenarioId, String mapReference, String key, Object value, Integer timeoutSeconds) { }
    private record MappingResolveRequest(String scenarioId, String input, Integer timeoutSeconds) { }
    private record MappingSnapshotRequest(String scenarioId, String mapReference, Integer timeoutSeconds) { }
    private record MappingRestoreRequest(String scenarioId, RuntimeMappingState snapshot, Integer timeoutSeconds) { }
}
