package tools.dscode.studio.gui;

import tools.dscode.studio.runtime.RuntimeBridgeDescriptor;
import tools.dscode.studio.runtime.RuntimeBridgeStatus;
import tools.dscode.studio.runtime.RuntimeScenarioStatus;

import java.util.List;

public record RuntimeDesktopState(
        String sessionId,
        List<RuntimeBridgeDescriptor> runtimes,
        String selectedRuntimeId,
        RuntimeBridgeStatus runtimeStatus,
        List<RuntimeScenarioStatus> scenarios
) {
    public RuntimeDesktopState {
        runtimes = List.copyOf(runtimes);
        scenarios = List.copyOf(scenarios);
    }
}
