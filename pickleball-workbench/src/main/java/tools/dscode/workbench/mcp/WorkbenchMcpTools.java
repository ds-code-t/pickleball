package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import tools.dscode.control.protocol.AgentLog;
import tools.dscode.control.protocol.VerbTiers;
import tools.dscode.control.protocol.OutputFormatter;
import tools.dscode.control.protocol.SecretRedactor;
import tools.dscode.control.protocol.ControlBridgeMappingSnapshot;
import tools.dscode.control.protocol.DiagnosticQuery;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.store.StoreCliForwarder;
import tools.dscode.workbench.engine.DomEngineCliForwarder;
import tools.dscode.workbench.engine.EngineCliForwarder;
import tools.dscode.workbench.launch.ConsumerReadyForwarder;
import tools.dscode.workbench.nav.WorkbenchGoLink;
import tools.dscode.workbench.lease.WorkbenchCallContext;
import tools.dscode.workbench.lease.WorkbenchLeaseHolder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** MCP tool definitions that adapt the shared Workbench controller surface. */
final class WorkbenchMcpTools {
    private static final ThreadLocal<String> CURRENT_TOOL = new ThreadLocal<>();
    private static final ThreadLocal<WorkbenchMcpTools> CURRENT = new ThreadLocal<>();

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
            throw new IllegalArgumentException(
                    "error: Unknown Workbench tool: " + name + ".\n"
                            + "fix: Use tools/list. Do not invent workbench_* names.\n"
            );
        }
        String previous = CURRENT_TOOL.get();
        WorkbenchMcpTools previousTools = CURRENT.get();
        CURRENT_TOOL.set(name);
        CURRENT.set(this);
        try {
            return WorkbenchCallContext.callAs(
                    WorkbenchLeaseHolder.AGENT,
                    () -> binding.action.apply(arguments == null ? Map.of() : arguments)
            );
        } finally {
            if (previous == null) CURRENT_TOOL.remove();
            else CURRENT_TOOL.set(previous);
            if (previousTools == null) CURRENT.remove();
            else CURRENT.set(previousTools);
        }
    }

    private void register() {
        add("workbench_sync", "Synchronize the consumer project. Skips a rebuild when Java, the build, and dependencies are unchanged.", schema(Map.of()),
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
                schema(Map.of("agentName", stringProperty("Display name shown in the Workbench banner. Defaults to agent."))),
                args -> {
                    String name = optionalText(args, "agentName");
                    return services.requestControl(name == null || name.isBlank() ? "agent" : name);
                });
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

        add("workbench_execute_step", "Execute raw Gherkin in the paused live scenario. A FAILED result does not stop the worker and is not an MCP error.",
                schema(Map.of(
                        "text", stringProperty("Gherkin step text."),
                        "argument", stringProperty("Optional DocString-style argument text.")
                ), "text"),
                args -> services.executeStep(text(args, "text"), optionalText(args, "argument")));
        add("workbench_step_resolve", "Resolve one Gherkin step to its Java step definition without executing it. Returns CONSUMER_GLUE, DYNAMIC, OVERRIDE, or UNMATCHED.",
                schema(Map.of(
                        "text", stringProperty("Gherkin step text, with or without Given/When/Then."),
                        "argument", stringProperty("Optional DocString-style argument text.")
                ), "text"),
                args -> services.resolveStep(text(args, "text"), optionalText(args, "argument")));

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
        add("workbench_browser_page",
                "Compact snap of the live start host or isolate worker. Same rows as snap. Pass session to choose that id. A live session does not need a separate paused worker.",
                schema(Map.of(
                        "target", stringProperty("browser:auto, a ref, or a short id. Default browser:auto."),
                        "session", stringProperty("Session id from start or isolate. Omit to use the current live session."),
                        "fields", stringProperty("Comma-separated reply fields."),
                        "budget", cliNumber("Maximum reply bytes. Default 2048. A number or a string."),
                        "format", stringProperty("lines or json. Default json."),
                        "where", stringProperty("Row filter such as role=button."),
                        "limit", cliNumber("Maximum rows. A number or a string.")
                )),
                args -> {
                    Map<String, Object> call = new LinkedHashMap<>();
                    if (args != null) call.putAll(args);
                    if (optionalText(call, "target") == null) call.put("target", "browser:auto");
                    if (optionalText(call, "format") == null) call.put("format", "json");
                    if (optionalText(call, "budget") == null) call.put("budget", "2048");
                    return viewCommand("snap", call);
                });
        add("workbench_browser_screenshot", "Capture the current browser screenshot. Prefer workbench_browser_page unless the image is required.", schema(Map.of()),
                args -> services.browserScreenshot());
        add("workbench_element_inspect", "Inspect with a Pickleball category or one engine query (css=, xpath=, text=, image=, near=, below=, above=, left-of=, right-of=, >> stages, AND/OR/NOT).",
                schema(Map.of(
                        "category", stringProperty("Optional Pickleball element category. Omit when query is set."),
                        "text", stringProperty("Optional visible/context text for a category."),
                        "operation", stringProperty("Optional inspection operation."),
                        "maxElements", integerProperty("Optional maximum result count."),
                        "query", stringProperty("Engine pipeline or expression. The same string the composite CLI takes as --selector."),
                        "exposure", stringProperty("human (default for a query) or lower. Per query, not session-sticky."),
                        "limit", integerProperty("Optional maximum nodes, at least 1."),
                        "explain", booleanProperty("Ask the engine for why-matched."),
                        "promoteVisual", booleanProperty("Bind an image match to a node when the engine can.")
                )),
                args -> {
                    String query = optionalText(args, "query");
                    String exposure = optionalText(args, "exposure");
                    if (query != null && exposure == null) exposure = "human";
                    return services.elementInspect(
                        optionalText(args, "category"),
                        optionalText(args, "text"),
                        optionalText(args, "operation"),
                        integer(args, "maxElements"),
                        query,
                        exposure,
                        integer(args, "limit"),
                        args.containsKey("explain") ? bool(args, "explain", false) : null,
                        args.containsKey("promoteVisual") ? bool(args, "promoteVisual", false) : null
                    );
                });
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
                "Deprecated alias of wb_runs. Compact JSON including each run's retained pkb_run_profile. Do not glob.",
                schema(Map.of()),
                args -> retrieval("runs", args));
        add("workbench_diagnostic_run",
                "Deprecated alias of wb_get. One run record including the retained pkb_run_profile. No events or screenshots.",
                schema(Map.of("runId", stringProperty("Diagnostic run directory name from the catalog.")), "runId"),
                args -> {
                    text(args, "runId");
                    Map<String, Object> copy = new LinkedHashMap<>(args);
                    copy.putIfAbsent("kind", "run");
                    return retrieval("get", copy);
                });
        add("workbench_diagnostic_summary",
                "Deprecated alias of wb_get. One scenario summary including the retained pkb_run_profile. No events.jsonl.",
                schema(Map.of(
                        "runId", stringProperty("Diagnostic run directory name from the catalog."),
                        "scenarioId", stringProperty("Scenario directory name under that run.")
                ), "runId", "scenarioId"),
                args -> {
                    text(args, "runId");
                    text(args, "scenarioId");
                    Map<String, Object> copy = new LinkedHashMap<>(args);
                    copy.putIfAbsent("kind", "scenario");
                    return retrieval("get", copy);
                });
        add("wb_runs",
                "List retained diagnostic runs as compact JSON. Default limit 20 plus cursor.",
                schema(Map.of(
                        "limit", integerProperty("Page size. Default 20, max 100."),
                        "cursor", stringProperty("nextCursor from the previous page."),
                        "fields", stringProperty("Comma-separated fields."),
                        "status", stringProperty("Outcome filter such as FAILED.")
                )),
                args -> retrieval("runs", args));
        add("wb_get",
                "Get one diagnostic record by runId and kind. Ids and a relative where, not a file dump.",
                schema(Map.of(
                        "runId", stringProperty("Run id from wb_runs."),
                        "kind", stringProperty("run, scenario, step, event, property, artifact, error, or log."),
                        "scenarioId", stringProperty("Scenario id when kind is scenario, step, event, or artifact."),
                        "id", stringProperty("Event seq, artifact id, or scenario id.")
                ), "runId"),
                args -> retrieval("get", args));
        add("wb_query",
                "Filter diagnostic records. Compact JSON, explicit fields, limit and cursor.",
                schema(Map.of(
                        "runId", stringProperty("Optional run id."),
                        "scenarioId", stringProperty("Optional scenario id."),
                        "kind", stringProperty("run, scenario, step, event, property, artifact, error, or log."),
                        "text", stringProperty("Optional substring."),
                        "status", stringProperty("Optional outcome or step status."),
                        "fields", stringProperty("Comma-separated fields."),
                        "limit", integerProperty("Page size. Default 20, max 100."),
                        "cursor", stringProperty("nextCursor from the previous page.")
                )),
                args -> retrieval("query", args));
        add("wb_search",
                "Search event, error, scenario, and short-log text. Returns ids and where.",
                schema(Map.of(
                        "text", stringProperty("Substring to find."),
                        "runId", stringProperty("Optional run id."),
                        "kind", stringProperty("Optional kind filter."),
                        "limit", integerProperty("Page size. Default 20, max 100."),
                        "cursor", stringProperty("nextCursor from the previous page.")
                ), "text"),
                args -> retrieval("search", args));
        add("wb_props",
                "List retained run properties (runProfile and scalar configuration). Values are clipped.",
                schema(Map.of(
                        "runId", stringProperty("Run id from wb_runs."),
                        "limit", integerProperty("Page size. Default 20, max 100."),
                        "cursor", stringProperty("nextCursor from the previous page.")
                ), "runId"),
                args -> retrieval("props", args));
        add("wb_errors",
                "List failure records with a one-line stack head. Truncation says ...+N more, use cursor.",
                schema(Map.of(
                        "runId", stringProperty("Optional run id. Omit to scan the catalog."),
                        "scenarioId", stringProperty("Optional scenario id."),
                        "limit", integerProperty("Page size. Default 20, max 100."),
                        "cursor", stringProperty("nextCursor from the previous page.")
                )),
                args -> retrieval("errors", args));
        add("workbench_investigation_emit",
                "Write .pickleball/investigations/<id>/investigation.json and report.html. Returns the report path only.",
                schema(Map.of(
                        "investigation", Map.of(
                                "type", "object",
                                "description", "Investigation JSON object. Source of truth written to investigation.json.",
                                "additionalProperties", true
                        )
                ), "investigation"),
                args -> services.emitInvestigation(investigationObject(args.get("investigation"))));
        add("workbench_go",
                "Navigate Explorer, Report, Mapping, or Terminal. Headless calls validate the target and do not write.",
                schema(Map.of(
                        "link", Map.of(
                                "type", "object",
                                "description", "Navigation link: to, runId, eventSeq, nodeId, path, line, column, kind, mapReference, key, investigationId, section, label.",
                                "additionalProperties", true
                        ),
                        "wb", stringProperty("Optional wb://explorer?run=&seq=&node= URI with the same fields.")
                )),
                args -> {
                    Object raw = args.get("link");
                    if (raw instanceof Map<?, ?> map) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        map.forEach((key, value) -> {
                            if (key != null) copy.put(String.valueOf(key), value);
                        });
                        return services.go(copy);
                    }
                    String wb = text(args, "wb");
                    if (!wb.isBlank()) {
                        return services.go(WorkbenchGoLink.parse(wb).toMap());
                    }
                    return services.go(Map.of());
                });
        add("workbench_agent_command",
                "Forward one agent command (observe, launch, chat, overlay, panel, guide, ask, ui-apply, html, capture) to the shared controller. Page UI: see ui_catalog.",
                schema(Map.of(
                        "op", stringProperty("Agent command name."),
                        "args", Map.of(
                                "type", "object",
                                "description", "Flag map forwarded unchanged.",
                                "additionalProperties", true
                        )
                ), "op"),
                arguments -> {
                    String op = text(arguments, "op");
                    Map<String, String> forwarded = new LinkedHashMap<>();
                    Object raw = arguments.get("args");
                    if (raw instanceof Map<?, ?> map) {
                        map.forEach((key, value) -> {
                            if (key != null && value != null) forwarded.put(String.valueOf(key), forwardValue(value));
                        });
                    }
                    Object value = services.agentCommand(op, forwarded);
                    return value == null ? Map.of("status", "UNAVAILABLE", "op", op) : value;
                });
        add("store",
                "Forwards store CLI arguments, including sandbox merge and changes. Does not open the store.",
                schema(Map.of(
                        "args", Map.of(
                                "type", "array",
                                "description", "Arguments after store. Example: [\"sandbox\", \"merge\", \"left\", \"--policy=fail\", \"--json\"], [\"changes\", \"--since=1\", \"--table=files\", \"--limit=20\", \"--json\"], [\"status\"], [\"query\", \"--json\"], [\"retain\"].",
                                "items", Map.of("type", "string")
                        )
                ), "args"),
                arguments -> forwardStore(arguments.get("args")));
        add("engine",
                "Forward arguments to the pickleball engine CLI. Does not run steps inside Workbench.",
                schema(Map.of(
                        "args", Map.of(
                                "type", "array",
                                "description", "Arguments after engine. Example: [\"help\"], [\"run\", \"--text=Feature: F\\\\n  Scenario: S\\\\n    Given hello\"], or [\"step\", \"--text=hello\"].",
                                "items", Map.of("type", "string")
                        )
                ), "args"),
                arguments -> forwardEngine(arguments.get("args")));
        add("domengine",
                "Forward arguments to the Java DOM engine CLI (query, desktop, session). Does not run the engine inside Workbench.",
                schema(Map.of(
                        "args", Map.of(
                                "type", "array",
                                "description", "Arguments after domengine. Example: [\"version\"], [\"desktop\", \"list\"], or [\"query\", \"--help\"].",
                                "items", Map.of("type", "string")
                        )
                ), "args"),
                arguments -> forwardDomEngine(arguments.get("args")));
        for (McpRegistry.Command command : McpRegistry.builtins(services, args -> forwardDomEngine(args))) {
            add(command.name(), command.description(), command.schema(), command.handler());
        }
        for (McpRegistry.Command command : McpRegistry.extras()) {
            if (!tools.containsKey(command.name())) {
                add(command.name(), command.description(), command.schema(), command.handler());
            }
        }
        McpRegistry.contribute((name, description, schema, handler) -> {
            if (!tools.containsKey(name)) add(name, description, schema, handler);
        });
        add("workbench_start",
                "Forward start --url --browser --env. The page engine is Chrome DevTools. --engine=selenium is not supported.",
                schema(Map.of(
                        "url", stringProperty("Page URL."),
                        "browser", stringProperty("chrome, edge, or chrome-headless. Defaults to chrome. selenium is rejected."),
                        "env", stringProperty("Optional KEY=VALUE pairs, comma-separated.")
                ), "url"),
                args -> {
                    String browser = optionalText(args, "browser");
                    if (browser == null || browser.isBlank()) browser = "chrome";
                    if (browser.toLowerCase(java.util.Locale.ROOT).contains("selenium")) {
                        return Map.of(
                                "ok", false,
                                "error", "--browser=selenium is not supported",
                                "fix", "use chrome, edge, or chrome-headless. The page engine is Chrome DevTools."
                        );
                    }
                    return forwardReady(List.of(
                            "start",
                            "--url=" + text(args, "url"),
                            "--browser=" + browser,
                            "--env=" + blank(optionalText(args, "env"))
                    ));
                });
        add("workbench_chat_wait",
                "Forward blocking chat_wait. Returns when a chat message is stored or the timeout elapses.",
                schema(Map.of("timeout", timeoutProperty())),
                args -> forwardReady(waitArgs(args)));
        add("workbench_history_summary",
                "Forward history-summary. Counts runs, passed, failed, and skipped.",
                schema(Map.of()),
                args -> forwardReady(List.of("history-summary")));
        add("workbench_history_query",
                "Forward history-query. Same read as query. The store is the source of truth.",
                schema(Map.of(
                        "op", stringProperty("runs, get, query, search, props, errors, or tail. Defaults to query."),
                        "text", stringProperty("Optional words."),
                        "runId", stringProperty("Optional run id.")
                )),
                args -> forwardReady(historyQueryArgs(args)));
        add("workbench_dashboard_query",
                "Forward dashboard query. Lean historical rows: name, ref, status, scenarios, artifacts, data, and a short summary. Does not write the store.",
                schema(Map.of(
                        "from", stringProperty("ISO start. Optional."),
                        "to", stringProperty("ISO end. Optional."),
                        "status", stringProperty("Run status. Optional."),
                        "feature", stringProperty("Feature path fragment. Optional."),
                        "entity", stringProperty("Type#id. Optional."),
                        "text", stringProperty("Words. Optional."),
                        "element", stringProperty("Element hint. Optional."),
                        "run", stringProperty("Run id. Optional."),
                        "limit", integerProperty("Maximum runs. Optional.")
                )),
                args -> forwardReady(dashboardQueryArgs(args)));
        add("workbench_dashboard_xref",
                "Forward dashboard xref for one element: runs, paths, logs, screenshots, overlaps, summary, overlay, and chat. window writes HTML. Does not write the store.",
                schema(Map.of(
                        "id", stringProperty("Short id or element id. Optional."),
                        "selector", stringProperty("Selector hint. Optional."),
                        "role", stringProperty("Role hint. Optional."),
                        "name", stringProperty("Accessible name. Optional."),
                        "limit", integerProperty("Maximum runs. Optional."),
                        "window", booleanProperty("Write one HTML file when true.")
                )),
                args -> forwardReady(dashboardXrefArgs(args)));
        add("workbench_dashboard_overlap",
                "Forward dashboard overlap. Groups of runs whose times overlap, and the failure text shared by at least two runs in a group. Does not write the store.",
                schema(Map.of(
                        "from", stringProperty("ISO start. Optional."),
                        "to", stringProperty("ISO end. Optional."),
                        "limit", integerProperty("Maximum groups. Optional.")
                )),
                args -> forwardReady(dashboardOverlapArgs(args)));
        add("workbench_entity_lookup",
                "Forward entity-lookup --ref. Prints the field value.",
                schema(Map.of("ref", stringProperty("entity:Type#id.field or the angle-bracket form.")), "ref"),
                args -> forwardReady(List.of("entity-lookup", "--ref=" + text(args, "ref"))));
        add("workbench_open_run",
                "Forward open-run so the Workbench shows that run in Explorer or Tracking.",
                schema(Map.of(
                        "runId", stringProperty("Run id."),
                        "tab", stringProperty("explorer or tracking. Default explorer.")
                ), "runId"),
                args -> forwardReady(openRunArgs(args)));
        add("workbench_bridge_health",
                "Forward bridge-health. GET /v1/health on the bridge URL.",
                schema(Map.of("url", stringProperty("Bridge base URL.")), "url"),
                args -> forwardReady(List.of("bridge-health", "--url=" + text(args, "url"))));
        Map<String, Object> view = viewProperties();
        add("workbench_snap",
                "Compact page snap on the live start host or isolate worker. Pass session to use that id. items is the only element list. Short ids are the default.",
                schema(view),
                args -> viewCommand("snap", args));
        add("workbench_find",
                "Find elements. items is the hit list. A short id from this reply works as a target.",
                schema(view),
                args -> viewCommand("find", args));
        add("workbench_act",
                "Act on a target. Accepts a short id, ref, or query. fields and budget limit the reply.",
                schema(view),
                args -> viewCommand("act", args));
        add("workbench_form_fill",
                "Fill controls by id, short id, or visible label. fields is id=value pairs separated by |.",
                schema(view),
                args -> viewCommand("form-fill", args));
        add("workbench_watch",
                "Watch page events. fields and budget limit the reply.",
                schema(view),
                args -> viewCommand("watch", args));
        add("workbench_navigate",
                "Open a URL, URL.* config key, or fixture:<name> in the live start-host page. The session, panel, and guide stay.",
                schema(view),
                args -> viewCommand("navigate", args));
        add("workbench_pw",
                "Forward pw: one Playwright step (area and action, e.g. route fulfill, trace start, screenshot, emulate) or steps on one connection. Attaches over CDP to the live or named browser; never launches or downloads one.",
                schema(Map.of(
                        "area", stringProperty("page, context, storage, route, har, wait, locator, trace, video, screenshot, download, upload, dialog, emulate, tabs, frames, keyboard, mouse, or run."),
                        "action", stringProperty("The area's action, e.g. list, fulfill, start, press."),
                        "flags", stringProperty("Space-separated --name=value flags, e.g. --pattern=**/api --body=ok."),
                        "steps", stringProperty("For area run: a JSON array of {area, action, ...flags}."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                ), "area"),
                args -> forwardReady(pwArgs("pw", args)));
        add("workbench_cdp",
                "Forward cdp: one raw Chrome DevTools Protocol command on the current page (control tier).",
                schema(Map.of(
                        "method", stringProperty("Domain.method, e.g. Runtime.evaluate."),
                        "params", stringProperty("JSON object of parameters."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                ), "method"),
                args -> forwardReady(pwArgs("cdp", args)));
        add("workbench_browser_events",
                "Forward browser-events: read the unified browser event stream (net, console, error, nav, dialog, ...) with a seq cursor; --start, --follow, --stop. Control tier.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for browser-events; see help browser-events."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("browser-events", args)));
        add("workbench_errors",
                "Page errors and unhandled rejections with stacks and source-mapped locations (browser-events --kinds=error).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for errors; see help errors."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("errors", args)));
        add("workbench_logpoint",
                "Set, list or clear CDP logpoints that log an expression when a line runs, without pausing.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for logpoint; see help logpoint."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("logpoint", args)));
        add("workbench_net",
                "Turn response-body capture on or off for later requests (none, api, all) with a size cap.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net; see help net."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net", args)));
        add("workbench_net_body",
                "Read or save one captured response body by request id.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net-body; see help net-body."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net-body", args)));
        add("workbench_har",
                "Start or stop a HAR 1.2 recording of the session's network traffic.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for har; see help har."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("har", args)));
        add("workbench_net_route",
                "E5 network control: net-route (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net-route; see help net-route."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net-route", args)));
        add("workbench_net_unroute",
                "E5 network control: net-unroute (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net-unroute; see help net-unroute."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net-unroute", args)));
        add("workbench_net_block",
                "E5 network control: net-block (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net-block; see help net-block."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net-block", args)));
        add("workbench_net_headers",
                "E5 network control: net-headers (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net-headers; see help net-headers."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net-headers", args)));
        add("workbench_net_auth",
                "E5 network control: net-auth (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net-auth; see help net-auth."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net-auth", args)));
        add("workbench_ws_route",
                "E5 network control: ws-route (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for ws-route; see help ws-route."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("ws-route", args)));
        add("workbench_net_cache",
                "E5 network control: net-cache (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for net-cache; see help net-cache."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("net-cache", args)));
        add("workbench_security",
                "E5 network control: security (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for security; see help security."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("security", args)));
        add("workbench_csp",
                "E5 network control: csp (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for csp; see help csp."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("csp", args)));
        add("workbench_emulate",
                "E5 network control: emulate (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for emulate; see help emulate."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("emulate", args)));
        add("workbench_clock",
                "E6: page clock (install, pause-at, fast-forward, run-for, set-fixed, resume).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for clock; see help clock."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("clock", args)));
        add("workbench_permissions",
                "E6: grant, deny or reset browser permissions.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for permissions; see help permissions."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("permissions", args)));
        add("workbench_context_new",
                "E7: context-new (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for context-new; see help context-new."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("context-new", args)));
        add("workbench_context_list",
                "E7: context-list (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for context-list; see help context-list."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("context-list", args)));
        add("workbench_context_close",
                "E7: context-close (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for context-close; see help context-close."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("context-close", args)));
        add("workbench_tab_new",
                "E7: tab-new (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for tab-new; see help tab-new."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("tab-new", args)));
        add("workbench_tab_activate",
                "E7: tab-activate (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for tab-activate; see help tab-activate."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("tab-activate", args)));
        add("workbench_page_history",
                "E7: page-history (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for page-history; see help page-history."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("page-history", args)));
        add("workbench_print",
                "E7: print (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for print; see help print."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("print", args)));
        add("workbench_storage",
                "E7: storage (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for storage; see help storage."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("storage", args)));
        add("workbench_storage_state",
                "E7: storage-state (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for storage-state; see help storage-state."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("storage-state", args)));
        add("workbench_downloads",
                "E7: downloads (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for downloads; see help downloads."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("downloads", args)));
        add("workbench_dialogs",
                "E7: dialogs (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for dialogs; see help dialogs."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("dialogs", args)));
        add("workbench_drop",
                "E7: drop (same flags as the CLI verb).",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for drop; see help drop."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("drop", args)));
        add("workbench_input",
                "Run a W3C action chain (keys, mouse, pen, touch, wheel) on the live page.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for input; see help input."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("input", args)));
        add("workbench_aria_snap",
                "Playwright aria snapshot of the page; --mode=ai adds refs for act --aria-ref.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for aria-snap; see help aria-snap."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("aria-snap", args)));
        add("workbench_webauthn",
                "Virtual WebAuthn authenticator and credentials for passkey tests; keys never printed.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for webauthn; see help webauthn."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("webauthn", args)));
        add("workbench_extension",
                "Install, uninstall or list unpacked browser extensions.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for extension; see help extension."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("extension", args)));
        add("workbench_webmcp",
                "List or call the WebMCP tools a page registers.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for webmcp; see help webmcp."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("webmcp", args)));
        add("workbench_perf",
                "Page metrics, Core Web Vitals, Chrome or Playwright traces, and the run's Browser report section.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for perf; see help perf."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("perf", args)));
        add("workbench_memory",
                "Collect garbage or write a capped heap snapshot of the live page.",
                schema(Map.of(
                        "flags", stringProperty("Space-separated --name=value flags for memory; see help memory."),
                        "target", stringProperty("browser:cdp=<ws>. Omit for the live browser.")
                )),
                args -> forwardReady(protoArgs("memory", args)));
        add("workbench_chat_turn",
                "Send one agent line, then wait for a human message, an answer, or a click.",
                schema(view),
                args -> viewCommand("chat-turn", args));
        add("workbench_execute_steps",
                "Run a JSON steps file on the shared controller path. Each step is one verb.",
                schema(Map.of(
                        "file", stringProperty("Path to the steps JSON."),
                        "runId", stringProperty("Optional run id."),
                        "budget", stringProperty("Optional reply byte budget.")
                ), "file"),
                args -> {
                    List<String> verb = new ArrayList<>();
                    verb.add("execute-steps");
                    verb.add("--file=" + text(args, "file"));
                    String runId = optionalText(args, "runId");
                    if (runId != null && !runId.isBlank()) verb.add("--run-id=" + runId);
                    Object forwarded = forwardReady(verb);
                    if (!(forwarded instanceof Map<?, ?> raw)) return forwarded;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) raw;
                    int cap = budgetOf(optionalText(args, "budget"));
                    if (cap > 0 && fitJson(body, cap)) body.put("truncated", true);
                    return body;
                });
        add("workbench_ui_verify",
                "Check placed overlay widgets. The reply lists each placement once, under items.",
                schema(view),
                args -> viewCommand("ui-verify", args));
        add("workbench_capture_steps",
                "Write the steps captured for this run.",
                schema(view),
                args -> viewCommand("capture-steps", args));
        add("workbench_record_start",
                "Start collaborative recording. Later human and agent interactions become dynamic steps.",
                schema(Map.of("session", stringProperty("Optional session id."))),
                args -> recordCommand(args, "start"));
        add("workbench_record_stop",
                "Stop collaborative recording and return the script.",
                schema(Map.of()),
                args -> recordCommand(args, "stop"));
        add("workbench_record_status",
                "Read record mode, session id, and step count.",
                schema(Map.of()),
                args -> recordCommand(args, "status"));
        add("workbench_record_script",
                "Return the live parameterized dynamic-step script.",
                schema(Map.of()),
                args -> recordCommand(args, "script"));
        add("workbench_record_save",
                "Save the live script as a named routine.",
                schema(Map.of("name", stringProperty("Routine name.")), "name"),
                args -> recordCommand(args, "save"));
        add("workbench_record_load",
                "Load a saved routine.",
                schema(Map.of("name", stringProperty("Routine name.")), "name"),
                args -> recordCommand(args, "load"));
        add("workbench_record_list",
                "List saved routines.",
                schema(Map.of()),
                args -> recordCommand(args, "list"));
        add("workbench_record_export",
                "Export a routine as a Gherkin feature that uses dynamic steps.",
                schema(Map.of(
                        "name", stringProperty("Routine name. Omit to export the live script."),
                        "format", stringProperty("feature."),
                        "out", stringProperty("Optional feature path.")
                )),
                args -> recordCommand(args, "export"));
        add("workbench_record_inject",
                "Record an agent action while recording is on.",
                schema(Map.of(
                        "kind", stringProperty("click, input, select, key, hover, drag, or check."),
                        "query", stringProperty("Visible label."),
                        "value", stringProperty("Typed or selected value."),
                        "role", stringProperty("button, textbox, combobox, checkbox, or link.")
                ), "kind"),
                args -> recordCommand(args, "inject"));
        add("workbench_record_refine",
                "Rename a parameter, mark it literal, add context, or accept or reject a step.",
                schema(Map.of(
                        "from", stringProperty("Parameter to rename."),
                        "to", stringProperty("New parameter name."),
                        "literal", stringProperty("Parameter to keep as a literal value."),
                        "text", stringProperty("Context phrase prepended to the script."),
                        "index", stringProperty("Step index to accept or reject."),
                        "action", stringProperty("rename, literal, context, accept, or reject.")
                )),
                args -> {
                    String action = optionalScalar(args, "action");
                    if (action.isBlank()) {
                        if (!optionalScalar(args, "from").isBlank()) action = "rename";
                        else if (!optionalScalar(args, "literal").isBlank()) action = "literal";
                        else if (!optionalScalar(args, "text").isBlank()) action = "context";
                        else action = "reject";
                    }
                    return recordCommand(args, action);
                });
        add("workbench_record_editor",
                "Return the in-page step editor rows (id, index, text, depth) and panel placement.",
                schema(Map.of()),
                args -> recordCommand(args, "editor"));
        add("workbench_record_edit",
                "Edit the step editor: edit text, move, nest, outdent, add, or delete. New interactions still append.",
                schema(Map.of(
                        "action", stringProperty("edit, move, nest, outdent, add, or delete."),
                        "index", stringProperty("0-based row. For add, the insert point."),
                        "text", stringProperty("Step phrase. A missing comma is added."),
                        "from", stringProperty("Move source index."),
                        "to", stringProperty("Move destination index."),
                        "depth", stringProperty("Nesting depth after a move or add.")
                ), "action"),
                args -> {
                    String action = text(args, "action");
                    if (action.isBlank()) action = "edit";
                    return recordCommand(args, action);
                });
        add("workbench_record_pop",
                "Pop the step editor or the chat panel out to its own window, or pop it back in.",
                schema(Map.of(
                        "action", stringProperty("popout or popin."),
                        "panel", stringProperty("editor or chat.")
                ), "panel"),
                args -> {
                    String action = optionalScalar(args, "action");
                    if (action.isBlank()) action = "popout";
                    return recordCommand(args, action);
                });
    }

    private Object recordCommand(Map<String, Object> args, String flag) {
        Map<String, String> forwarded = new LinkedHashMap<>();
        if (flag != null && !flag.isBlank()) forwarded.put(flag, "true");
        for (String name : List.of("name", "session", "format", "out", "kind", "query", "value", "role",
                "from", "to", "literal", "text", "index", "param", "depth", "panel")) {
            String value = optionalScalar(args, name);
            if (!value.isBlank()) forwarded.put(name, value);
        }
        Object value = services.agentCommand("record", forwarded);
        return value == null ? Map.of("status", "UNAVAILABLE", "op", "record") : value;
    }

    /** pw/cdp MCP arguments to CLI tokens: area, action, then --flags. */
    /** Protocol verbs (E1-E12): the verb, then space-separated --name=value flags, then an optional target. */
    static List<String> protoArgs(String verb, Map<String, Object> args) {
        List<String> out = new ArrayList<>();
        out.add(verb);
        String flags = optionalScalar(args, "flags");
        for (String token : flags.trim().split("\\s+(?=--)")) if (!token.isBlank()) out.add(token.trim());
        String target = optionalScalar(args, "target");
        if (!target.isBlank()) out.add("--target=" + target);
        return out;
    }

    static List<String> pwArgs(String verb, Map<String, Object> args) {
        List<String> out = new ArrayList<>();
        out.add(verb);
        if ("pw".equals(verb)) {
            String area = optionalScalar(args, "area");
            if (!area.isBlank()) out.add(area);
            String action = optionalScalar(args, "action");
            if (!action.isBlank()) out.add(action);
            String steps = optionalScalar(args, "steps");
            if (!steps.isBlank()) out.add("--steps=" + steps);
            String flags = optionalScalar(args, "flags");
            for (String token : flags.trim().split("\\s+(?=--)")) if (!token.isBlank()) out.add(token.trim());
        } else {
            String method = optionalScalar(args, "method");
            if (!method.isBlank()) out.add("--method=" + method);
            String params = optionalScalar(args, "params");
            if (!params.isBlank()) out.add("--params=" + params);
        }
        String target = optionalScalar(args, "target");
        if (!target.isBlank()) out.add("--target=" + target);
        return out;
    }

    private Object forwardReady(List<String> verbArgs) {
        ConsumerReadyForwarder.Result result = ConsumerReadyForwarder.forward(services.projectRoot(), verbArgs);
        Map<String, Object> body = new LinkedHashMap<>();
        String out = result.out() == null ? "" : result.out().strip();
        boolean parsed = false;
        if (out.startsWith("{") && out.endsWith("}")) {
            try {
                JsonNode node = json.readTree(out);
                if (node.isObject()) {
                    node.fields().forEachRemaining(entry ->
                            body.put(entry.getKey(), json.convertValue(entry.getValue(), Object.class)));
                    parsed = true;
                }
            } catch (Exception ignored) {
                parsed = false;
            }
        }
        if (!parsed && !out.isBlank()) body.put("text", out);
        body.put("exit", result.exit());
        if (!body.containsKey("ok")) body.put("ok", result.exit() == 0);
        if (result.err() != null && !result.err().isBlank()) body.put("err", result.err());
        body.put("command", result.command());
        String budget = null;
        if (verbArgs != null) {
            for (String arg : verbArgs) {
                if (arg != null && arg.startsWith("--budget=")) budget = arg.substring("--budget=".length());
            }
        }
        int cap = budgetOf(budget);
        if (cap > 0 && fitJson(body, cap)) body.put("truncated", true);
        return body;
    }

    private static List<String> dashboardQueryArgs(Map<String, Object> args) {
        List<String> verb = new ArrayList<>();
        verb.add("dashboard");
        verb.add("query");
        addOptional(verb, "from", optionalText(args, "from"));
        addOptional(verb, "to", optionalText(args, "to"));
        addOptional(verb, "status", optionalText(args, "status"));
        addOptional(verb, "feature", optionalText(args, "feature"));
        addOptional(verb, "entity", optionalText(args, "entity"));
        addOptional(verb, "text", optionalText(args, "text"));
        addOptional(verb, "element", optionalText(args, "element"));
        addOptional(verb, "run", optionalText(args, "run"));
        String limit = optionalScalar(args, "limit");
        if (!limit.isBlank()) verb.add("--limit=" + limit);
        return verb;
    }

    private static List<String> dashboardXrefArgs(Map<String, Object> args) {
        List<String> verb = new ArrayList<>();
        verb.add("dashboard");
        verb.add("xref");
        addOptional(verb, "id", optionalText(args, "id"));
        addOptional(verb, "selector", optionalText(args, "selector"));
        addOptional(verb, "role", optionalText(args, "role"));
        addOptional(verb, "name", optionalText(args, "name"));
        String limit = optionalScalar(args, "limit");
        if (!limit.isBlank()) verb.add("--limit=" + limit);
        if (flag(args, "window")) verb.add("--window");
        return verb;
    }

    private static List<String> dashboardOverlapArgs(Map<String, Object> args) {
        List<String> verb = new ArrayList<>();
        verb.add("dashboard");
        verb.add("overlap");
        addOptional(verb, "from", optionalText(args, "from"));
        addOptional(verb, "to", optionalText(args, "to"));
        String limit = optionalScalar(args, "limit");
        if (!limit.isBlank()) verb.add("--limit=" + limit);
        return verb;
    }

    private static boolean flag(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value instanceof Boolean on) return on;
        return value instanceof String text && "true".equalsIgnoreCase(text.trim());
    }

    private static void addOptional(List<String> verb, String name, String value) {
        if (value != null && !value.isBlank()) verb.add("--" + name + "=" + value);
    }

    private static List<String> waitArgs(Map<String, Object> args) {
        List<String> verb = new ArrayList<>();
        verb.add("chat_wait");
        String timeout = timeoutText(args.get("timeout"));
        if (timeout != null && !timeout.isBlank()) verb.add("--timeout=" + timeout);
        return verb;
    }

    /** Seconds from a string or a number. Null when the argument was omitted. */
    static String timeoutText(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            if (raw < 0 || raw > 120) {
                throw new IllegalArgumentException("error: workbench_chat_wait timeout is seconds from 0 to 120.");
            }
            if (Math.rint(raw) == raw) return Integer.toString(number.intValue());
            return Double.toString(raw);
        }
        if (value instanceof String text && !text.isBlank()) return text.trim();
        return null;
    }

    private static Map<String, Object> timeoutProperty() {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("description", "Seconds to wait, 0 to 120. Default 30. A number or a string.");
        property.put("anyOf", List.of(
                Map.of("type", "integer"),
                Map.of("type", "number"),
                Map.of("type", "string")
        ));
        return property;
    }

    private static Map<String, Object> viewProperties() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("target", stringProperty("browser:auto, a ref, or a short id."));
        view.put("session", stringProperty("Session id from start or isolate. Omit to use the current live session."));
        view.put("query", stringProperty("Visible text or selector."));
        view.put("ref", stringProperty("Full ref or short id."));
        view.put("fields", stringProperty("Comma-separated reply fields, or form-fill id=value pairs."));
        view.put("budget", cliNumber("Maximum reply bytes. The reply is truncated under this cap. A number or a string."));
        view.put("format", stringProperty("lines or json."));
        view.put("limit", cliNumber("Maximum rows. A number or a string."));
        view.put("where", stringProperty("Row filter such as role=button."));
        view.put("within", stringProperty("Snap scope: ref, short id, or element id."));
        view.put("depth", cliNumber("Levels under --within. A number or a string."));
        view.put("group", stringProperty("role or type."));
        view.put("priority", stringProperty("on to order controls."));
        view.put("kind", stringProperty("act kind: click, type, hover, press, or select."));
        view.put("value", stringProperty("Value for type or select."));
        view.put("text", stringProperty("chat-turn text."));
        view.put("timeout", cliNumber("Seconds to wait. A number or a string."));
        view.put("until", stringProperty("Watch or chat-turn condition."));
        view.put("pick", stringProperty("Index, name, or role such as role=button."));
        view.put("runId", stringProperty("Optional run id."));
        view.put("mode", stringProperty("snap mode: compact, diff, or full."));
        view.put("out", stringProperty("Optional capture path."));
        view.put("frame", stringProperty("Frame id."));
        view.put("condition", stringProperty("Wait or act condition."));
        view.put("url", stringProperty("navigate address: URL, URL.* config key, or fixture:<name>."));
        view.put("include", stringProperty("snap/find keys: values,highlights,cards,guide,placements,chat."));
        view.put("exposure", stringProperty("human or full."));
        view.put("since", stringProperty("snap --since cursor (snapCursor from an earlier snap)."));
        view.put("purpose", stringProperty("data-pkb-purpose token, such as chat-tab."));
        view.put("file", stringProperty("upload or attach path; several are comma-separated."));
        return view;
    }

    private Object viewCommand(String op, Map<String, Object> args) {
        checkView(args);
        Map<String, String> forwarded = viewFlags(args);
        Object value = services.agentCommand(op, forwarded);
        if (!(value instanceof Map<?, ?> raw)) return Map.of("status", "UNAVAILABLE", "op", op);
        Map<String, Object> body = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key != null) body.put(String.valueOf(key), item);
        });
        String fields = forwarded.get("fields");
        if (fields != null && !fields.isBlank() && !"form-fill".equals(op) && body.get("items") instanceof List<?> list
                && !list.isEmpty() && list.get(0) instanceof Map<?, ?>) {
            List<String> names = new ArrayList<>();
            for (String part : fields.split(",")) {
                if (!part.isBlank()) names.add(part.trim());
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (String name : names) {
                    if (map.containsKey(name)) row.put(name, map.get(name));
                }
                rows.add(row);
            }
            body.put("items", rows);
        }
        int budget = budgetOf(forwarded.get("budget"));
        if (budget > 0 && fitJson(body, budget)) body.put("truncated", true);
        return body;
    }

    private static int budgetOf(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            int bytes = Integer.parseInt(raw.trim());
            if (bytes < 1) return 0;
            return Math.min(16 * 1024, bytes);
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    /** Drops trailing items until the JSON object fits, including the last row. */
    static boolean fitJson(Map<String, Object> body, int maxBytes) {
        if (body == null || maxBytes < 1) return false;
        boolean trimmed = false;
        int guard = 0;
        while (jsonBytes(body) > maxBytes && guard++ < 10_000) {
            Object items = body.get("items");
            if (!(items instanceof List<?> list) || list.isEmpty()) {
                Object text = body.get("text");
                if (text instanceof String raw && raw.length() > 80 && raw.length() > maxBytes) {
                    int room = Math.max(24, Math.min(raw.length() - 1, Math.max(24, maxBytes / 2)));
                    body.put("text", raw.substring(0, room) + "...");
                    trimmed = true;
                    continue;
                }
                break;
            }
            if (list.size() == 1 && list.get(0) instanceof String text && text.length() > 24) {
                int room = Math.max(8, Math.min(text.length() - 1, Math.max(8, maxBytes / 4)));
                String clip = text.substring(0, Math.min(text.length(), room));
                if (clip.length() > 3 && clip.length() < text.length()) {
                    body.put("items", List.of(clip.substring(0, clip.length() - 3) + "..."));
                    trimmed = true;
                    continue;
                }
            }
            body.put("items", new ArrayList<>(list.subList(0, list.size() - 1)));
            trimmed = true;
        }
        return trimmed;
    }

    private static int jsonBytes(Map<String, Object> body) {
        try {
            return new ObjectMapper().writeValueAsString(body).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        } catch (java.io.IOException failure) {
            return Integer.MAX_VALUE;
        }
    }

    private static Map<String, String> viewFlags(Map<String, Object> args) {
        Map<String, String> forwarded = new LinkedHashMap<>();
        if (args == null) return forwarded;
        for (String key : List.of(
                "target", "session", "query", "ref", "fields", "budget", "format", "limit", "where", "within",
                "depth", "group", "priority", "kind", "value", "text", "timeout", "until", "pick", "mode", "out",
                "channel", "frame", "condition", "url", "include", "exposure", "since", "purpose", "file"
        )) {
            String value = optionalAny(args, key);
            if (value != null && !value.isBlank()) forwarded.put(key, value);
        }
        String runId = optionalAny(args, "runId");
        if (runId != null && !runId.isBlank()) forwarded.put("run-id", runId);
        if (forwarded.get("session") == null) {
            String alias = optionalAny(args, "sessionId");
            if (alias != null && !alias.isBlank()) forwarded.put("session", alias);
        }
        return forwarded;
    }

    private static final java.util.Set<String> VIEW_KEYS = java.util.Set.of(
            "target", "session", "sessionId", "query", "ref", "fields", "budget", "format", "limit", "where",
            "within", "depth", "group", "priority", "kind", "value", "text", "timeout", "until", "pick",
            "runId", "mode", "out", "channel", "frame", "condition", "url", "include", "exposure", "since", "purpose", "file"
    );

    /** Rejects a value the CLI would not accept. Numbers and strings are both valid. */
    private static void checkView(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return;
        String tool = CURRENT_TOOL.get();
        if (tool == null || tool.isBlank()) tool = "workbench tool";
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            String key = entry.getKey();
            if (key == null || !VIEW_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "error: " + tool + " does not accept " + key + ".\n"
                                + "fix: Use the same flags as the CLI verb. session is the start or isolate id."
                );
            }
            Object value = entry.getValue();
            if (value == null) continue;
            if (!(value instanceof String) && !(value instanceof Number) && !(value instanceof Boolean)) {
                throw new IllegalArgumentException(
                        "error: " + key + " must be a string, number, or boolean.\n"
                                + "fix: Pass the same value the CLI flag accepts."
                );
            }
        }
        whole(args.get("budget"), "budget");
        whole(args.get("limit"), "limit");
        whole(args.get("timeout"), "timeout");
        whole(args.get("depth"), "depth");
        Object format = args.get("format");
        if (format instanceof String text && !text.isBlank()
                && !"lines".equals(text.trim()) && !"json".equals(text.trim())) {
            throw new IllegalArgumentException(
                    "error: format must be lines or json.\nfix: Pass format as \"lines\" or \"json\"."
            );
        }
    }

    private static void whole(Object value, String name) {
        if (value == null) return;
        if (value instanceof Boolean) {
            throw new IllegalArgumentException(
                    "error: " + name + " must be a whole number.\nfix: Pass " + name + " as 600 or \"600\"."
            );
        }
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            if (!Double.isFinite(raw) || raw != Math.rint(raw)) {
                throw new IllegalArgumentException(
                        "error: " + name + " must be a whole number.\nfix: Pass " + name + " as 600 or \"600\"."
                );
            }
            return;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.matches("-?\\d+")) return;
        }
        throw new IllegalArgumentException(
                "error: " + name + " must be a whole number.\nfix: Pass " + name + " as 600 or \"600\"."
        );
    }

    private static String optionalAny(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) return null;
        if (value instanceof String text) return text.isBlank() ? null : text;
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            if (Double.isFinite(raw) && raw == Math.rint(raw)) return Long.toString(number.longValue());
            return String.valueOf(raw);
        }
        if (value instanceof Boolean) return String.valueOf(value);
        return null;
    }

    private static List<String> historyQueryArgs(Map<String, Object> args) {
        List<String> verb = new ArrayList<>();
        verb.add("history-query");
        String op = optionalText(args, "op");
        verb.add(op == null || op.isBlank() ? "query" : op);
        String words = optionalText(args, "text");
        if (words != null && !words.isBlank()) verb.add("--text=" + words);
        String runId = optionalText(args, "runId");
        if (runId != null && !runId.isBlank()) verb.add("--run-id=" + runId);
        return verb;
    }

    private static List<String> openRunArgs(Map<String, Object> args) {
        List<String> verb = new ArrayList<>();
        verb.add("open-run");
        verb.add("--run-id=" + text(args, "runId"));
        String tab = optionalText(args, "tab");
        if (tab != null && !tab.isBlank()) verb.add("--tab=" + tab);
        return verb;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private Object forwardStore(Object rawArgs) {
        List<String> args = new ArrayList<>();
        if (rawArgs instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) args.add(String.valueOf(item));
            }
        } else if (rawArgs != null) {
            throw new IllegalArgumentException("error: store requires args as a list of strings.\nfix: Pass {\"args\":[\"status\"]}.");
        }
        if (args.isEmpty()) {
            throw new IllegalArgumentException("error: store requires args.\nfix: Pass {\"args\":[\"status\"]}.");
        }
        StoreCliForwarder.Result result = StoreCliForwarder.run(services.projectRoot(), args);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exit", result.exit());
        body.put("out", result.out());
        body.put("err", result.err());
        body.put("forwarded", args);
        return body;
    }

    private Object forwardDomEngine(Object rawArgs) {
        List<String> args = new ArrayList<>();
        if (rawArgs instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) args.add(String.valueOf(item));
            }
        } else if (rawArgs != null) {
            throw new IllegalArgumentException("error: domengine requires args as a list of strings.\nfix: Pass {\"args\":[\"version\"]}.");
        }
        if (args.isEmpty()) {
            throw new IllegalArgumentException("error: domengine requires args.\nfix: Pass {\"args\":[\"version\"]}.");
        }
        DomEngineCliForwarder.Result result =
                DomEngineCliForwarder.run(services.projectRoot(), args);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exit", result.exit());
        body.put("out", result.out());
        body.put("err", result.err());
        body.put("forwarded", args);
        return body;
    }

    private Object forwardEngine(Object rawArgs) {
        List<String> args = new ArrayList<>();
        if (rawArgs instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) args.add(String.valueOf(item));
            }
        } else if (rawArgs != null) {
            throw new IllegalArgumentException("error: engine requires args as a list of strings.\nfix: Pass {\"args\":[\"help\"]}.");
        }
        if (args.isEmpty()) {
            throw new IllegalArgumentException("error: engine requires args.\nfix: Pass {\"args\":[\"help\"]}.");
        }
        EngineCliForwarder.Result result = EngineCliForwarder.run(services.projectRoot(), args);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exit", result.exit());
        body.put("out", result.out());
        body.put("err", result.err());
        body.put("forwarded", args);
        return body;
    }

    private Object retrieval(String op, Map<String, Object> args) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("op", op);
        for (String key : List.of("runId", "scenarioId", "kind", "text", "fields", "cursor", "status", "id", "limit", "verbose")) {
            if (args != null && args.containsKey(key) && args.get(key) != null) request.put(key, args.get(key));
        }
        DiagnosticQuery.Outcome outcome = DiagnosticQuery.query(services.projectRoot(), request);
        if (outcome.exit() != 0) throw new ToolFailure(outcome.body());
        appendQueryLog(op, args);
        return outcome.body();
    }

    /** Same columns as a successful launcher {@code query}: event {@code query}, purpose is the verb. */
    private void appendQueryLog(String op, Map<String, Object> args) {
        Path project = services.projectRoot();
        if (project == null) return;
        String run = null;
        String agent = null;
        if (args != null) {
            Object value = args.get("runId");
            if (value instanceof String text && !text.isBlank()) run = text;
            else if (value != null && !String.valueOf(value).isBlank()) run = String.valueOf(value);
            Object named = args.get("agentId");
            if (named == null) named = args.get("agent");
            if (named != null && !String.valueOf(named).isBlank()) agent = String.valueOf(named);
        }
        if (agent == null || agent.isBlank()) agent = AgentLog.recordedAgent(project, run);
        try {
            AgentLog.append(project, Instant.now(), agent == null || agent.isBlank() ? "-" : agent, run, "query", op, null);
        } catch (IOException ignored) {
            // The JSON answer is already computed. A short-log failure does not hide it.
        }
    }

    static final class ToolFailure extends RuntimeException {
        final Map<String, Object> body;

        ToolFailure(Map<String, Object> body) {
            super(String.valueOf(body.get("error")));
            this.body = body;
        }
    }

    private void add(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Function<Map<String, Object>, Object> action
    ) {
        // The tier comes from the one registry (VerbTiers): human tools use the human view and human input only.
        String tiered = description + " Tier: " + VerbTiers.toolTier(name) + ".";
        tools.put(name, new ToolBinding(name, tiered, inputSchema, action));
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
        } catch (ToolFailure failure) {
            return result(failure.body, true);
        } catch (IllegalArgumentException failure) {
            return result(usageBody(failure.getMessage()), true);
        } catch (RuntimeException failure) {
            return result(Map.of(
                    "error", failure.getClass().getSimpleName(),
                    "message", failure.getMessage() == null ? failure.toString() : failure.getMessage()
            ), true);
        }
    }

    private McpSchema.CallToolResult result(Object value, boolean error) {
        try {
            Object safe = OutputFormatter.redactValue(value);
            String text = SecretRedactor.scrub(json.writeValueAsString(safe));
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

    private static Map<String, Object> usageBody(String message) {
        String text = message == null ? "invalid arguments" : message.strip();
        String error = text;
        String fix = "";
        if (text.startsWith("error:")) {
            int fixAt = text.indexOf("\nfix:");
            if (fixAt >= 0) {
                error = text.substring("error:".length(), fixAt).trim();
                fix = text.substring(fixAt + "\nfix:".length()).trim();
            } else {
                error = text.substring("error:".length()).trim();
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        if (!fix.isBlank()) body.put("fix", fix);
        // The JSON fields stay. The original lines stay in the text so a reader
        // still sees "error:" and "fix:" the way the launcher prints them.
        if (text.contains("\nfix:")) body.put("message", text);
        return body;
    }

    private static Map<String, Object> cliNumber(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("description", description);
        property.put("anyOf", List.of(
                Map.of("type", "integer"),
                Map.of("type", "number"),
                Map.of("type", "string")
        ));
        return property;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("description", description + " A number or a string.");
        property.put("anyOf", List.of(
                Map.of("type", "integer"),
                Map.of("type", "number"),
                Map.of("type", "string")
        ));
        return property;
    }

    private static Map<String, Object> booleanProperty(String description) {
        return Map.of("type", "boolean", "description", description);
    }

    private static Map<String, Object> objectProperty(String description) {
        return Map.of("type", "object", "description", description);
    }

    private static String forwardValue(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder joined = new StringBuilder();
            for (Object item : list) {
                if (item == null) continue;
                if (!joined.isEmpty()) joined.append('\n');
                joined.append(item);
            }
            return joined.toString();
        }
        return String.valueOf(value);
    }

    private static String text(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(missingArgument(name, value));
        }
        return text;
    }

    private static String missingArgument(String name, Object value) {
        String tool = CURRENT_TOOL.get();
        String who = tool == null || tool.isBlank() ? "This tool" : tool;
        String description = argumentDescription(tool, name);
        String detail = description.isBlank() ? "" : " (" + description + ")";
        String got = value == null ? "It was omitted." : "Got " + value.getClass().getSimpleName() + ".";
        return "error: " + who + " requires " + name + detail + ". " + got
                + "\nfix: Pass a non-blank string.";
    }

    private static String argumentDescription(String tool, String name) {
        WorkbenchMcpTools current = CURRENT.get();
        if (current == null || tool == null || name == null) return "";
        ToolBinding binding = current.tools.get(tool);
        if (binding == null) return "";
        Object properties = binding.inputSchema.get("properties");
        if (!(properties instanceof Map<?, ?> map)) return "";
        Object property = map.get(name);
        if (!(property instanceof Map<?, ?> spec)) return "";
        Object description = spec.get("description");
        return description == null ? "" : description.toString();
    }

    private static String optionalText(Map<String, Object> args, String name) {
        Object value = args.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String optionalScalar(Map<String, Object> args, String name) {
        if (args == null || args.get(name) == null) return "";
        Object value = args.get(name);
        if (value instanceof Number || value instanceof String) return McpRegistry.plain(value);
        throw new IllegalArgumentException(
                "error: " + name + " must be a string or a whole number.\nfix: Pass " + name + " as text or digits."
        );
    }

    private static Integer integer(Map<String, Object> args, String name) {
        Long whole = whole(args, name);
        if (whole == null) return null;
        if (whole > Integer.MAX_VALUE || whole < Integer.MIN_VALUE) {
            throw new IllegalArgumentException(
                    "error: " + name + " must be a whole number.\nfix: Pass " + name + " as a number or a string of digits."
            );
        }
        return whole.intValue();
    }

    private static Long longValue(Map<String, Object> args, String name) {
        return whole(args, name);
    }

    private static Long whole(Map<String, Object> args, String name) {
        if (args == null || args.get(name) == null) return null;
        Object value = args.get(name);
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            if (!Double.isFinite(raw) || raw != Math.rint(raw)) {
                throw new IllegalArgumentException(
                        "error: " + name + " must be a whole number.\nfix: Pass " + name + " as 20 or \"20\"."
                );
            }
            return number.longValue();
        }
        if (value instanceof String text) {
            if (text.isBlank()) return null;
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "error: " + name + " must be a whole number.\nfix: Pass " + name + " as 20 or \"20\"."
                );
            }
        }
        throw new IllegalArgumentException(
                "error: " + name + " must be a whole number.\nfix: Pass " + name + " as 20 or \"20\"."
        );
    }

    private static boolean bool(Map<String, Object> args, String name, boolean defaultValue) {
        Object value = args.get(name);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> investigationObject(Object value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "error: workbench_investigation_emit requires investigation.\n"
                            + "fix: Pass a JSON object.\n"
            );
        }
        Object parsed = value;
        if (parsed instanceof String text) {
            if (text.isBlank()) {
                throw new IllegalArgumentException("error: investigation must be a JSON object.\nfix: Pass a JSON object.\n");
            }
            try {
                parsed = json.readValue(text, LinkedHashMap.class);
            } catch (Exception failure) {
                throw new IllegalArgumentException("error: investigation must be a JSON object.\nfix: Pass a JSON object.\n");
            }
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("error: investigation must be a JSON object.\nfix: Pass a JSON object.\n");
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
