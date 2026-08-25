package tools.dscode.workbench.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.control.protocol.*;

import static tools.dscode.control.protocol.ControlBridgeRequests.*;
import static tools.dscode.control.protocol.ControlBridgeResponses.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Workbench-side HTTP client for the consumer-hosted Pickleball control bridge.
 *
 * <p>The client depends only on the neutral control-protocol DTOs. Pickleball
 * runtime semantics remain exclusively in the consumer worker.</p>
 */
public final class ControlBridgeClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper json;
    private final HttpClient http;
    private final ControlBridgeDescriptor descriptor;
    private final String token;

    public ControlBridgeClient(ControlBridgeDescriptor descriptor, String token) {
        this(
                Objects.requireNonNull(descriptor, "descriptor"),
                requireToken(token),
                new ObjectMapper(),
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
        );
    }

    private ControlBridgeClient(
            ControlBridgeDescriptor descriptor,
            String token,
            ObjectMapper json,
            HttpClient http
    ) {
        this.descriptor = validateDescriptor(descriptor);
        this.token = token;
        this.json = json;
        this.http = http;
    }

    public static ControlBridgeClient fromDescriptor(Path descriptorFile, String token) {
        Objects.requireNonNull(descriptorFile, "descriptorFile");
        try {
            ObjectMapper json = new ObjectMapper();
            ControlBridgeDescriptor descriptor =
                    json.readValue(descriptorFile.toFile(), ControlBridgeDescriptor.class);
            return new ControlBridgeClient(
                    descriptor,
                    requireToken(token),
                    json,
                    HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
            );
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not read Pickleball control bridge descriptor: " + descriptorFile,
                    failure
            );
        }
    }

    public ControlBridgeDescriptor descriptor() {
        return descriptor;
    }

    public ControlBridgeStatus status() {
        return request("GET", "/v1/status", null, ControlBridgeStatus.class, READ_TIMEOUT);
    }

    public List<ControlBridgeScenarioStatus> scenarios() {
        return List.of(request(
                "GET", "/v1/scenarios", null, ControlBridgeScenarioStatus[].class, READ_TIMEOUT
        ));
    }

    public ControlBridgeEventPage events(String scenarioId, Long afterSequence, Integer limit) {
        List<String> parameters = new ArrayList<>();
        if (scenarioId != null && !scenarioId.isBlank()) {
            parameters.add("scenarioId=" + encode(scenarioId.trim()));
        }
        if (afterSequence != null) parameters.add("afterSequence=" + afterSequence);
        if (limit != null) parameters.add("limit=" + limit);
        String path = "/v1/events" + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
        return request("GET", path, null, ControlBridgeEventPage.class, READ_TIMEOUT);
    }

    public ControlBridgeCallResult pause(String scenarioId, Integer waitSeconds, Integer leaseSeconds) {
        int wait = waitSeconds == null ? 30 : waitSeconds;
        return request(
                "POST", "/v1/pause",
                new PauseRequest(scenarioId, waitSeconds, leaseSeconds),
                ControlBridgeCallResult.class,
                Duration.ofSeconds(Math.max(10, wait + 5L))
        );
    }

    public ControlBridgeCallResult resume(String scenarioId) {
        return request(
                "POST", "/v1/resume", new ResumeRequest(scenarioId),
                ControlBridgeCallResult.class, READ_TIMEOUT
        );
    }

    public ControlBridgeCallResult executeStep(
            String scenarioId, String text, String argument, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/steps/execute",
                new ExecuteStepRequest(scenarioId, text, argument, timeoutSeconds),
                ControlBridgeCallResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeValueResult mappingGet(
            String scenarioId, String mapReference, String key, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/mappings/get",
                new MappingGetRequest(scenarioId, mapReference, key, timeoutSeconds),
                ControlBridgeValueResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeValueResult mappingPut(
            String scenarioId, String mapReference, String key, Object value, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/mappings/put",
                new MappingPutRequest(scenarioId, mapReference, key, value, timeoutSeconds),
                ControlBridgeValueResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeValueResult mappingResolve(
            String scenarioId, String input, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/mappings/resolve",
                new MappingResolveRequest(scenarioId, input, timeoutSeconds),
                ControlBridgeValueResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeMappingSnapshotResult mappingSnapshot(
            String scenarioId, String mapReference, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/mappings/snapshot",
                new MappingSnapshotRequest(scenarioId, mapReference, timeoutSeconds),
                ControlBridgeMappingSnapshotResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeCallResult mappingRestore(
            String scenarioId, ControlBridgeMappingSnapshot snapshot, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/mappings/restore",
                new MappingRestoreRequest(scenarioId, snapshot, timeoutSeconds),
                ControlBridgeCallResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeBrowserPageResult browserPage(String scenarioId, Integer timeoutSeconds) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/browser/page",
                new BrowserEvidenceRequest(scenarioId, timeoutSeconds),
                ControlBridgeBrowserPageResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeBrowserScreenshotResult browserScreenshot(
            String scenarioId, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/browser/screenshot",
                new BrowserEvidenceRequest(scenarioId, timeoutSeconds),
                ControlBridgeBrowserScreenshotResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeElementInspectionResult elementInspect(
            String scenarioId,
            String category,
            String text,
            String operation,
            Integer maxElements,
            Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/browser/elements",
                new ElementInspectionRequest(
                        scenarioId, category, text, operation, maxElements, timeoutSeconds
                ),
                ControlBridgeElementInspectionResult.class, commandDuration(timeout)
        );
    }

    public ControlBridgeServiceCallResult serviceCall(
            String scenarioId, String selector, Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/services/call",
                new ServiceCallRequest(scenarioId, selector, timeoutSeconds),
                ControlBridgeServiceCallResult.class, commandDuration(timeout)
        );
    }

    public List<ControlBridgeBreakpoint> breakpoints() {
        return List.of(request(
                "GET", "/v1/breakpoints", null, ControlBridgeBreakpoint[].class, READ_TIMEOUT
        ));
    }

    public ControlBridgeBreakpoint addBreakpoint(
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
                        scenarioId, hook, signatureContains, stepContains,
                        phraseContains, oneShot, leaseSeconds
                ),
                ControlBridgeBreakpoint.class, READ_TIMEOUT
        );
    }

    public boolean removeBreakpoint(String breakpointId) {
        return request(
                "POST", "/v1/breakpoints/remove",
                new BreakpointIdRequest(breakpointId),
                Removal.class, READ_TIMEOUT
        ).removed();
    }

    public int clearBreakpoints() {
        return request(
                "POST", "/v1/breakpoints/clear", null,
                ClearResult.class, READ_TIMEOUT
        ).removed();
    }

    public List<ControlBridgeStepOverride> stepOverrides(String scenarioId) {
        String path = "/v1/step-overrides?scenarioId=" + encode(requireText(scenarioId, "scenarioId"));
        return List.of(request(
                "GET", path, null, ControlBridgeStepOverride[].class, READ_TIMEOUT
        ));
    }

    /**
     * Compiles and installs one REPLACE-mode REGEX Step Override in the selected worker scenario.
     * The Java source must contain {@code {{CLASS_NAME}}} where the generated class name belongs.
     */
    public ControlBridgeStepOverrideResult compileStepOverride(
            String scenarioId,
            String id,
            String regex,
            String source,
            Integer timeoutSeconds
    ) {
        int timeout = commandTimeout(timeoutSeconds);
        return request(
                "POST", "/v1/step-overrides/compile",
                new StepOverrideCompileRequest(
                        scenarioId, id, "REGEX", regex, source
                ),
                ControlBridgeStepOverrideResult.class,
                commandDuration(timeout)
        );
    }

    public boolean removeStepOverride(String scenarioId, String id) {
        return request(
                "POST", "/v1/step-overrides/remove",
                new StepOverrideIdRequest(scenarioId, id),
                Removal.class, READ_TIMEOUT
        ).removed();
    }

    public int clearStepOverrides(String scenarioId) {
        return request(
                "POST", "/v1/step-overrides/clear",
                new StepOverrideScenarioRequest(scenarioId),
                ClearResult.class, READ_TIMEOUT
        ).removed();
    }

    private <T> T request(
            String method,
            String path,
            Object body,
            Class<T> responseType,
            Duration timeout
    ) {
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
                throw new IllegalStateException("Could not serialize control bridge request.", failure);
            }
        }

        try {
            HttpResponse<byte[]> response =
                    http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Control bridge returned HTTP " + response.statusCode() + ": "
                                + new String(response.body(), StandardCharsets.UTF_8)
                );
            }
            return json.readValue(response.body(), responseType);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Control bridge request was interrupted.", failure);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not communicate with control bridge " + descriptor.runtimeId() + ".",
                    failure
            );
        }
    }

    private URI uri(String path) {
        return URI.create("http://" + descriptor.host() + ":" + descriptor.port() + path);
    }

    private static ControlBridgeDescriptor validateDescriptor(ControlBridgeDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!"127.0.0.1".equals(descriptor.host())) {
            throw new IllegalArgumentException(
                    "Control bridge descriptor is not loopback-bound: " + descriptor.host()
            );
        }
        if (descriptor.port() <= 0 || descriptor.port() > 65_535) {
            throw new IllegalArgumentException(
                    "Control bridge descriptor has an invalid loopback port: " + descriptor.port()
            );
        }
        if (descriptor.sessionId() == null || descriptor.sessionId().isBlank()
                || descriptor.runtimeId() == null || descriptor.runtimeId().isBlank()) {
            throw new IllegalArgumentException(
                    "Control bridge descriptor must identify its session and runtime."
            );
        }
        boolean validWorkerRange = descriptor.minimumCompatibleProtocolVersion() > 0
                && descriptor.protocolVersion() >= descriptor.minimumCompatibleProtocolVersion();
        boolean compatible = validWorkerRange
                && descriptor.protocolVersion() >= ControlProtocol.MINIMUM_COMPATIBLE_VERSION
                && descriptor.minimumCompatibleProtocolVersion() <= ControlProtocol.CURRENT_VERSION;
        if (!compatible) {
            throw new IllegalArgumentException(
                    "Incompatible control bridge protocol: worker=" + descriptor.protocolVersion()
                            + " (minimum " + descriptor.minimumCompatibleProtocolVersion() + ")"
                            + ", controller=" + ControlProtocol.CURRENT_VERSION
                            + " (minimum " + ControlProtocol.MINIMUM_COMPATIBLE_VERSION + ")."
            );
        }

        List<String> missing = ControlProtocol.CONTROLLER_REQUIRED_CAPABILITIES.stream()
                .filter(capability -> !descriptor.capabilities().contains(capability))
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    "Consumer worker is missing required Workbench capabilities: " + missing
            );
        }
        return descriptor;
    }

    private static int commandTimeout(Integer timeoutSeconds) {
        return timeoutSeconds == null ? 60 : timeoutSeconds;
    }

    private static Duration commandDuration(int timeoutSeconds) {
        return Duration.ofSeconds(Math.max(10, timeoutSeconds + 5L));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireToken(String token) {
        return requireText(token, "Control bridge bearer token");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return value.trim();
    }

}
