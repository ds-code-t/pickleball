package tools.dscode.workbench.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgeClientTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String expectedToken;
    private final List<ControlBridgeBreakpoint> breakpoints = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void clientUsesOnlyTheNeutralWireContract() throws Exception {
        String token = "controller-only-token";
        ControlBridgeDescriptor descriptor = startProtocolServer(token);
        Path descriptorFile = tempDir.resolve("runtime-" + descriptor.runtimeId() + ".json");
        JSON.writeValue(descriptorFile.toFile(), descriptor);

        ControlBridgeClient client = ControlBridgeClient.fromDescriptor(descriptorFile, token);
        ControlBridgeStatus status = client.status();

        assertEquals(descriptor.runtimeId(), client.descriptor().runtimeId());
        assertEquals(descriptor.runtimeId(), status.runtimeId());
        assertEquals(ControlProtocol.CURRENT_VERSION, status.protocolVersion());
        assertEquals("127.0.0.1", descriptor.host());
        assertTrue(client.scenarios().isEmpty());
        assertTrue(client.events(null, 0L, 10).events().isEmpty());
        assertTrue(client.breakpoints().isEmpty());
        assertTrue(descriptor.capabilities().contains("step_overrides"));
        assertTrue(descriptor.capabilities().contains("step_override_compile"));

        String missingScenario = UUID.randomUUID().toString();
        assertEquals("UNAVAILABLE", client.pause(missingScenario, 1, 30).status());
        assertEquals("UNAVAILABLE", client.resume(missingScenario).status());
        assertEquals(
                "UNAVAILABLE",
                client.executeStep(missingScenario, "CONTROL API TEST STEP", "", 1).status()
        );
        assertEquals(
                "UNAVAILABLE",
                client.mappingGet(missingScenario, "OVERRIDE", "missing", 1).status()
        );
        assertEquals(
                "UNAVAILABLE",
                client.mappingPut(missingScenario, "OVERRIDE", "missing", "value", 1).status()
        );
        assertEquals("UNAVAILABLE", client.mappingResolve(missingScenario, "<missing>", 1).status());
        assertEquals("UNAVAILABLE", client.mappingSnapshot(missingScenario, "OVERRIDE", 1).status());
        assertEquals("UNAVAILABLE", client.browserPage(missingScenario, 1).status());
        assertEquals("UNAVAILABLE", client.browserScreenshot(missingScenario, 1).status());
        assertEquals(
                "UNAVAILABLE",
                client.elementInspect(missingScenario, "Button", null, "DEFAULT", 5, 1).status()
        );
        assertEquals(
                "UNAVAILABLE",
                client.serviceCall(missingScenario, "%health-full-url", 1).status()
        );

        assertTrue(client.stepOverrides(missingScenario).isEmpty());
        assertEquals(
                "UNAVAILABLE",
                client.compileStepOverride(
                        missingScenario,
                        "missing",
                        "^MISSING$",
                        "public final class {{CLASS_NAME}} {}",
                        1
                ).status()
        );
        assertFalse(client.removeStepOverride(missingScenario, "missing"));
        assertEquals(0, client.clearStepOverrides(missingScenario));

        ControlBridgeBreakpoint breakpoint = client.addBreakpoint(
                null, "AFTER_STEP", null, "controller-marker", null, true, 30
        );
        assertTrue(client.breakpoints().stream()
                .anyMatch(candidate -> candidate.breakpointId().equals(breakpoint.breakpointId())));
        assertTrue(client.removeBreakpoint(breakpoint.breakpointId()));
        assertEquals(0, client.clearBreakpoints());

        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("tools.dscode.testengine.DynamicSuiteBootstrap")
        );
    }

    @Test
    void wrongBearerTokenIsRejected() throws Exception {
        ControlBridgeDescriptor descriptor = startProtocolServer("correct-token");
        ControlBridgeClient client = new ControlBridgeClient(descriptor, "wrong-token");

        IllegalStateException failure = assertThrows(IllegalStateException.class, client::status);

        assertTrue(failure.getMessage().contains("HTTP 401"));
    }

    @Test
    void descriptorRequiresLoopbackCompatibleVersionAndCapabilities() {
        ControlBridgeDescriptor nonLoopback = descriptor(
                "localhost",
                ControlProtocol.CURRENT_VERSION,
                ControlProtocol.MINIMUM_COMPATIBLE_VERSION,
                ControlProtocol.WORKER_CAPABILITIES
        );
        ControlBridgeDescriptor wrongProtocol = descriptor(
                "127.0.0.1",
                1,
                1,
                ControlProtocol.WORKER_CAPABILITIES
        );
        ControlBridgeDescriptor missingCapability = descriptor(
                "127.0.0.1",
                ControlProtocol.CURRENT_VERSION,
                ControlProtocol.MINIMUM_COMPATIBLE_VERSION,
                List.of("status")
        );

        IllegalArgumentException hostFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ControlBridgeClient(nonLoopback, "token")
        );
        IllegalArgumentException protocolFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ControlBridgeClient(wrongProtocol, "token")
        );
        IllegalArgumentException capabilityFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ControlBridgeClient(missingCapability, "token")
        );

        assertTrue(hostFailure.getMessage().contains("not loopback-bound"));
        assertTrue(protocolFailure.getMessage().contains("Incompatible control bridge protocol"));
        assertTrue(capabilityFailure.getMessage().contains("missing required Workbench capabilities"));
    }

    @Test
    void canonicalEnvironmentAndWorkerContractsAreControllerNeutral() {
        assertEquals("PKB_CONTROL_BRIDGE_SESSION_DIR", ControlProtocol.SESSION_DIRECTORY_ENV);
        assertEquals("PKB_CONTROL_BRIDGE_SESSION_ID", ControlProtocol.SESSION_ID_ENV);
        assertEquals("PKB_CONTROL_BRIDGE_TOKEN", ControlProtocol.SESSION_TOKEN_ENV);
        assertEquals(
                "PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO",
                ControlProtocol.PAUSE_FIRST_SCENARIO_ENV
        );
        assertEquals(
                "tools.dscode.testengine.WorkbenchWorkerMain",
                ControlProtocol.WORKER_MAIN_CLASS
        );
    }

    private ControlBridgeDescriptor startProtocolServer(String token) throws IOException {
        expectedToken = token;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        return descriptor(
                "127.0.0.1",
                ControlProtocol.CURRENT_VERSION,
                ControlProtocol.MINIMUM_COMPATIBLE_VERSION,
                ControlProtocol.WORKER_CAPABILITIES
        );
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!("Bearer " + expectedToken).equals(
                    exchange.getRequestHeaders().getFirst("Authorization")
            )) {
                send(exchange, 401, java.util.Map.of("error", "unauthorized"));
                return;
            }

            String path = exchange.getRequestURI().getPath();
            Object response;
            if ("/v1/status".equals(path)) {
                response = status();
            } else if ("/v1/scenarios".equals(path)) {
                response = List.of();
            } else if ("/v1/events".equals(path)) {
                response = new ControlBridgeEventPage(List.of(), 0, 1, 0, false, false);
            } else if ("/v1/breakpoints".equals(path)) {
                response = List.copyOf(breakpoints);
            } else if ("/v1/breakpoints/add".equals(path)) {
                ControlBridgeRequests.BreakpointAddRequest request = read(
                        exchange,
                        ControlBridgeRequests.BreakpointAddRequest.class
                );
                ControlBridgeBreakpoint breakpoint = new ControlBridgeBreakpoint(
                        "bp-1", request.scenarioId(), request.hook(), request.signatureContains(),
                        request.stepContains(), request.phraseContains(),
                        Boolean.TRUE.equals(request.oneShot()),
                        request.leaseSeconds() == null ? 120 : request.leaseSeconds(),
                        0, null, null
                );
                breakpoints.add(breakpoint);
                response = breakpoint;
            } else if ("/v1/breakpoints/remove".equals(path)) {
                ControlBridgeRequests.BreakpointIdRequest request = read(
                        exchange,
                        ControlBridgeRequests.BreakpointIdRequest.class
                );
                response = new ControlBridgeResponses.Removal(
                        breakpoints.removeIf(value -> value.breakpointId().equals(request.breakpointId()))
                );
            } else if ("/v1/breakpoints/clear".equals(path)) {
                int removed = breakpoints.size();
                breakpoints.clear();
                response = new ControlBridgeResponses.ClearResult(removed);
            } else if ("/v1/step-overrides".equals(path)) {
                response = List.of();
            } else if ("/v1/step-overrides/compile".equals(path)) {
                read(exchange, ControlBridgeRequests.StepOverrideCompileRequest.class);
                response = new ControlBridgeStepOverrideResult(
                        "UNAVAILABLE", null, unavailableError(), status()
                );
            } else if ("/v1/step-overrides/remove".equals(path)) {
                read(exchange, ControlBridgeRequests.StepOverrideIdRequest.class);
                response = new ControlBridgeResponses.Removal(false);
            } else if ("/v1/step-overrides/clear".equals(path)) {
                read(exchange, ControlBridgeRequests.StepOverrideScenarioRequest.class);
                response = new ControlBridgeResponses.ClearResult(0);
            } else {
                response = unavailableResponse(path, exchange);
            }
            send(exchange, 200, response);
        }
    }

    private Object unavailableResponse(String path, HttpExchange exchange) throws IOException {
        if (path.startsWith("/v1/mappings/get")
                || path.startsWith("/v1/mappings/put")
                || path.startsWith("/v1/mappings/resolve")) {
            exchange.getRequestBody().readAllBytes();
            return new ControlBridgeValueResult("UNAVAILABLE", null, unavailableError(), status());
        }
        if (path.startsWith("/v1/mappings/snapshot")) {
            exchange.getRequestBody().readAllBytes();
            return new ControlBridgeMappingSnapshotResult(
                    "UNAVAILABLE", null, unavailableError(), status()
            );
        }
        if (path.startsWith("/v1/browser/page")) {
            exchange.getRequestBody().readAllBytes();
            return new ControlBridgeBrowserPageResult(
                    "UNAVAILABLE", null, unavailableError(), status()
            );
        }
        if (path.startsWith("/v1/browser/screenshot")) {
            exchange.getRequestBody().readAllBytes();
            return new ControlBridgeBrowserScreenshotResult(
                    "UNAVAILABLE", null, unavailableError(), status()
            );
        }
        if (path.startsWith("/v1/browser/elements")) {
            exchange.getRequestBody().readAllBytes();
            return new ControlBridgeElementInspectionResult(
                    "UNAVAILABLE", null, unavailableError(), status()
            );
        }
        if (path.startsWith("/v1/services/call")) {
            exchange.getRequestBody().readAllBytes();
            return new ControlBridgeServiceCallResult(
                    "UNAVAILABLE", null, unavailableError(), status()
            );
        }
        exchange.getRequestBody().readAllBytes();
        return new ControlBridgeCallResult("UNAVAILABLE", null, null, unavailableError(), status());
    }

    private <T> T read(HttpExchange exchange, Class<T> type) throws IOException {
        return JSON.readValue(exchange.getRequestBody(), type);
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private ControlBridgeStatus status() {
        return new ControlBridgeStatus(
                ControlProtocol.CURRENT_VERSION,
                "runtime",
                42L,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                ControlProtocol.WORKER_CAPABILITIES
        );
    }

    private static ControlBridgeError unavailableError() {
        return new ControlBridgeError("UNAVAILABLE", "No active scenario.", "");
    }

    private ControlBridgeDescriptor descriptor(
            String host,
            int protocolVersion,
            int minimumCompatibleVersion,
            List<String> capabilities
    ) {
        return new ControlBridgeDescriptor(
                protocolVersion,
                minimumCompatibleVersion,
                "session",
                "runtime",
                42L,
                host,
                server == null ? 1 : server.getAddress().getPort(),
                "2026-08-20T00:00:00Z",
                "2.1.9",
                tempDir.resolve("consumer-pickleball.jar").toString(),
                capabilities
        );
    }
}
