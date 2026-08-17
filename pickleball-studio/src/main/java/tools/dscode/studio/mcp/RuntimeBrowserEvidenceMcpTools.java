package tools.dscode.studio.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.dscode.studio.runtime.RuntimeBridgeService;
import tools.dscode.studio.runtime.RuntimeBrowserPageResult;
import tools.dscode.studio.runtime.RuntimeBrowserScreenshotResult;

public final class RuntimeBrowserEvidenceMcpTools {
    private final RuntimeBridgeService runtimeBridge;

    public RuntimeBrowserEvidenceMcpTools(RuntimeBridgeService runtimeBridge) {
        this.runtimeBridge = runtimeBridge;
    }

    @Tool(
            name = "runtime_browser_page",
            description = "Read bounded URL, title, window, and DOM-source evidence from the browser already owned by a selected Pickleball scenario. Does not create or mutate a browser."
    )
    public RuntimeBrowserPageResult browserPage(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Optional scenario id from runtime_scenarios. Required when parallel scenarios are otherwise ambiguous.", required = false) String scenarioId,
            @ToolParam(description = "Seconds to wait for the scenario-thread command. Defaults to 60; maximum 3600.", required = false) Integer timeoutSeconds
    ) {
        return runtimeBridge.browserPage(sessionId, runtimeId, scenarioId, timeoutSeconds);
    }

    @Tool(
            name = "runtime_browser_screenshot",
            description = "Capture a PNG from the browser already owned by a selected Pickleball scenario and save it as a Studio evidence file. Does not create or mutate a browser."
    )
    public RuntimeBrowserScreenshotResult browserScreenshot(
            @ToolParam(description = "Session id returned by runtime_start.") String sessionId,
            @ToolParam(description = "Runtime id returned by runtime_list.") String runtimeId,
            @ToolParam(description = "Optional scenario id from runtime_scenarios. Required when parallel scenarios are otherwise ambiguous.", required = false) String scenarioId,
            @ToolParam(description = "Seconds to wait for the scenario-thread command. Defaults to 60; maximum 3600.", required = false) Integer timeoutSeconds
    ) {
        return runtimeBridge.browserScreenshot(sessionId, runtimeId, scenarioId, timeoutSeconds);
    }
}
