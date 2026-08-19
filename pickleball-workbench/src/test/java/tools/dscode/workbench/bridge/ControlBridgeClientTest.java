package tools.dscode.workbench.bridge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.bridge.ControlBridgeBootstrap;
import tools.dscode.control.bridge.ControlBridgeBreakpoint;
import tools.dscode.control.bridge.ControlBridgeDescriptor;
import tools.dscode.control.bridge.ControlBridgeStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlBridgeClientTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void stopBridge() {
        ControlBridgeBootstrap.stop();
    }

    @Test
    void clientUsesThePublishedPickleballBridgeContract() throws Exception {
        String token = "phase-2-token";
        ControlBridgeDescriptor descriptor =
                ControlBridgeBootstrap.start(tempDir, "phase-2-session", token, false);

        Path descriptorFile;
        try (var files = Files.list(tempDir)) {
            descriptorFile = files
                    .filter(path -> path.getFileName().toString().startsWith("runtime-"))
                    .findFirst()
                    .orElseThrow();
        }

        ControlBridgeClient client = ControlBridgeClient.fromDescriptor(descriptorFile, token);
        ControlBridgeStatus status = client.status();

        assertEquals(descriptor.runtimeId(), client.descriptor().runtimeId());
        assertEquals(descriptor.runtimeId(), status.runtimeId());
        assertEquals(1, status.protocolVersion());
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
        assertEquals(
                "UNAVAILABLE",
                client.mappingResolve(missingScenario, "<missing>", 1).status()
        );
        assertEquals(
                "UNAVAILABLE",
                client.mappingSnapshot(missingScenario, "OVERRIDE", 1).status()
        );
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
                        """
                        import tools.dscode.control.override.StepOverrideContext;
                        import tools.dscode.control.override.StepOverrideHandler;
                        public final class {{CLASS_NAME}} implements StepOverrideHandler {
                            public Object execute(StepOverrideContext context) {
                                return null;
                            }
                        }
                        """,
                        1
                ).status()
        );
        assertFalse(client.removeStepOverride(missingScenario, "missing"));
        assertEquals(0, client.clearStepOverrides(missingScenario));

        ControlBridgeBreakpoint breakpoint = client.addBreakpoint(
                null, "AFTER_STEP", null, "phase-2-marker", null, true, 30
        );
        assertTrue(
                client.breakpoints().stream()
                        .anyMatch(candidate -> candidate.breakpointId().equals(breakpoint.breakpointId()))
        );
        assertTrue(client.removeBreakpoint(breakpoint.breakpointId()));
        assertEquals(0, client.clearBreakpoints());
    }

    @Test
    void wrongBearerTokenIsRejected() {
        ControlBridgeDescriptor descriptor =
                ControlBridgeBootstrap.start(tempDir, "phase-2-session", "correct-token", false);
        ControlBridgeClient client = new ControlBridgeClient(descriptor, "wrong-token");

        IllegalStateException failure =
                assertThrows(IllegalStateException.class, client::status);

        assertTrue(failure.getMessage().contains("HTTP 401"));
    }

    @Test
    void descriptorMustUseTheSupportedLoopbackProtocol() {
        ControlBridgeDescriptor nonLoopback = descriptor("localhost", 1);
        ControlBridgeDescriptor wrongProtocol = descriptor("127.0.0.1", 2);

        IllegalArgumentException hostFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ControlBridgeClient(nonLoopback, "token").status()
        );
        IllegalArgumentException protocolFailure = assertThrows(
                IllegalArgumentException.class,
                () -> new ControlBridgeClient(wrongProtocol, "token").status()
        );

        assertTrue(hostFailure.getMessage().contains("not loopback-bound"));
        assertTrue(protocolFailure.getMessage().contains("Unsupported control bridge protocol"));
    }

    @Test
    void canonicalEnvironmentNamesAreWorkbenchNeutral() {
        assertEquals(
                "PKB_CONTROL_BRIDGE_SESSION_DIR",
                ControlBridgeBootstrap.ENV_SESSION_DIR
        );
        assertEquals(
                "PKB_CONTROL_BRIDGE_SESSION_ID",
                ControlBridgeBootstrap.ENV_SESSION_ID
        );
        assertEquals(
                "PKB_CONTROL_BRIDGE_TOKEN",
                ControlBridgeBootstrap.ENV_TOKEN
        );
        assertEquals(
                "PKB_CONTROL_BRIDGE_PAUSE_FIRST_SCENARIO",
                ControlBridgeBootstrap.ENV_PAUSE_FIRST_SCENARIO
        );
    }

    private static ControlBridgeDescriptor descriptor(String host, int protocolVersion) {
        return new ControlBridgeDescriptor(
                protocolVersion,
                "session",
                "runtime",
                ProcessHandle.current().pid(),
                host,
                1,
                "2026-08-18T00:00:00Z",
                List.of("status")
        );
    }
}
