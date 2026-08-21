
package tools.dscode.control.protocol;

import java.util.List;

public record ControlBridgeStatus(
        int protocolVersion,
        String runtimeId,
        long pid,
        int activeScenarioCount,
        Long selectedScenarioThreadId,
        String scenarioId,
        String scenarioName,
        String stepText,
        String phraseText,
        String lastHook,
        String lastSignature,
        boolean paused,
        boolean pauseRequested,
        List<String> capabilities
) {
    public ControlBridgeStatus {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
