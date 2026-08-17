package tools.dscode.studio.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.runtime.RuntimeControlResult;
import tools.dscode.studio.runtime.RuntimeMappingSnapshotResult;
import tools.dscode.studio.runtime.RuntimeMappingSnapshotSummary;

import java.util.List;

/** Thin MCP adapter over Studio-owned live mapping snapshot services. */
public final class RuntimeMappingMcpTools {
    private final RuntimeBridgeService runtimeBridge;

    public RuntimeMappingMcpTools(RuntimeBridgeService runtimeBridge) {
        this.runtimeBridge = runtimeBridge;
    }

    @Tool(
            name = "runtime_mapping_snapshot",
            description = "Capture one live Pickleball NodeMap as materialized JSON state and retain it in Studio under a bounded session-scoped snapshot id. Ordinary NodeMap snapshots are restorable; specialized live NodeMap subclasses are inspection-only."
    )
    public RuntimeMappingSnapshotResult mappingSnapshot(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Optional scenario id from runtime_scenarios. Required when parallel scenarios are otherwise ambiguous.", required = false)
            String scenarioId,
            @ToolParam(description = "Live NodeMap reference such as OVERRIDE, RUN, STEP, PARENT.STEP, or SCENARIO.") String mapReference,
            @ToolParam(description = "Seconds to wait for the scenario-thread command. Defaults to 60; maximum 3600.", required = false)
            Integer timeoutSeconds
    ) {
        return runtimeBridge.mappingSnapshot(
                sessionId,
                runtimeId,
                scenarioId,
                mapReference,
                timeoutSeconds
        );
    }

    @Tool(
            name = "runtime_mapping_snapshots",
            description = "List compact metadata for mapping snapshots currently retained by Studio. Snapshot history is bounded to 50 per runtime session and is not persisted across Studio restarts."
    )
    public List<RuntimeMappingSnapshotSummary> mappingSnapshots(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Optional runtime id filter.", required = false) String runtimeId,
            @ToolParam(description = "Optional scenario id filter.", required = false) String scenarioId,
            @ToolParam(description = "Optional NodeMap reference filter.", required = false) String mapReference
    ) {
        return runtimeBridge.mappingSnapshots(
                sessionId,
                runtimeId,
                scenarioId,
                mapReference
        );
    }

    @Tool(
            name = "runtime_mapping_restore",
            description = "Deliberately restore a Studio-retained mapping snapshot into the exact captured runtime/scenario/map target. Restore overwrites the current values of that ordinary live NodeMap while preserving its object identity; inspection-only specialized snapshots are rejected."
    )
    public RuntimeControlResult mappingRestore(
            @ToolParam(description = "Session id that owns the snapshot.") String sessionId,
            @ToolParam(description = "Snapshot id returned by runtime_mapping_snapshot or runtime_mapping_snapshots.") String snapshotId,
            @ToolParam(description = "Seconds to wait for the scenario-thread command. Defaults to 60; maximum 3600.", required = false)
            Integer timeoutSeconds
    ) {
        return runtimeBridge.mappingRestore(sessionId, snapshotId, timeoutSeconds);
    }
}
