package tools.dscode.studio.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.runtime.RuntimeEventPage;

public final class RuntimeEvidenceMcpTools {
    private final RuntimeBridgeService runtimeBridge;

    public RuntimeEvidenceMcpTools(RuntimeBridgeService runtimeBridge) {
        this.runtimeBridge = runtimeBridge;
    }

    @Tool(
            name = "runtime_events",
            description = "Read a cursor page from one consumer runtime's bounded semantic hook history. The result reports retention gaps explicitly and may be filtered to one scenario id."
    )
    public RuntimeEventPage runtimeEvents(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Optional scenario id from runtime_scenarios. Omit to read events from all scenarios in runtime sequence order.", required = false)
            String scenarioId,
            @ToolParam(description = "Exclusive runtime event sequence cursor. Omit or use 0 for the oldest retained events.", required = false)
            Long afterSequence,
            @ToolParam(description = "Maximum returned events. Defaults to 100; maximum 500.", required = false)
            Integer limit
    ) {
        return runtimeBridge.events(
                sessionId,
                runtimeId,
                scenarioId,
                afterSequence,
                limit
        );
    }
}
