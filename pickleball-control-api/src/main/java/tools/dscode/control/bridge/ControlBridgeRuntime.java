package tools.dscode.control.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.dscode.common.control.ControlRuntime;

import java.io.IOException;
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
    static final int PROTOCOL_VERSION = 1;
    static final List<String> CAPABILITIES = List.of(
            "status",
            "scenarios",
            "events",
            "pause",
            "resume",
            "execute_step",
            "mapping_get",
            "mapping_put",
            "mapping_resolve",
            "mapping_snapshot",
            "mapping_restore",
            "browser_page",
            "browser_screenshot"
    );

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

    static ControlBridgeRuntime start(
            Path sessionDirectory,
            String sessionId,
            String token,
            boolean pauseFirstScenario
    ) {
        if (sessionDirectory == null) {
            throw new IllegalArgumentException("Bridge session directory must not be null.");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Bridge session id must not be blank.");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Bridge token must not be blank.");
        }

        Path directory = sessionDirectory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(directory);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not create Pickleball Studio bridge session directory: " + directory,
                    failure
            );
        }

        String runtimeId = UUID.randomUUID().toString();
        long pid = ProcessHandle.current().pid();
        ControlBridgeEventRecorder eventRecorder = new ControlBridgeEventRecorder();
        ControlBridgeCoordinator coordinator = new ControlBridgeCoordinator(
                runtimeId,
                pid,
                CAPABILITIES,
                pauseFirstScenario
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
                            PROTOCOL_VERSION,
                            sessionId,
                            runtimeId,
                            pid,
                            HOST,
                            server.getAddress().getPort(),
                            Instant.now().toString(),
                            CAPABILITIES
                    )
            );

            runtime.registerContexts();
            server.start();
            // The recorder must observe the hook before the coordinator can pause on it.
            ControlRuntime.addObserver(eventRecorder);
            ControlRuntime.addObserver(coordinator);
            runtime.writeDescriptor();
            return runtime;
        } catch (Throwable failure) {
            ControlRuntime.removeObserver(coordinator);
            ControlRuntime.removeObserver(eventRecorder);
            coordinator.close();
            eventRecorder.close();
            if (server != null) {
                server.stop(0);
            }
            if (executor != null) {
                executor.shutdownNow();
            }
            try {
                Files.deleteIfExists(descriptorFile);
            } catch (IOException ignored) {
            }
            throw failure instanceof RuntimeException runtimeFailure
                    ? runtimeFailure
                    : new IllegalStateException("Could not start Pickleball Studio control bridge.", failure);
        }
    }

    ControlBridgeDescriptor descriptor() {
        return descriptor;
    }

    private void registerContexts() {
        server.createContext("/v1/status", exchange ->
                handle(exchange, "GET", coordinator::status));
        server.createContext("/v1/scenarios", exchange ->
                handle(exchange, "GET", coordinator::scenarios));
        server.createContext("/v1/events", exchange ->
                handle(exchange, "GET", () -> eventRecorder.page(
                        queryParameter(exchange, "scenarioId"),
                        longQueryParameter(exchange, "afterSequence"),
                        intQueryParameter(exchange, "limit")
                )));
        server.createContext("/v1/pause", exchange ->
                handle(exchange, "POST", () -> {
                    PauseRequest request = readOptional(exchange, PauseRequest.class);
                    return coordinator.requestPause(
                            request == null ? null : request.scenarioId(),
                            request == null ? null : request.waitSeconds(),
                            request == null ? null : request.leaseSeconds()
                    );
                }));
        server.createContext("/v1/resume", exchange ->
                handle(exchange, "POST", () -> {
                    ResumeRequest request = readOptional(exchange, ResumeRequest.class);
                    return coordinator.resume(request == null ? null : request.scenarioId());
                }));
        server.createContext("/v1/steps/execute", exchange ->
                handle(exchange, "POST", () -> {
                    ExecuteStepRequest request = readRequired(exchange, ExecuteStepRequest.class);
                    return coordinator.executeStep(
                            request.scenarioId(),
                            request.text(),
                            request.argument(),
                            request.timeoutSeconds()
                    );
                }));
        server.createContext("/v1/mappings/get", exchange ->
                handle(exchange, "POST", () -> {
                    MappingGetRequest request = readRequired(exchange, MappingGetRequest.class);
                    return coordinator.mappingGet(
                            request.scenarioId(),
                            request.mapReference(),
                            request.key(),
                            request.timeoutSeconds()
                    );
                }));
        server.createContext("/v1/mappings/put", exchange ->
                handle(exchange, "POST", () -> {
                    MappingPutRequest request = readRequired(exchange, MappingPutRequest.class);
                    return coordinator.mappingPut(
                            request.scenarioId(),
                            request.mapReference(),
                            request.key(),
                            request.value(),
                            request.timeoutSeconds()
                    );
                }));
        server.createContext("/v1/mappings/resolve", exchange ->
                handle(exchange, "POST", () -> {
                    MappingResolveRequest request = readRequired(exchange, MappingResolveRequest.class);
                    return coordinator.mappingResolve(
                            request.scenarioId(),
                            request.input(),
                            request.timeoutSeconds()
                    );
                }));
        server.createContext("/v1/mappings/snapshot", exchange ->
                handle(exchange, "POST", () -> {
                    MappingSnapshotRequest request = readRequired(exchange, MappingSnapshotRequest.class);
                    return coordinator.mappingSnapshot(
                            request.scenarioId(),
                            request.mapReference(),
                            request.timeoutSeconds()
                    );
                }));
        server.createContext("/v1/mappings/restore", exchange ->
                handle(exchange, "POST", () -> {
                    MappingRestoreRequest request = readRequired(exchange, MappingRestoreRequest.class);
                    return coordinator.mappingRestore(
                            request.scenarioId(),
                            request.snapshot(),
                            request.timeoutSeconds()
                    );
                }));
        server.createContext("/v1/browser/page", exchange ->
                handle(exchange, "POST", () -> {
                    BrowserEvidenceRequest request = readRequired(exchange, BrowserEvidenceRequest.class);
                    return coordinator.browserPage(request.scenarioId(), request.timeoutSeconds());
                }));
        server.createContext("/v1/browser/screenshot", exchange ->
                handle(exchange, "POST", () -> {
                    BrowserEvidenceRequest request = readRequired(exchange, BrowserEvidenceRequest.class);
                    return coordinator.browserScreenshot(request.scenarioId(), request.timeoutSeconds());
                }));
    }

    private void handle(
            HttpExchange exchange,
            String expectedMethod,
            RequestAction action
    ) throws IOException {
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

            Object result;
            try {
                result = action.run();
            } catch (IllegalArgumentException | IOException failure) {
                sendJson(exchange, 400, Map.of(
                        "error", "invalid_request",
                        "message", safeMessage(failure)
                ));
                return;
            } catch (Throwable failure) {
                sendJson(exchange, 500, Map.of(
                        "error", "bridge_failure",
                        "message", safeMessage(failure)
                ));
                return;
            }

            sendJson(exchange, 200, result);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }

        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] supplied = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, supplied);
    }

    private <T> T readRequired(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] body = readBody(exchange);
        if (body.length == 0) {
            throw new IllegalArgumentException("Request body is required.");
        }
        return json.readValue(body, type);
    }

    private <T> T readOptional(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] body = readBody(exchange);
        return body.length == 0 ? null : json.readValue(body, type);
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw new IllegalArgumentException(
                    "Bridge request body exceeds " + MAX_REQUEST_BYTES + " bytes."
            );
        }
        return body;
    }

    private String queryParameter(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return null;
        }

        for (String parameter : raw.split("&")) {
            int separator = parameter.indexOf('=');
            String rawName = separator < 0 ? parameter : parameter.substring(0, separator);
            if (!name.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                continue;
            }
            String rawValue = separator < 0 ? "" : parameter.substring(separator + 1);
            return URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
        }
        return null;
    }

    private Long longQueryParameter(HttpExchange exchange, String name) {
        String value = queryParameter(exchange, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be a whole number.", failure);
        }
    }

    private Integer intQueryParameter(HttpExchange exchange, String name) {
        String value = queryParameter(exchange, name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(name + " must be a whole number.", failure);
        }
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
                Files.move(
                        temporary,
                        descriptorFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException atomicFailure) {
                Files.move(
                        temporary,
                        descriptorFile,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
            throw new IllegalStateException(
                    "Could not publish Pickleball Studio bridge descriptor: " + descriptorFile,
                    failure
            );
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        ControlRuntime.removeObserver(coordinator);
        ControlRuntime.removeObserver(eventRecorder);
        coordinator.close();
        eventRecorder.close();
        server.stop(0);
        executor.shutdownNow();

        try {
            Files.deleteIfExists(descriptorFile);
        } catch (IOException ignored) {
        }
    }

    private static String safeMessage(Throwable failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    @FunctionalInterface
    private interface RequestAction {
        Object run() throws Exception;
    }

    private record PauseRequest(
            String scenarioId,
            Integer waitSeconds,
            Integer leaseSeconds
    ) {
    }

    private record ResumeRequest(String scenarioId) {
    }

    private record ExecuteStepRequest(
            String scenarioId,
            String text,
            String argument,
            Integer timeoutSeconds
    ) {
    }

    private record MappingGetRequest(
            String scenarioId,
            String mapReference,
            String key,
            Integer timeoutSeconds
    ) {
    }

    private record MappingPutRequest(
            String scenarioId,
            String mapReference,
            String key,
            Object value,
            Integer timeoutSeconds
    ) {
    }

    private record MappingResolveRequest(
            String scenarioId,
            String input,
            Integer timeoutSeconds
    ) {
    }

    private record MappingSnapshotRequest(
            String scenarioId,
            String mapReference,
            Integer timeoutSeconds
    ) {
    }

    private record MappingRestoreRequest(
            String scenarioId,
            ControlBridgeMappingSnapshot snapshot,
            Integer timeoutSeconds
    ) {
    }

    private record BrowserEvidenceRequest(
            String scenarioId,
            Integer timeoutSeconds
    ) {
    }
}
