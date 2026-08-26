package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshot;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** MCP tool definitions that adapt the shared Workbench controller surface. */
final class WorkbenchMcpTools {
    private final WorkbenchServices services;
    private final ObjectMapper json;
    private final Map<String, ToolBinding> tools = new LinkedHashMap<>();

    WorkbenchMcpTools(WorkbenchServices services, ObjectMapper json) {
        this.services = services;
        this.json = json;
        register();
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> specifications = new ArrayList<>();
        for (ToolBinding binding : tools.values()) {
            specifications.add(specification(binding));
        }
        return List.copyOf(specifications);
    }

    List<String> names() {
        return List.copyOf(tools.keySet());
    }

    Object call(String name, Map<String, Object> arguments) {
        ToolBinding binding = tools.get(name);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown Workbench tool: " + name);
        }
        return WorkbenchCallContext.callAs(
                WorkbenchLeaseHolder.AGENT,
                () -> binding.action.apply(arguments == null ? Map.of() : arguments)
        );
    }

    private void register() {
        add("workbench_sync", "Synchronize the selected consumer project. Skips Maven/Gradle when Java/build/dependencies are unchanged; resources-only refresh when only features/config/data changed. Live buffer edits do not require this.", schema(Map.of()),
                args -> services.synchronize());
        add("workbench_sync_status", "Read the current Workbench synchronization manifest.", schema(Map.of()),
                args -> services.synchronizationStatus());
        add("workbench_worker_start", "Start the persistent interactive consumer worker.", schema(Map.of()),
                args -> services.startWorker());
        add("workbench_worker_restart", "Restart the worker in a fresh JVM without rebuilding.", schema(Map.of()),
                args -> services.restartWorker());
        add("workbench_worker_stop", "Stop the interactive worker cleanly.", schema(Map.of()),
                args -> services.stopWorker());
        add("workbench_worker_status", "Read the current interactive worker status.", schema(Map.of()),
                args -> services.workerStatus());

        add("workbench_request_control",
                "Request the Workbench live-control lease so this agent can test the live scenario while the human watches.",
                schema(Map.of("agentName", stringProperty("Display name shown in the Workbench banner.")), "agentName"),
                args -> services.requestControl(text(args, "agentName")));
        add("workbench_release_control",
                "Release the Workbench live-control lease back to the human.",
                schema(Map.of()),
                args -> services.releaseControl());
        add("workbench_set_current_action",
                "Update the watched-agent banner with what this agent is currently doing.",
                schema(Map.of("text", stringProperty("Short action text shown in the UI banner.")), "text"),
                args -> services.setCurrentAction(text(args, "text")));
        add("workbench_control_lease",
                "Read the current Workbench control-lease holder, banner action, and pending permission.",
                schema(Map.of()),
                args -> services.controlLeaseSnapshot());
        add("workbench_player_state",
                "Read the shared live scenario buffer, playhead, player state, and originating feature path if any.",
                schema(Map.of()),
                args -> services.playerState());
        add("workbench_player_replace_document",
                "Replace the live session buffer. Does not write the original .feature file.",
                schema(Map.of("text", stringProperty("Full live Gherkin document.")), "text"),
                args -> {
                    services.replaceLiveDocument(text(args, "text").lines().toList());
                    return services.playerState();
                });
        add("workbench_request_save",
                "Ask to copy the live scenario into the original .feature file. With a UI attached this waits for Allow/Deny and never writes on deny.",
                schema(Map.of()),
                args -> services.requestSave());

        add("workbench_execute_step", "Execute raw Gherkin in the paused live scenario. A FAILED result leaves the worker paused so you can inspect or retry; it is not an MCP error and does not stop the worker.",
                schema(Map.of(
                        "text", stringProperty("Gherkin step text."),
                        "argument", stringProperty("Optional DocString-style argument text.")
                ), "text"),
                args -> services.executeStep(text(args, "text"), optionalText(args, "argument")));

        add("workbench_mapping_get", "Read one value from a Pickleball Mapping.",
                schema(Map.of(
                        "mapReference", stringProperty("Mapping reference."),
                        "key", stringProperty("Mapping key.")
                ), "mapReference", "key"),
                args -> services.mappingGet(text(args, "mapReference"), text(args, "key")));
        add("workbench_mapping_put", "Write one value into a Pickleball Mapping.",
                schema(Map.of(
                        "mapReference", stringProperty("Mapping reference."),
                        "key", stringProperty("Mapping key."),
                        "value", Map.of("description", "JSON-compatible value to store.")
                ), "mapReference", "key", "value"),
                args -> services.mappingPut(text(args, "mapReference"), text(args, "key"), args.get("value")));
        add("workbench_mapping_resolve", "Resolve Pickleball mapping/template references in text.",
                schema(Map.of("input", stringProperty("Text to resolve.")), "input"),
                args -> services.mappingResolve(text(args, "input")));
        add("workbench_mapping_snapshot", "Snapshot one Mapping for later restoration.",
                schema(Map.of("mapReference", stringProperty("Mapping reference.")), "mapReference"),
                args -> services.mappingSnapshot(text(args, "mapReference")));
        add("workbench_mapping_restore", "Restore a previously returned Mapping snapshot.",
                schema(Map.of("snapshot", objectProperty("Snapshot returned by workbench_mapping_snapshot.")), "snapshot"),
                args -> services.mappingRestore(json.convertValue(args.get("snapshot"), ControlBridgeMappingSnapshot.class)));

        add("workbench_events", "Read semantic runtime events for the active scenario. Page with afterSequence and a small limit (default 100, max 500).",
                schema(Map.of(
                        "afterSequence", integerProperty("Return events after this sequence number."),
                        "limit", integerProperty("Maximum events to return.")
                )),
                args -> services.events(longValue(args, "afterSequence"), integer(args, "limit")));
        add("workbench_browser_page", "Read current browser page evidence.", schema(Map.of()),
                args -> services.browserPage());
        add("workbench_browser_screenshot", "Capture current browser screenshot evidence. Prefer workbench_browser_page or workbench_element_inspect unless the image itself is required.", schema(Map.of()),
                args -> services.browserScreenshot());
        add("workbench_element_inspect", "Inspect browser elements using Pickleball element vocabulary.",
                schema(Map.of(
                        "category", stringProperty("Optional Pickleball element category."),
                        "text", stringProperty("Optional visible/context text."),
                        "operation", stringProperty("Optional inspection operation."),
                        "maxElements", integerProperty("Optional maximum result count.")
                )),
                args -> services.elementInspect(
                        optionalText(args, "category"),
                        optionalText(args, "text"),
                        optionalText(args, "operation"),
                        integer(args, "maxElements")
                ));
        add("workbench_service_call", "Execute an existing Pickleball service-call selector and return evidence.",
                schema(Map.of("selector", stringProperty("Existing service-call selector, for example %health-full-url.")), "selector"),
                args -> services.serviceCall(text(args, "selector")));

        add("workbench_breakpoint_list", "List semantic breakpoints.", schema(Map.of()),
                args -> services.breakpoints());
        add("workbench_breakpoint_add", "Add a semantic runtime breakpoint.",
                schema(Map.of(
                        "hook", stringProperty("Control hook name, for example BEFORE_STEP."),
                        "signatureContains", stringProperty("Optional signature substring filter."),
                        "stepContains", stringProperty("Optional step substring filter."),
                        "phraseContains", stringProperty("Optional phrase substring filter."),
                        "oneShot", booleanProperty("Remove the breakpoint after its first match."),
                        "leaseSeconds", integerProperty("Finite breakpoint lease in seconds.")
                ), "hook"),
                args -> services.addBreakpoint(
                        text(args, "hook"),
                        optionalText(args, "signatureContains"),
                        optionalText(args, "stepContains"),
                        optionalText(args, "phraseContains"),
                        bool(args, "oneShot", false),
                        integer(args, "leaseSeconds")
                ));
        add("workbench_breakpoint_remove", "Remove one semantic breakpoint.",
                schema(Map.of("breakpointId", stringProperty("Breakpoint id.")), "breakpointId"),
                args -> Map.of("removed", services.removeBreakpoint(text(args, "breakpointId"))));
        add("workbench_breakpoint_clear", "Clear all semantic breakpoints.", schema(Map.of()),
                args -> Map.of("removed", services.clearBreakpoints()));

        add("workbench_step_override_list", "List Step Overrides in the active live scenario.", schema(Map.of()),
                args -> services.stepOverrides());
        add("workbench_step_override_compile", "Compile and install a scenario-scoped REPLACE/REGEX Step Override in the worker.",
                schema(Map.of(
                        "id", stringProperty("Stable override id; recompiling the id replaces it."),
                        "regex", stringProperty("Regular expression matched before ordinary Cucumber glue."),
                        "source", stringProperty("Java StepOverrideHandler source containing {{CLASS_NAME}}.")
                ), "id", "regex", "source"),
                args -> services.compileStepOverride(
                        text(args, "id"), text(args, "regex"), text(args, "source")
                ));
        add("workbench_step_override_remove", "Remove one Step Override from the active scenario.",
                schema(Map.of("id", stringProperty("Override id.")), "id"),
                args -> Map.of("removed", services.removeStepOverride(text(args, "id"))));
        add("workbench_step_override_clear", "Clear all Step Overrides from the active scenario.", schema(Map.of()),
                args -> Map.of("removed", services.clearStepOverrides()));

        add("workbench_diagnostic_catalog",
                "Read reports/diagnostic-runs/run-catalog.json as sparse JSON. Do not glob the diagnostic tree.",
                schema(Map.of()),
                args -> services.diagnosticCatalog());
        add("workbench_diagnostic_run",
                "Read one run's run-index.json and clusters.json. Does not return events, traces, or screenshots.",
                schema(Map.of("runId", stringProperty("Diagnostic run directory name from the catalog.")), "runId"),
                args -> services.diagnosticRun(text(args, "runId")));
        add("workbench_diagnostic_summary",
                "Read one scenario summary.json. Does not return events.jsonl, traces, or PNG bytes.",
                schema(Map.of(
                        "runId", stringProperty("Diagnostic run directory name from the catalog."),
                        "scenarioId", stringProperty("Scenario directory name under that run.")
                ), "runId", "scenarioId"),
                args -> services.diagnosticScenarioSummary(text(args, "runId"), text(args, "scenarioId")));
        add("workbench_investigation_emit",
                "Write .pickleball/investigations/<id>/{investigation.json,report.html} from investigation JSON. Returns the relative report.html path only. Does not copy the diagnostic pack or embed PNG bytes.",
                schema(Map.of(
                        "investigation", Map.of(
                                "type", "object",
                                "description", "Investigation JSON object. Source of truth written to investigation.json.",
                                "additionalProperties", true
                        )
                ), "investigation"),
                args -> services.emitInvestigation(investigationObject(args.get("investigation"))));
    }

    private void add(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, Object> action
    ) {
        tools.put(name, new ToolBinding(name, description, inputSchema, action));
    }

    private McpServerFeatures.SyncToolSpecification specification(ToolBinding binding) {
        McpSchema.Tool tool = McpSchema.Tool.builder(binding.name, binding.inputSchema)
                .description(binding.description)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(binding.name, request.arguments()))
                .build();
    }

    private McpSchema.CallToolResult invoke(String name, Map<String, Object> arguments) {
        try {
            Object value = call(name, arguments);
            return result(value, false);
        } catch (RuntimeException failure) {
            return result(Map.of(
                    "error", failure.getClass().getSimpleName(),
                    "message", failure.getMessage() == null ? failure.toString() : failure.getMessage()
            ), true);
        }
    }

    private McpSchema.CallToolResult result(Object value, boolean error) {
        try {
            String text = json.writeValueAsString(value);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(McpSchema.TextContent.builder(text).build()))
                    .isError(error)
                    .build();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not serialize Workbench MCP tool result.", failure);
        }
    }

    private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) schema.put("required", List.of(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProperty(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> objectProperty(String description) {
        return Map.of("type", "object", "description", description);
    }

    private static String text(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank.");
        }
        return text;
    }

    private static String optionalText(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Integer integer(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longValue(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static boolean bool(Map<String, Object> args, String name, boolean defaultValue) {
        Object value = args.get(name);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> investigationObject(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("investigation must be a JSON object.");
        }
        Object parsed = value;
        if (parsed instanceof String text) {
            if (text.isBlank()) {
                throw new IllegalArgumentException("investigation must be a JSON object.");
            }
            try {
                parsed = json.readValue(text, LinkedHashMap.class);
            } catch (Exception failure) {
                throw new IllegalArgumentException("investigation must be a JSON object.");
            }
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("investigation must be a JSON object.");
        }
        return json.convertValue(parsed, LinkedHashMap.class);
    }

    private record ToolBinding(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, Object> action
    ) {
    }
}
