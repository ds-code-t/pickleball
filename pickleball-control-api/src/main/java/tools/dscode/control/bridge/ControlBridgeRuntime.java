package tools.dscode.control.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.dscode.common.control.ControlRuntime;
import tools.dscode.control.override.StepOverrideCompiler;
import tools.dscode.control.override.StepOverridePatternType;
import tools.dscode.control.override.StepOverrideRegistry;
import tools.dscode.control.override.StepOverrideRule;
import tools.dscode.control.protocol.ControlBridgeDescriptor;
import tools.dscode.control.protocol.ControlBridgeError;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshot;
import tools.dscode.control.protocol.ControlBridgeStatus;
import tools.dscode.control.protocol.ControlBridgeStepOverride;
import tools.dscode.control.protocol.ControlBridgeStepOverrideResult;
import tools.dscode.control.protocol.ControlProtocol;

import static tools.dscode.control.protocol.ControlBridgeRequests.*;
import static tools.dscode.control.protocol.ControlBridgeResponses.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class ControlBridgeRuntime implements AutoCloseable {
    private static final String HOST = "127.0.0.1";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final ObjectMapper json = new ObjectMapper();
    private final String token;
    private final Path descriptorFile;
    private final ControlBridgeEventRecorder eventRecorder;
    private final ControlBridgeCoordinator coordinator;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ControlBridgeDescriptor descriptor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ControlBridgeRuntime(
            String token,
            Path descriptorFile,
            ControlBridgeEventRecorder eventRecorder,
            ControlBridgeCoordinator coordinator,
            HttpServer server,
            ExecutorService executor,
            ControlBridgeDescriptor descriptor
    ) {
        this.token = token;
        this.descriptorFile = descriptorFile;
        this.eventRecorder = eventRecorder;
        this.coordinator = coordinator;
        this.server = server;
        this.executor = executor;
        this.descriptor = descriptor;
    }

    static ControlBridgeRuntime start(Path sessionDirectory, String sessionId, String token, boolean pauseFirstScenario) {
        if (sessionDirectory == null) throw new IllegalArgumentException("Bridge session directory must not be null.");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("Bridge session id must not be blank.");
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Bridge token must not be blank.");

        Path directory = sessionDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create Pickleball Workbench bridge session directory: " + directory, failure);
        }

        String runtimeId = UUID.randomUUID().toString();
        long pid = ProcessHandle.current().pid();
        ControlBridgeEventRecorder eventRecorder = new ControlBridgeEventRecorder();
        ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator(
                runtimeId, pid, ControlProtocol.WORKER_CAPABILITIES, pauseFirstScenario
        );

        HttpServer server = null;
        ExecutorService executor = null;
        Path descriptorFile = directory.resolve("runtime-" + runtimeId + ".json");
        try {
            server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            ControlBridgeRuntime runtime = new ControlBridgeRuntime(
                    token,
                    descriptorFile,
                    eventRecorder,
                    coordinator,
                    server,
                    executor,
                    new ControlBridgeDescriptor(
                            ControlProtocol.CURRENT_VERSION,
                            ControlProtocol.MINIMUM_COMPATIBLE_VERSION,
                            sessionId,
                            runtimeId,
                            pid,
                            HOST,
                            server.getAddress().getPort(),
                            Instant.now().toString(),
                            runtimeVersion(),
                            runtimeCodeSource(),
                            ControlProtocol.WORKER_CAPABILITIES
                    )
            );
            runtime.registerContexts();
            server.start();
            ControlRuntime.addObserver(eventRecorder);
            ControlRuntime.addObserver(coordinator);
            runtime.writeDescriptor();
            return runtime;
        } catch (Throwable failure) {
            ControlRuntime.removeObserver(coordinator);
            ControlRuntime.removeObserver(eventRecorder);
            coordinator.close();
            eventRecorder.close();
            if (server != null) server.stop(0);
            if (executor != null) executor.shutdownNow();
            try { Files.deleteIfExists(descriptorFile); } catch (IOException ignored) { }
            throw failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("Could not start Pickleball Workbench control bridge.", failure);
        }
    }

    ControlBridgeDescriptor descriptor() {
        return descriptor;
    }

    private void registerContexts() {
        server.createContext("/v1/status", exchange -> handle(exchange, "GET", coordinator::status));
        server.createContext("/v1/scenarios", exchange -> handle(exchange, "GET", coordinator::scenarios));
        server.createContext("/v1/events", exchange -> handle(exchange, "GET", () -> eventRecorder.page(
                queryParameter(exchange, "scenarioId"),
                longQueryParameter(exchange, "afterSequence"),
                intQueryParameter(exchange, "limit")
        )));
        server.createContext("/v1/pause", exchange -> handle(exchange, "POST", () -> {
            PauseRequest request = readOptional(exchange, PauseRequest.class);
            return coordinator.requestPause(
                    request == null ? null : request.scenarioId(),
                    request == null ? null : request.waitSeconds(),
                    request == null ? null : request.leaseSeconds()
            );
        }));
        server.createContext("/v1/resume", exchange -> handle(exchange, "POST", () -> {
            ResumeRequest request = readOptional(exchange, ResumeRequest.class);
            return coordinator.resume(request == null ? null : request.scenarioId());
        }));
        server.createContext("/v1/steps/execute", exchange -> handle(exchange, "POST", () -> {
            ExecuteStepRequest request = readRequired(exchange, ExecuteStepRequest.class);
            return coordinator.executeStep(request.scenarioId(), request.text(), request.argument(), request.timeoutSeconds());
        }));
        server.createContext("/v1/mappings/get", exchange -> handle(exchange, "POST", () -> {
            MappingGetRequest request = readRequired(exchange, MappingGetRequest.class);
            return coordinator.mappingGet(request.scenarioId(), request.mapReference(), request.key(), request.timeoutSeconds());
        }));
        server.createContext("/v1/mappings/put", exchange -> handle(exchange, "POST", () -> {
            MappingPutRequest request = readRequired(exchange, MappingPutRequest.class);
            return coordinator.mappingPut(request.scenarioId(), request.mapReference(), request.key(), request.value(), request.timeoutSeconds());
        }));
        server.createContext("/v1/mappings/resolve", exchange -> handle(exchange, "POST", () -> {
            MappingResolveRequest request = readRequired(exchange, MappingResolveRequest.class);
            return coordinator.mappingResolve(request.scenarioId(), request.input(), request.timeoutSeconds());
        }));
        server.createContext("/v1/mappings/snapshot", exchange -> handle(exchange, "POST", () -> {
            MappingSnapshotRequest request = readRequired(exchange, MappingSnapshotRequest.class);
            return coordinator.mappingSnapshot(request.scenarioId(), request.mapReference(), request.timeoutSeconds());
        }));
        server.createContext("/v1/mappings/restore", exchange -> handle(exchange, "POST", () -> {
            MappingRestoreRequest request = readRequired(exchange, MappingRestoreRequest.class);
            return coordinator.mappingRestore(request.scenarioId(), request.snapshot(), request.timeoutSeconds());
        }));
        server.createContext("/v1/browser/page", exchange -> handle(exchange, "POST", () -> {
            BrowserEvidenceRequest request = readRequired(exchange, BrowserEvidenceRequest.class);
            return coordinator.browserPage(request.scenarioId(), request.timeoutSeconds());
        }));
        server.createContext("/v1/browser/screenshot", exchange -> handle(exchange, "POST", () -> {
            BrowserEvidenceRequest request = readRequired(exchange, BrowserEvidenceRequest.class);
            return coordinator.browserScreenshot(request.scenarioId(), request.timeoutSeconds());
        }));
        server.createContext("/v1/browser/elements", exchange -> handle(exchange, "POST", () -> {
            ElementInspectionRequest request = readRequired(exchange, ElementInspectionRequest.class);
            return coordinator.inspectElements(
                    request.scenarioId(), request.category(), request.text(), request.operation(),
                    request.maxElements(), request.timeoutSeconds()
            );
        }));
        server.createContext("/v1/services/call", exchange -> handle(exchange, "POST", () -> {
            ServiceCallRequest request = readRequired(exchange, ServiceCallRequest.class);
            return coordinator.executeServiceCall(request.scenarioId(), request.selector(), request.timeoutSeconds());
        }));
        server.createContext("/v1/breakpoints", exchange -> handle(exchange, "GET", coordinator::breakpoints));
        server.createContext("/v1/breakpoints/add", exchange -> handle(exchange, "POST", () -> {
            BreakpointAddRequest request = readRequired(exchange, BreakpointAddRequest.class);
            return coordinator.addBreakpoint(
                    request.scenarioId(), request.hook(), request.signatureContains(),
                    request.stepContains(), request.phraseContains(), request.oneShot(), request.leaseSeconds()
            );
        }));
        server.createContext("/v1/breakpoints/remove", exchange -> handle(exchange, "POST", () -> {
            BreakpointIdRequest request = readRequired(exchange, BreakpointIdRequest.class);
            return new Removal(coordinator.removeBreakpoint(request.breakpointId()));
        }));
        server.createContext("/v1/breakpoints/clear", exchange -> handle(exchange, "POST", () ->
                new ClearResult(coordinator.clearBreakpoints())));

        server.createContext("/v1/step-overrides", exchange -> handle(exchange, "GET", () -> {
            String scenarioId = queryParameter(exchange, "scenarioId");
            if (!scenarioActive(scenarioId)) return new ControlBridgeStepOverride[0];
            return StepOverrideRegistry.rules(scenarioId).stream()
                    .map(ControlBridgeRuntime::overrideSnapshot)
                    .toArray(ControlBridgeStepOverride[]::new);
        }));
        server.createContext("/v1/step-overrides/compile", exchange -> handle(exchange, "POST", () -> {
            StepOverrideCompileRequest request = readRequired(exchange, StepOverrideCompileRequest.class);
            return compileOverride(request);
        }));
        server.createContext("/v1/step-overrides/remove", exchange -> handle(exchange, "POST", () -> {
            StepOverrideIdRequest request = readRequired(exchange, StepOverrideIdRequest.class);
            if (!scenarioActive(request.scenarioId())) return new Removal(false);
            return new Removal(StepOverrideRegistry.remove(request.scenarioId(), request.id()));
        }));
        server.createContext("/v1/step-overrides/clear", exchange -> handle(exchange, "POST", () -> {
            StepOverrideScenarioRequest request = readRequired(exchange, StepOverrideScenarioRequest.class);
            if (!scenarioActive(request.scenarioId())) return new ClearResult(0);
            return new ClearResult(StepOverrideRegistry.clear(request.scenarioId()));
        }));
    }

    private ControlBridgeStepOverrideResult compileOverride(StepOverrideCompileRequest request) {
        ControlBridgeStatus runtime = coordinator.status();
        if (!scenarioActive(request.scenarioId())) {
            return new ControlBridgeStepOverrideResult(
                    "UNAVAILABLE", null,
                    new ControlBridgeError(
                            "UNAVAILABLE",
                            "No active scenario with id " + request.scenarioId() + ".",
                            ""
                    ),
                    runtime
            );
        }

        StepOverridePatternType patternType;
        try {
            patternType = StepOverridePatternType.valueOf(
                    request.patternType() == null ? "" : request.patternType().trim().toUpperCase()
            );
        } catch (IllegalArgumentException failure) {
            return failedOverride(failure, runtime);
        }

        try {
            StepOverrideRule rule = StepOverrideCompiler.compile(
                    request.scenarioId(),
                    request.id(),
                    patternType,
                    request.pattern(),
                    request.source()
            );
            return new ControlBridgeStepOverrideResult(
                    "SUCCESS", overrideSnapshot(rule), null, coordinator.status()
            );
        } catch (StepOverrideCompiler.CompilerUnavailableException failure) {
            return new ControlBridgeStepOverrideResult(
                    "UNAVAILABLE", null,
                    bridgeError(failure),
                    coordinator.status()
            );
        } catch (Throwable failure) {
            return failedOverride(failure, coordinator.status());
        }
    }

    private static ControlBridgeStepOverrideResult failedOverride(
            Throwable failure,
            ControlBridgeStatus runtime
    ) {
        return new ControlBridgeStepOverrideResult(
                "FAILED", null, bridgeError(failure), runtime
        );
    }

    private boolean scenarioActive(String scenarioId) {
        if (scenarioId == null || scenarioId.isBlank()) return false;
        return coordinator.scenarios().stream()
                .anyMatch(scenario -> scenarioId.equals(scenario.scenarioId()));
    }

    private static ControlBridgeStepOverride overrideSnapshot(StepOverrideRule rule) {
        return new ControlBridgeStepOverride(
                rule.id(),
                rule.patternType().name(),
                rule.pattern(),
                StepOverrideCompiler.handlerClassName(rule)
        );
    }

    private static ControlBridgeError bridgeError(Throwable failure) {
        StringWriter writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        return new ControlBridgeError(
                failure.getClass().getName(),
                safeMessage(failure),
                writer.toString()
        );
    }

    private void handle(HttpExchange exchange, String expectedMethod, RequestAction action) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", expectedMethod);
                sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            if (!authorized(exchange)) {
                sendJson(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            try {
                sendJson(exchange, 200, action.run());
            } catch (IllegalArgumentException | IOException failure) {
                sendJson(exchange, 400, Map.of("error", "invalid_request", "message", safeMessage(failure)));
            } catch (Throwable failure) {
                sendJson(exchange, 500, Map.of("error", "bridge_failure", "message", safeMessage(failure)));
            }
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private <T> T readRequired(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] body = readBody(exchange);
        if (body.length == 0) throw new IllegalArgumentException("Request body is required.");
        return json.readValue(body, type);
    }

    private <T> T readOptional(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] body = readBody(exchange);
        return body.length == 0 ? null : json.readValue(body, type);
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) throw new IllegalArgumentException("Bridge request body exceeds " + MAX_REQUEST_BYTES + " bytes.");
        return body;
    }

    private String queryParameter(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return null;
        for (String parameter : raw.split("&")) {
            int separator = parameter.indexOf('=');
            String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
            if (!name.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) continue;
            String rawValue = separator < 0 ? "" : parameter.substring(separator + 1);
            return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        }
        return null;
    }

    private Long longQueryParameter(HttpExchange exchange, String name) {
        String value = queryParameter(exchange, name);
        if (value == null || value.isBlank()) return null;
        try { return Long.valueOf(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(name + " must be a whole number.", failure); }
    }

    private Integer intQueryParameter(HttpExchange exchange, String name) {
        String value = queryParameter(exchange, name);
        if (value == null || value.isBlank()) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(name + " must be a whole number.", failure); }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private void writeDescriptor() {
        Path temporary = descriptorFile.resolveSibling(descriptorFile.getFileName() + ".tmp");
        try {
            json.writeValue(temporary.toFile(), descriptor);
            try {
                Files.move(temporary, descriptorFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailure) {
                Files.move(temporary, descriptorFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("Could not publish Pickleball Workbench bridge descriptor: " + descriptorFile, failure);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ControlRuntime.removeObserver(coordinator);
        ControlRuntime.removeObserver(eventRecorder);
        coordinator.close();
        eventRecorder.close();
        server.stop(0);
        executor.shutdownNow();
        try { Files.deleteIfExists(descriptorFile); } catch (IOException ignored) { }
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static String runtimeVersion() {
        String version = ControlBridgeRuntime.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development" : version;
    }

    private static String runtimeCodeSource() {
        try {
            var source = ControlBridgeRuntime.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return "unknown";
            return Path.of(source.getLocation().toURI()).toAbsolutePath().normalize().toString();
        } catch (Exception failure) {
            return "unknown";
        }
    }

    @FunctionalInterface
    private interface RequestAction { Object run() throws Exception; }

}
