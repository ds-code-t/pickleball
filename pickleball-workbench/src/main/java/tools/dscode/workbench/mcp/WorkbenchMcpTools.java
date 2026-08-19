package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.dscode.control.bridge.ControlBridgeMappingSnapshot;
import tools.dscode.workbench.WorkbenchServices;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** MCP tool definitions that adapt the shared Workbench controller surface. */
final class WorkbenchMcpTools {
    private final WorkbenchServices services;
    private final ObjectMapper json;

    WorkbenchMcpTools(WorkbenchServices services, ObjectMapper json) {
        this.services = services;
        this.json = json;
    }

    List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        tools.add(tool("workbench_sync", "Synchronize the selected consumer project.", schema(Map.of()),
                args -> services.synchronize()));
        tools.add(tool("workbench_sync_status", "Read the current Workbench synchronization manifest.", schema(Map.of()),
                args -> services.synchronizationStatus()));
        tools.add(tool("workbench_worker_start", "Start the persistent interactive consumer worker.", schema(Map.of()),
                args -> services.startWorker()));
        tools.add(tool("workbench_worker_restart", "Restart the worker in a fresh JVM without rebuilding.", schema(Map.of()),
                args -> services.restartWorker()));
        tools.add(tool("workbench_worker_stop", "Stop the interactive worker cleanly.", schema(Map.of()),
                args -> services.stopWorker()));
        tools.add(tool("workbench_worker_status", "Read the current interactive worker status.", schema(Map.of()),
                args -> services.workerStatus()));

        tools.add(tool("workbench_execute_step", "Execute raw Gherkin in the paused live scenario.",
                schema(Map.of(
                        "text", stringProperty("Gherkin step text."),
                        "argument", stringProperty("Optional DocString-style argument text.")
                ), "text"),
                args -> services.executeStep(text(args, "text"), optionalText(args, "argument"))));

        tools.add(tool("workbench_mapping_get", "Read one value from a Pickleball Mapping.",
                schema(Map.of(
                        "mapReference", stringProperty("Mapping reference."),
                        "key", stringProperty("Mapping key.")
                ), "mapReference", "key"),
                args -> services.mappingGet(text(args, "mapReference"), text(args, "key"))));
        tools.add(tool("workbench_mapping_put", "Write one value into a Pickleball Mapping.",
                schema(Map.of(
                        "mapReference", stringProperty("Mapping reference."),
                        "key", stringProperty("Mapping key."),
                        "value", Map.of("description", "JSON-compatible value to store.")
                ), "mapReference", "key", "value"),
                args -> services.mappingPut(text(args, "mapReference"), text(args, "key"), args.get("value"))));
        tools.add(tool("workbench_mapping_resolve", "Resolve Pickleball mapping/template references in text.",
                schema(Map.of("input", stringProperty("Text to resolve.")), "input"),
                args -> services.mappingResolve(text(args, "input"))));
        tools.add(tool("workbench_mapping_snapshot", "Snapshot one Mapping for later restoration.",
                schema(Map.of("mapReference", stringProperty("Mapping reference.")), "mapReference"),
                args -> services.mappingSnapshot(text(args, "mapReference"))));
        tools.add(tool("workbench_mapping_restore", "Restore a previously returned Mapping snapshot.",
                schema(Map.of("snapshot", objectProperty("Snapshot returned by workbench_mapping_snapshot.")), "snapshot"),
                args -> services.mappingRestore(json.convertValue(args.get("snapshot"), ControlBridgeMappingSnapshot.class))));

        tools.add(tool("workbench_events", "Read semantic runtime events for the active scenario.",
                schema(Map.of(
                        "afterSequence", integerProperty("Return events after this sequence number."),
                        "limit", integerProperty("Maximum events to return.")
                )),
                args -> services.events(longValue(args, "afterSequence"), integer(args, "limit"))));
        tools.add(tool("workbench_browser_page", "Read current browser page evidence.", schema(Map.of()),
                args -> services.browserPage()));
        tools.add(tool("workbench_browser_screenshot", "Capture current browser screenshot evidence.", schema(Map.of()),
                args -> services.browserScreenshot()));
        tools.add(tool("workbench_element_inspect", "Inspect browser elements using Pickleball element vocabulary.",
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
                )));
        tools.add(tool("workbench_service_call", "Execute an existing Pickleball service-call selector and return evidence.",
                schema(Map.of("selector", stringProperty("Existing service-call selector, for example %health-full-url.")), "selector"),
                args -> services.serviceCall(text(args, "selector"))));

        tools.add(tool("workbench_breakpoint_list", "List semantic breakpoints.", schema(Map.of()),
                args -> services.breakpoints()));
        tools.add(tool("workbench_breakpoint_add", "Add a semantic runtime breakpoint.",
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
                )));
        tools.add(tool("workbench_breakpoint_remove", "Remove one semantic breakpoint.",
                schema(Map.of("breakpointId", stringProperty("Breakpoint id.")), "breakpointId"),
                args -> Map.of("removed", services.removeBreakpoint(text(args, "breakpointId")))));
        tools.add(tool("workbench_breakpoint_clear", "Clear all semantic breakpoints.", schema(Map.of()),
                args -> Map.of("removed", services.clearBreakpoints())));

        tools.add(tool("workbench_step_override_list", "List Step Overrides in the active live scenario.", schema(Map.of()),
                args -> services.stepOverrides()));
        tools.add(tool("workbench_step_override_compile", "Compile and install a scenario-scoped REPLACE/REGEX Step Override in the worker.",
                schema(Map.of(
                        "id", stringProperty("Stable override id; recompiling the id replaces it."),
                        "regex", stringProperty("Regular expression matched before ordinary Cucumber glue."),
                        "source", stringProperty("Java StepOverrideHandler source containing {{CLASS_NAME}}.")
                ), "id", "regex", "source"),
                args -> services.compileStepOverride(
                        text(args, "id"), text(args, "regex"), text(args, "source")
                )));
        tools.add(tool("workbench_step_override_remove", "Remove one Step Override from the active scenario.",
                schema(Map.of("id", stringProperty("Override id.")), "id"),
                args -> Map.of("removed", services.removeStepOverride(text(args, "id")))));
        tools.add(tool("workbench_step_override_clear", "Clear all Step Overrides from the active scenario.", schema(Map.of()),
                args -> Map.of("removed", services.clearStepOverrides())));

        return List.copyOf(tools);
    }

    private McpServerFeatures.SyncToolSpecification tool(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, Object> action
    ) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, inputSchema)
                .description(description)
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(action, request.arguments()))
                .build();
    }

    private McpSchema.CallToolResult invoke(
            Function<Map<String, Object>, Object> action,
            Map<String, Object> arguments
    ) {
        try {
            Object value = action.apply(arguments == null ? Map.of() : arguments);
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
}
