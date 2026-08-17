
package tools.dscode.studio.runtime;

import java.util.List;

public record RuntimeBridgeStatus(
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
    public RuntimeBridgeStatus {
        capabilities = List.copyOf(capabilities);
    }
}
