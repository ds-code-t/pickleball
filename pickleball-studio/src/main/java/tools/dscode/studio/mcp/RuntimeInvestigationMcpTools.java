package tools.dscode.studio.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.dscode.studio.runtime.RuntimeBreakpoint;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.runtime.RuntimeElementInspectionResult;
import tools.dscode.studio.runtime.RuntimeServiceCallResult;

import java.util.List;
import java.util.Map;

public final class RuntimeInvestigationMcpTools {
    private final RuntimeBridgeService runtimeBridge;

    public RuntimeInvestigationMcpTools(RuntimeBridgeService runtimeBridge) {
        this.runtimeBridge = runtimeBridge;
    }

    @Tool(name = "runtime_element_inspect", description = "Resolve and inspect elements through Pickleball's own element category/text/operation dictionary. This is read-only and does not accept raw CSS/XPath selectors or create a browser.")
    public RuntimeElementInspectionResult elementInspect(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Optional scenario id from runtime_scenarios.", required = false) String scenarioId,
            @ToolParam(description = "Pickleball element category, for example Button, Field, Link, or Text.") String category,
            @ToolParam(description = "Optional Pickleball element text/value used by category resolution.", required = false) String text,
            @ToolParam(description = "Optional Pickleball operation such as equals, contains, starts with, ends with, matches, gt/gte/lt/lte. DEFAULT uses the category default.", required = false) String operation,
            @ToolParam(description = "Maximum matched elements to return evidence for. Defaults to 20; maximum 100.", required = false) Integer maxElements,
            @ToolParam(description = "Seconds to wait for the scenario-thread command. Defaults to 60; maximum 3600.", required = false) Integer timeoutSeconds
    ) {
        return runtimeBridge.elementInspect(sessionId, runtimeId, scenarioId, category, text, operation, maxElements, timeoutSeconds);
    }

    @Tool(name = "runtime_service_call", description = "Execute an existing Pickleball service-call definition by the same selector accepted by CALL:, returning bounded REQUEST/CONFIGURATION/RESPONSE evidence. Failures are retry-friendly and do not by themselves fail the paused scenario.")
    public RuntimeServiceCallResult serviceCall(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Optional scenario id from runtime_scenarios.", required = false) String scenarioId,
            @ToolParam(description = "Existing Pickleball service-call selector, for example %health-full-url or another CALL: selector.") String selector,
            @ToolParam(description = "Seconds to wait for the scenario-thread command. Defaults to 60; maximum 3600.", required = false) Integer timeoutSeconds
    ) {
        return runtimeBridge.serviceCall(sessionId, runtimeId, scenarioId, selector, timeoutSeconds);
    }

    @Tool(name = "runtime_breakpoint_add", description = "Add a temporary semantic breakpoint. A matching hook pauses the selected scenario with a finite lease; filters are literal and may be combined. At least one filter is required.")
    public RuntimeBreakpoint breakpointAdd(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Optional scenario id to restrict the breakpoint.", required = false) String scenarioId,
            @ToolParam(description = "Optional ControlHook name such as BEFORE_STEP, BEFORE_DOM_ACCESS, BEFORE_DRIVER_COMMAND, BEFORE_MAPPING_RESOLVE, or BEFORE_SERVICE_CALL.", required = false) String hook,
            @ToolParam(description = "Optional literal substring required in the hook signature.", required = false) String signatureContains,
            @ToolParam(description = "Optional literal substring required in the current step text.", required = false) String stepContains,
            @ToolParam(description = "Optional literal substring required in the current phrase text.", required = false) String phraseContains,
            @ToolParam(description = "Remove the breakpoint after its first hit. Defaults to false.", required = false) Boolean oneShot,
            @ToolParam(description = "Pause lease seconds. Defaults to 120; maximum 3600.", required = false) Integer leaseSeconds
    ) {
        return runtimeBridge.addBreakpoint(sessionId, runtimeId, scenarioId, hook, signatureContains, stepContains, phraseContains, oneShot, leaseSeconds);
    }

    @Tool(name = "runtime_breakpoints", description = "List current semantic breakpoints and their bounded hit metadata for one live runtime.")
    public List<RuntimeBreakpoint> breakpoints(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId
    ) {
        return runtimeBridge.breakpoints(sessionId, runtimeId);
    }

    @Tool(name = "runtime_breakpoint_remove", description = "Remove one semantic breakpoint by id.")
    public Map<String, Boolean> breakpointRemove(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Breakpoint id returned by runtime_breakpoint_add or runtime_breakpoints.") String breakpointId
    ) {
        return Map.of("removed", runtimeBridge.removeBreakpoint(sessionId, runtimeId, breakpointId));
    }

    @Tool(name = "runtime_breakpoints_clear", description = "Remove all semantic breakpoints from one live runtime.")
    public Map<String, Integer> breakpointsClear(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId
    ) {
        return Map.of("removed", runtimeBridge.clearBreakpoints(sessionId, runtimeId));
    }
}
