package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.ControlBridgeCallResult;
import tools.dscode.control.protocol.ControlBridgeError;
import tools.dscode.control.protocol.ControlBridgeStatus;
import tools.dscode.control.protocol.ControlBridgeStepResolution;
import tools.dscode.control.protocol.ControlProtocol;
import tools.dscode.workbench.WorkbenchServices;
import tools.dscode.workbench.launch.ConsumerReadyForwarder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchMcpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final List<String> DOCUMENTED_TOOLS = List.of(
            "workbench_sync",
            "workbench_sync_status",
            "workbench_worker_start",
            "workbench_worker_restart",
            "workbench_worker_stop",
            "workbench_worker_status",
            "workbench_request_control",
            "workbench_release_control",
            "workbench_set_current_action",
            "workbench_control_lease",
            "workbench_player_state",
            "workbench_player_replace_document",
            "workbench_request_save",
            "workbench_execute_step",
            "workbench_step_resolve",
            "workbench_mapping_get",
            "workbench_mapping_put",
            "workbench_mapping_resolve",
            "workbench_mapping_snapshot",
            "workbench_mapping_restore",
            "workbench_events",
            "workbench_browser_page",
            "workbench_browser_screenshot",
            "workbench_element_inspect",
            "workbench_service_call",
            "workbench_breakpoint_list",
            "workbench_breakpoint_add",
            "workbench_breakpoint_remove",
            "workbench_breakpoint_clear",
            "workbench_step_override_list",
            "workbench_step_override_compile",
            "workbench_step_override_remove",
            "workbench_step_override_clear",
            "workbench_diagnostic_catalog",
            "workbench_diagnostic_run",
            "workbench_diagnostic_summary",
            "wb_runs",
            "wb_get",
            "wb_query",
            "wb_search",
            "wb_props",
            "wb_errors",
            "workbench_investigation_emit",
            "workbench_go",
            "workbench_agent_command",
            "store",
            "engine",
            "domengine",
            "query",
            "click",
            "type",
            "snapshot",
            "observe",
            "launch",
            "surfaces",
            "agent_popup_open",
            "agent_popup_status",
            "agent_popup_close",
            "ui",
            "ui_poll",
            "ui_catalog",
            "workbench_start",
            "workbench_chat_wait",
            "workbench_history_summary",
            "workbench_history_query",
            "workbench_dashboard_query",
            "workbench_dashboard_xref",
            "workbench_dashboard_overlap",
            "workbench_entity_lookup",
            "workbench_open_run",
            "workbench_bridge_health",
            "workbench_snap",
            "workbench_find",
            "workbench_act",
            "workbench_form_fill",
            "workbench_watch",
            "workbench_navigate",
            "workbench_pw",
            "workbench_cdp",
            "workbench_browser_events",
            "workbench_chat_turn",
            "workbench_execute_steps",
            "workbench_ui_verify",
            "workbench_capture_steps",
            "workbench_record_start",
            "workbench_record_stop",
            "workbench_record_status",
            "workbench_record_script",
            "workbench_record_save",
            "workbench_record_load",
            "workbench_record_list",
            "workbench_record_export",
            "workbench_record_inject",
            "workbench_record_refine",
            "workbench_record_editor",
            "workbench_record_edit",
            "workbench_record_pop"
    );

    @TempDir
    Path project;

    @Test
    void registeredToolsMatchTheDocumentedCatalog() {
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
            if ("close".equals(method)) return null;
            return null;
        }), JSON);
        assertEquals(DOCUMENTED_TOOLS, tools.names());
    }

    @Test
    void observeSchemaAcceptsTrafficFlagsAndStartRejectsSelenium() {
        Map<String, Object> schema = McpRegistry.observeSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("request"), properties.keySet().toString());
        assertTrue(properties.containsKey("response"), properties.keySet().toString());
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertEquals(
                List.of("observe", "--request", "/api", "--response", "*"),
                McpRegistry.argv("observe", Map.of("request", "/api", "response", "*"))
        );
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> null), JSON);
        Object rejected = tools.call("workbench_start", Map.of("url", "http://127.0.0.1/page", "browser", "selenium"));
        assertTrue(String.valueOf(rejected).contains("not supported"), String.valueOf(rejected));
        assertTrue(tools.names().contains("observe"));
    }

    @Test
    void snapshotCommandReturnsTheCompositeSnapshotShape() {
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> null), JSON);
        Object value = tools.call("snapshot", Map.of("kind", "offline"));
        assertTrue(value instanceof Map<?, ?> map && "pickleball.composite-snapshot".equals(map.get("schema")));
        Map<?, ?> body = (Map<?, ?>) value;
        assertEquals(2, ((Number) body.get("version")).intValue());
        assertEquals("html", ((Map<?, ?>) body.get("canonical")).get("kind"));
        assertTrue(body.containsKey("premap"), String.valueOf(body));
        assertTrue(body.containsKey("nodes"), String.valueOf(body));
        assertFalse(String.valueOf(body.get("warnings")).contains("g3-producers-absent"), String.valueOf(body));
        Object listed = tools.call("surfaces", Map.of());
        assertTrue(String.valueOf(listed).contains("html-write"));
    }

    @Test
    void surfacesListsAndForwardsEveryVisualVerbUnchanged() {
        java.util.concurrent.atomic.AtomicReference<Object[]> seen = new java.util.concurrent.atomic.AtomicReference<>();
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
            if (!"agentCommand".equals(method)) return null;
            seen.set(args);
            return Map.of("ok", true, "forwarded", String.valueOf(args[0]));
        }), JSON);
        String listed = String.valueOf(tools.call("surfaces", Map.of()));
        for (String verb : List.of("label", "unlabel", "border", "unborder", "arrow", "unarrow",
                "step-badge", "unstep-badge", "magnifier", "unmagnifier", "spotlight", "unspotlight")) {
            assertTrue(listed.contains(verb), verb + " missing from " + listed);
            Object value = tools.call("surfaces", Map.of("op", verb, "handle", "save", "number", "3", "zoom", "2"));
            assertEquals(verb, ((Map<?, ?>) value).get("forwarded"), String.valueOf(value));
            assertEquals(verb, seen.get()[0]);
            Map<?, ?> forwarded = (Map<?, ?>) seen.get()[1];
            assertEquals("save", forwarded.get("handle"), String.valueOf(forwarded));
            assertEquals("3", forwarded.get("number"), String.valueOf(forwarded));
            assertEquals("2", forwarded.get("zoom"), String.valueOf(forwarded));
        }
    }

    @Test
    void surfacesSchemaDescribesEveryForwardedVisualArgument() {
        Map<String, Object> properties = McpRegistry.surfaceProperties();
        for (String key : List.of("op", "text", "handle", "id", "html", "session", "x", "y", "width", "height", "geometry",
                "where", "number", "zoom", "dim", "direction", "from", "to", "color", "style", "preset", "target", "pane")) {
            assertTrue(properties.containsKey(key), key + " missing from the surfaces schema " + properties.keySet());
            Map<?, ?> property = (Map<?, ?>) properties.get(key);
            String description = String.valueOf(property.get("description"));
            assertFalse(description.contains("\n"), key + " " + description);
            assertTrue(description.length() <= 300, key + " " + description.length());
        }
        assertEquals(List.of("number", "string"), ((Map<?, ?>) properties.get("zoom")).get("type"));
        assertTrue(properties.containsKey("session"), properties.keySet().toString());
        assertTrue(String.valueOf(((Map<?, ?>) properties.get("html")).get("description")).contains("page file"));
    }

    @Test
    void wholeNumbersForwardWithoutADecimalAndBadLimitsAreRejected() {
        java.util.concurrent.atomic.AtomicReference<Object[]> seen = new java.util.concurrent.atomic.AtomicReference<>();
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
            if ("events".equals(method) || "agentCommand".equals(method)) seen.set(args);
            return null;
        }), JSON);
        assertEquals(null, tools.call("workbench_events", Map.of("afterSequence", "4", "limit", "5")));
        assertEquals(4L, seen.get()[0]);
        assertEquals(5, seen.get()[1]);
        IllegalArgumentException bad = assertThrows(
                IllegalArgumentException.class,
                () -> tools.call("workbench_events", Map.of("limit", "nope"))
        );
        assertTrue(bad.getMessage().contains("whole number"), bad.getMessage());

        tools.call("workbench_record_save", Map.of("name", "morning", "index", 2.0d));
        assertEquals("record", seen.get()[0]);
        @SuppressWarnings("unchecked")
        Map<String, String> forwarded = (Map<String, String>) seen.get()[1];
        assertEquals("true", forwarded.get("save"));
        assertEquals("morning", forwarded.get("name"));
        assertEquals("2", forwarded.get("index"));
        assertFalse(forwarded.containsKey("session"), forwarded.toString());
        assertFalse(forwarded.containsKey("text"), forwarded.toString());

        Object placed = tools.call("surfaces", Map.of("op", "magnifier", "handle", "s1.h18", "zoom", 2.0d, "session", "live-1"));
        assertEquals("magnifier", ((Map<?, ?>) placed).get("op"));
        assertEquals("magnifier", seen.get()[0]);
        @SuppressWarnings("unchecked")
        Map<String, String> visual = (Map<String, String>) seen.get()[1];
        assertEquals("2", visual.get("zoom"));
        assertEquals("s1.h18", visual.get("handle"));
        assertEquals("live-1", visual.get("session"));

        tools.call("workbench_record_refine", Map.of("from", "account", "to", "id"));
        assertEquals("record", seen.get()[0]);
        @SuppressWarnings("unchecked")
        Map<String, String> refined = (Map<String, String>) seen.get()[1];
        assertEquals("true", refined.get("rename"));
        assertEquals("account", refined.get("from"));
        assertEquals("id", refined.get("to"));
        assertFalse(refined.containsKey("action"), refined.toString());

        tools.call("workbench_record_pop", Map.of("panel", "editor"));
        @SuppressWarnings("unchecked")
        Map<String, String> popped = (Map<String, String>) seen.get()[1];
        assertEquals("true", popped.get("popout"));
        assertEquals("editor", popped.get("panel"));
    }

    @Test
    void snapshotCommandPlaceholderWhenDispatchIsAbsent() {
        String dispatch = SnapshotCommand.dispatchClassName;
        String producers = SnapshotCommand.producersClassName;
        try {
            SnapshotCommand.dispatchClassName = "missing.SnapshotDispatch";
            SnapshotCommand.producersClassName = "missing.SnapshotProducers";
            WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> null), JSON);
            Object value = tools.call("snapshot", Map.of("kind", "offline"));
            assertTrue(value instanceof Map<?, ?>);
            Map<?, ?> body = (Map<?, ?>) value;
            assertEquals("pickleball.composite-snapshot", body.get("schema"));
            assertEquals(1, ((Number) body.get("version")).intValue());
            assertTrue(String.valueOf(body.get("warnings")).contains("g3-producers-absent"));
        } finally {
            SnapshotCommand.dispatchClassName = dispatch;
            SnapshotCommand.producersClassName = producers;
        }
    }

    @Test
    void packagedServerCompletesInitializeHandshake() throws Exception {
        try (ProcessHarness harness = new ProcessHarness(project)) {
            harness.initialize();
        }
    }

    @Test
    void packagedServerInitializesListsToolsInvokesControllerAndKeepsStdoutProtocolOnly()
            throws Exception {
        try (ProcessHarness harness = new ProcessHarness(project)) {
            harness.initialize();

            JsonNode listed = harness.request(2, "tools/list", "{}");
            Set<String> toolNames = new HashSet<>();
            listed.at("/result/tools").forEach(tool -> toolNames.add(tool.path("name").asText()));
            assertEquals(Set.copyOf(DOCUMENTED_TOOLS), toolNames);

            JsonNode status = harness.toolCall(3, "workbench_worker_status", "{}");
            assertFalse(status.at("/result/isError").asBoolean());
            assertTrue(resultText(status).contains("\"running\":false"));

            JsonNode overrideError = harness.toolCall(4, "workbench_step_override_list", "{}");
            assertTrue(overrideError.at("/result/isError").asBoolean());
            assertTrue(resultText(overrideError).contains("interactive worker"));

            JsonNode invalidArguments = harness.toolCall(5, "workbench_execute_step", "{}");
            assertTrue(invalidArguments.has("error") || invalidArguments.at("/result/isError").asBoolean());

            JsonNode invalidMethod = harness.request(6, "workbench/not-a-method", "{}");
            assertNotNull(invalidMethod.get("error"));
        }
    }

    @Test
    void stepOverrideCompileUsesSharedServicesAndServerCloseIsIdempotent() throws Exception {
        AtomicBoolean compileCalled = new AtomicBoolean();
        AtomicInteger closes = new AtomicInteger();
        WorkbenchServices services = fakeServices((method, args) -> {
            if ("compileStepOverride".equals(method)) {
                assertEquals("probe", args[0]);
                assertEquals("^PROBE (.+)$", args[1]);
                assertTrue(((String) args[2]).contains("{{CLASS_NAME}}"));
                compileCalled.set(true);
                throw new IllegalStateException("compile-probe");
            }
            if ("close".equals(method)) closes.incrementAndGet();
            return null;
        });

        WorkbenchMcpTools tools = new WorkbenchMcpTools(services, JSON);
        var specification = tools.specifications().stream()
                .filter(spec -> "workbench_step_override_compile".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow();
        var result = specification.callHandler().apply(null, new McpSchema.CallToolRequest(
                "workbench_step_override_compile",
                Map.of(
                        "id", "probe",
                        "regex", "^PROBE (.+)$",
                        "source", "public final class {{CLASS_NAME}} {}"
                ),
                null
        ));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        assertTrue(((McpSchema.TextContent) result.content().getFirst()).text().contains("compile-probe"));
        assertTrue(compileCalled.get());

        try (WorkbenchMcpServer server = new WorkbenchMcpServer(
                services,
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream()
        )) {
            server.close();
            server.close();
        }
        assertEquals(1, closes.get());
    }

    @Test
    void executeStepFailureIsAResultNotAnMcpErrorAndDoesNotStopTheWorker() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        AtomicBoolean executed = new AtomicBoolean();
        WorkbenchServices services = fakeServices((method, args) -> {
            if ("executeStep".equals(method)) {
                executed.set(true);
                assertEquals(", verify \"left\" equals \"right\"", args[0]);
                return new ControlBridgeCallResult(
                        "FAILED",
                        null,
                        null,
                        new ControlBridgeError("ASSERTION", "left was not right", ""),
                        pausedRuntime()
                );
            }
            if ("stopWorker".equals(method) || "restartWorker".equals(method) || "close".equals(method)) {
                if ("stopWorker".equals(method) || "restartWorker".equals(method)) {
                    stops.incrementAndGet();
                }
                return null;
            }
            return null;
        });

        WorkbenchMcpTools tools = new WorkbenchMcpTools(services, JSON);
        var specification = tools.specifications().stream()
                .filter(spec -> "workbench_execute_step".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow();
        assertTrue(specification.tool().description().contains("does not stop the worker"));

        var result = specification.callHandler().apply(null, new McpSchema.CallToolRequest(
                "workbench_execute_step",
                Map.of("text", ", verify \"left\" equals \"right\""),
                null
        ));
        assertFalse(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("FAILED"));
        assertTrue(text.contains("left was not right"));
        assertTrue(executed.get());
        assertEquals(0, stops.get());
    }

    @Test
    void executeStepControllerExceptionIsMcpErrorAndDoesNotStopTheWorker() throws Exception {
        AtomicInteger stops = new AtomicInteger();
        WorkbenchServices services = fakeServices((method, args) -> {
            if ("executeStep".equals(method)) {
                throw new IllegalStateException("Workbench live operations require a paused interactive worker.");
            }
            if ("stopWorker".equals(method) || "restartWorker".equals(method)) {
                stops.incrementAndGet();
                return null;
            }
            if ("close".equals(method)) return null;
            return null;
        });

        WorkbenchMcpTools tools = new WorkbenchMcpTools(services, JSON);
        var result = tools.specifications().stream()
                .filter(spec -> "workbench_execute_step".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .callHandler()
                .apply(null, new McpSchema.CallToolRequest(
                        "workbench_execute_step",
                        Map.of("text", "CONTROL API TEST STEP"),
                        null
                ));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("paused interactive worker"));
        assertEquals(0, stops.get());
    }

    @Test
    void missingExecuteStepTextNamesTheToolAndTheField() {
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> null), JSON);
        var result = tools.specifications().stream()
                .filter(spec -> "workbench_execute_step".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow()
                .callHandler()
                .apply(null, new McpSchema.CallToolRequest("workbench_execute_step", Map.of(), null));
        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        assertTrue(text.contains("workbench_execute_step requires text (Gherkin step text.)"), text);
        assertTrue(text.contains("Pass a non-blank string"), text);
        assertTrue(text.contains("It was omitted."), text);
    }

    @Test
    void unknownToolPointsAtToolsList() {
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> null), JSON);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> tools.call("workbench_not_a_tool", Map.of())
        );
        assertTrue(failure.getMessage().contains("Unknown Workbench tool: workbench_not_a_tool"), failure.getMessage());
        assertTrue(failure.getMessage().contains("tools/list"), failure.getMessage());
    }

    @Test
    void stepResolveIsReadOnlyAndDoesNotExecute() throws Exception {
        AtomicBoolean resolved = new AtomicBoolean();
        AtomicBoolean executed = new AtomicBoolean();
        WorkbenchServices services = fakeServices((method, args) -> {
            if ("resolveStep".equals(method)) {
                assertEquals("Given CONTROL API TEST STEP", args[0]);
                resolved.set(true);
                return new ControlBridgeStepResolution(
                        ControlBridgeStepResolution.CONSUMER_GLUE,
                        "^CONTROL API TEST STEP$",
                        "com.example.pickleball.ControlApiSteps",
                        "controlApiTestStep",
                        "src/test/java/com/example/pickleball/ControlApiSteps.java",
                        "com.example.pickleball.ControlApiSteps.controlApiTestStep()",
                        ""
                );
            }
            if ("executeStep".equals(method)) {
                executed.set(true);
                return null;
            }
            if ("close".equals(method)) return null;
            return null;
        });

        WorkbenchMcpTools tools = new WorkbenchMcpTools(services, JSON);
        var specification = tools.specifications().stream()
                .filter(spec -> "workbench_step_resolve".equals(spec.tool().name()))
                .findFirst()
                .orElseThrow();
        assertTrue(specification.tool().description().toLowerCase().contains("without executing"));

        Object value = tools.call("workbench_step_resolve", Map.of("text", "Given CONTROL API TEST STEP"));
        assertTrue(resolved.get());
        assertFalse(executed.get());
        assertTrue(JSON.writeValueAsString(value).contains("CONSUMER_GLUE"));
    }

    @Test
    void diagnosticToolsDelegateToSharedServicesAndStaySparse() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path scenario = root.resolve("run-1/scenarios/scenario-1");
        Files.createDirectories(scenario);
        Files.writeString(root.resolve("run-catalog.json"), """
                {"runs":[{"runId":"run-1","outcome":"FAILED","runProfile":"pkb_browser=CHROME_HEADLESS"}]}
                """);
        Files.writeString(root.resolve("run-1/run-index.json"), """
                {"runId":"run-1","outcome":"FAILED","runProfile":"pkb_browser=CHROME_HEADLESS",
                 "scenarios":[{"scenarioId":"scenario-1","name":"Stay","outcome":"FAILED"}]}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"scenarioId":"scenario-1","name":"Stay","outcome":"FAILED"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"type":"step","text":"hidden events.jsonl body"}
                """);

        WorkbenchServices services = fakeServices((method, args) -> {
            if ("projectRoot".equals(method)) return project;
            if ("diagnosticCatalog".equals(method) || "diagnosticRun".equals(method)
                    || "diagnosticScenarioSummary".equals(method)) {
                throw new AssertionError(method + " is not the shared query path");
            }
            if ("emitInvestigation".equals(method)) {
                assertEquals("form-1", ((Map<?, ?>) args[0]).get("pkb_investigation_id"));
                return Map.of("reportPath", ".pickleball/investigations/form-1/report.html");
            }
            if ("close".equals(method)) return null;
            return null;
        });

        WorkbenchMcpTools tools = new WorkbenchMcpTools(services, JSON);
        assertTrue(tools.names().contains("workbench_diagnostic_catalog"));
        assertTrue(tools.names().contains("workbench_investigation_emit"));
        tools.specifications().stream()
                .filter(spec -> spec.tool().name().startsWith("workbench_diagnostic_"))
                .forEach(spec -> {
                    assertTrue(
                            spec.tool().description().contains("pkb_run_profile"),
                            spec.tool().name() + " should surface pkb_run_profile"
                    );
                    assertTrue(
                            spec.tool().description().contains("Deprecated alias"),
                            spec.tool().name() + " should name the wb_* alias"
                    );
                });
        Object catalog = tools.call("workbench_diagnostic_catalog", Map.of());
        String catalogJson = JSON.writeValueAsString(catalog);
        assertEquals(catalogJson, JSON.writeValueAsString(tools.call("wb_runs", Map.of())));
        assertTrue(catalogJson.contains("run-1"));
        assertFalse(catalogJson.contains("events.jsonl"));

        Object run = tools.call("workbench_diagnostic_run", Map.of("runId", "run-1"));
        String runJson = JSON.writeValueAsString(run);
        assertEquals(runJson, JSON.writeValueAsString(tools.call("wb_get", Map.of("runId", "run-1", "kind", "run"))));
        assertTrue(runJson.contains("FAILED"));
        assertFalse(runJson.contains("events.jsonl"));
        Object summary = tools.call("workbench_diagnostic_summary", Map.of(
                "runId", "run-1",
                "scenarioId", "scenario-1"
        ));
        String summaryJson = JSON.writeValueAsString(summary);
        assertEquals(summaryJson, JSON.writeValueAsString(tools.call(
                "wb_get", Map.of("runId", "run-1", "kind", "scenario", "scenarioId", "scenario-1")
        )));
        assertTrue(summaryJson.contains("FAILED"));
        assertFalse(summaryJson.contains("events.jsonl"));
        String log = Files.readString(project.resolve(".pickleball/agent-log"));
        assertTrue(log.contains("\t-\t-\tquery\truns\t-"), log);
        assertTrue(log.contains("\t-\trun-1\tquery\tget\t-"), log);
        Object emitted = tools.call("workbench_investigation_emit", Map.of(
                "investigation", Map.of(
                        "pkb_investigation_id", "form-1",
                        "cause", "Selector drifted."
                )
        ));
        String emittedJson = JSON.writeValueAsString(emitted);
        assertTrue(emittedJson.contains(".pickleball/investigations/form-1/report.html"));
        assertFalse(emittedJson.contains("Selector drifted."));
        assertFalse(emittedJson.contains(".png"));
    }

    @Test
    void storeForwardsMergeAndChanges() {
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
            if ("projectRoot".equals(method)) return project;
            return null;
        }), JSON);
        Object merge = tools.call("store", Map.of(
                "args", List.of("sandbox", "merge", "left", "--policy=fail", "--json")));
        assertEquals(List.of("sandbox", "merge", "left", "--policy=fail", "--json"),
                ((Map<?, ?>) merge).get("forwarded"));
        Object changes = tools.call("store", Map.of(
                "args", List.of("changes", "--since=3", "--table=files", "--limit=10", "--json")));
        assertEquals(List.of("changes", "--since=3", "--table=files", "--limit=10", "--json"),
                ((Map<?, ?>) changes).get("forwarded"));
    }

    @Test
    void consumerVerbsForwardThroughConsumerClasspath() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        tools.dscode.workbench.launch.ConsumerReadyForwarder.runner = command -> {
            seen.addAll(command);
            return new tools.dscode.workbench.launch.ConsumerReadyForwarder.Result(0, "live=true\n", "", command);
        };
        try {
            String mainClass = tools.dscode.workbench.launch.ConsumerClasspath.MAIN_CLASS;
            java.util.List<java.nio.file.Path> entries =
                    tools.dscode.workbench.launch.ConsumerClasspath.entries(project, null);
            WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
                if ("projectRoot".equals(method)) return project;
                return null;
            }), JSON);
            Object started = tools.call("workbench_start", java.util.Map.of(
                    "url", "http://127.0.0.1/page",
                    "browser", "chrome",
                    "env", "FOO=bar"));
            assertTrue(String.valueOf(((java.util.Map<?, ?>) started).get("command")).contains("start"), String.valueOf(started));
            assertTrue(seen.contains("start"));
            assertTrue(seen.contains("--url=http://127.0.0.1/page"));
            assertTrue(seen.contains("--browser=chrome"));
            assertTrue(seen.contains(mainClass));
            assertEquals("-cp", seen.get(1));
            String classpath = seen.get(2);
            assertFalse(classpath.isBlank());
            for (java.nio.file.Path entry : entries) {
                assertTrue(classpath.contains(entry.toAbsolutePath().normalize().toString()), entry.toString());
            }
        } finally {
            tools.dscode.workbench.launch.ConsumerReadyForwarder.runner = null;
        }
    }

    @Test
    void dashboardQueryForwardsLeanHistoryFilters() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        ConsumerReadyForwarder.runner = command -> {
            seen.addAll(command);
            return new ConsumerReadyForwarder.Result(
                    0,
                    "{\"ok\":true,\"op\":\"query\",\"count\":1,\"runs\":[{\"name\":\"Pay\",\"ref\":\"pay-run\"}]}",
                    "",
                    command);
        };
        try {
            WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
                if ("projectRoot".equals(method)) return project;
                return null;
            }), JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> reply = (Map<String, Object>) tools.call("workbench_dashboard_query", Map.of(
                    "status", "FAILED",
                    "element", "#save",
                    "from", "2020-01-01",
                    "limit", 5
            ));
            assertTrue(seen.contains("dashboard"), seen.toString());
            assertTrue(seen.contains("query"), seen.toString());
            assertTrue(seen.contains("--status=FAILED"), seen.toString());
            assertTrue(seen.contains("--element=#save"), seen.toString());
            assertTrue(seen.contains("--from=2020-01-01"), seen.toString());
            assertTrue(seen.contains("--limit=5"), seen.toString());
            assertEquals(0, reply.get("exit"));
            assertEquals("Pay", ((Map<?, ?>) ((List<?>) reply.get("runs")).get(0)).get("name"));
        } finally {
            ConsumerReadyForwarder.runner = null;
        }
    }

    @Test
    void dashboardXrefForwardsElementHints() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        ConsumerReadyForwarder.runner = command -> {
            seen.addAll(command);
            return new ConsumerReadyForwarder.Result(
                    0,
                    "{\"ok\":true,\"op\":\"xref\",\"count\":1,\"runs\":[{\"name\":\"Pay\",\"ref\":\"pay-run\"}],\"chat\":\"History for #save\"}",
                    "",
                    command);
        };
        try {
            WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
                if ("projectRoot".equals(method)) return project;
                return null;
            }), JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> reply = (Map<String, Object>) tools.call("workbench_dashboard_xref", Map.of(
                    "id", "#save",
                    "role", "button",
                    "name", "Save",
                    "limit", 3,
                    "window", true
            ));
            assertTrue(seen.contains("dashboard"), seen.toString());
            assertTrue(seen.contains("xref"), seen.toString());
            assertTrue(seen.contains("--id=#save"), seen.toString());
            assertTrue(seen.contains("--role=button"), seen.toString());
            assertTrue(seen.contains("--name=Save"), seen.toString());
            assertTrue(seen.contains("--limit=3"), seen.toString());
            assertTrue(seen.contains("--window"), seen.toString());
            assertEquals(0, reply.get("exit"));
            assertEquals("Pay", ((Map<?, ?>) ((List<?>) reply.get("runs")).get(0)).get("name"));
            assertEquals("History for #save", reply.get("chat"));
        } finally {
            ConsumerReadyForwarder.runner = null;
        }
    }

    @Test
    void dashboardOverlapForwardsTheTimeWindow() {
        java.util.List<String> seen = new java.util.ArrayList<>();
        ConsumerReadyForwarder.runner = command -> {
            seen.addAll(command);
            return new ConsumerReadyForwarder.Result(
                    0,
                    "{\"ok\":true,\"op\":\"overlap\",\"count\":1,\"groups\":[{\"runs\":[{\"name\":\"Pay\",\"ref\":\"pay-run\"}],\"shared\":[\"the card was declined\"]}]}",
                    "",
                    command);
        };
        try {
            WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
                if ("projectRoot".equals(method)) return project;
                return null;
            }), JSON);
            @SuppressWarnings("unchecked")
            Map<String, Object> reply = (Map<String, Object>) tools.call("workbench_dashboard_overlap", Map.of(
                    "from", "2020-02-01",
                    "to", "2020-02-02",
                    "limit", 4
            ));
            assertTrue(seen.contains("dashboard"), seen.toString());
            assertTrue(seen.contains("overlap"), seen.toString());
            assertTrue(seen.contains("--from=2020-02-01"), seen.toString());
            assertTrue(seen.contains("--to=2020-02-02"), seen.toString());
            assertTrue(seen.contains("--limit=4"), seen.toString());
            assertEquals(0, reply.get("exit"));
            assertEquals("the card was declined", ((List<?>) ((Map<?, ?>) ((List<?>) reply.get("groups")).get(0)).get("shared")).get(0));
        } finally {
            ConsumerReadyForwarder.runner = null;
        }
    }

    @Test
    void compactSnapReplyHonorsFieldsAndBudget() {
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
            if (!"agentCommand".equals(method)) return null;
            List<Map<String, Object>> items = new ArrayList<>();
            for (int index = 0; index < 40; index++) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", "s1.h" + index);
                row.put("role", "button");
                row.put("ref", "ref:data:" + "x".repeat(180) + ":" + index);
                row.put("name", "Row " + index + " " + "y".repeat(80));
                items.add(row);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("v", 1);
            body.put("ok", true);
            body.put("op", "snap");
            body.put("items", items);
            return body;
        }), JSON);
        @SuppressWarnings("unchecked")
        Map<String, Object> reply = (Map<String, Object>) tools.call("workbench_snap", Map.of(
                "target", "browser:auto",
                "fields", "id,role",
                "budget", "600"
        ));
        assertTrue(reply.get("items") instanceof List<?>);
        List<?> items = (List<?>) reply.get("items");
        assertFalse(items.isEmpty());
        assertTrue(items.size() < 40, items.size() + " rows");
        assertEquals(Boolean.TRUE, reply.get("truncated"));
        Map<?, ?> first = (Map<?, ?>) items.get(0);
        assertEquals(List.of("id", "role"), new ArrayList<>(first.keySet()));
        String json;
        try {
            json = JSON.writeValueAsString(reply);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 600, json);
    }

    @Test
    void browserPageIsALeanSnapAndForwardedRepliesAreStructured() {
        java.util.concurrent.atomic.AtomicReference<Map<String, String>> seen = new java.util.concurrent.atomic.AtomicReference<>();
        WorkbenchMcpTools tools = new WorkbenchMcpTools(fakeServices((method, args) -> {
            if ("projectRoot".equals(method)) return project;
            if (!"agentCommand".equals(method)) return null;
            @SuppressWarnings("unchecked")
            Map<String, String> flags = (Map<String, String>) args[1];
            seen.set(flags);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "s1.h1");
            row.put("role", "link");
            row.put("name", "Docs");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("op", "snap");
            body.put("items", List.of(row));
            return body;
        }), JSON);
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) tools.call("workbench_browser_page", Map.of());
        assertEquals("json", seen.get().get("format"));
        assertEquals("2048", seen.get().get("budget"));
        assertEquals("browser:auto", seen.get().get("target"));
        assertFalse(page.containsKey("out"), page.toString());
        assertEquals("Docs", ((Map<?, ?>) ((List<?>) page.get("items")).get(0)).get("name"));

        ConsumerReadyForwarder.runner = command -> {
            return new ConsumerReadyForwarder.Result(
                    0,
                    "{\"ok\":true,\"op\":\"start\",\"items\":[{\"name\":\"Save\"}]}",
                    "",
                    command);
        };
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> started = (Map<String, Object>) tools.call("workbench_start", Map.of(
                    "url", "http://127.0.0.1/page",
                    "browser", "chrome"));
            assertTrue(String.valueOf(started.get("command")).contains("start"), started.toString());
            assertEquals(0, started.get("exit"));
            assertFalse(started.containsKey("out"), started.toString());
            assertEquals("Save", ((Map<?, ?>) ((List<?>) started.get("items")).get(0)).get("name"));
        } finally {
            ConsumerReadyForwarder.runner = null;
        }
    }

    @Test
    void chatWaitAcceptsANumericTimeout() {
        assertEquals("12", WorkbenchMcpTools.timeoutText(12));
        assertEquals("0", WorkbenchMcpTools.timeoutText(0));
        assertEquals("30", WorkbenchMcpTools.timeoutText("30"));
        assertThrows(IllegalArgumentException.class, () -> WorkbenchMcpTools.timeoutText(121));
    }

    private static WorkbenchServices fakeServices(FakeInvocation invocation) {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[]{WorkbenchServices.class},
                (proxy, method, args) -> {
                    Object value = invocation.invoke(
                            method.getName(), args == null ? new Object[0] : args
                    );
                    if (value != null || method.getReturnType() == void.class) return value;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }

    private static String resultText(JsonNode response) {
        return response.at("/result/content/0/text").asText();
    }

    private static ControlBridgeStatus pausedRuntime() {
        return new ControlBridgeStatus(
                ControlProtocol.CURRENT_VERSION,
                "runtime",
                42L,
                1,
                1L,
                "scenario-1",
                "Live",
                ", verify \"left\" equals \"right\"",
                null,
                "AFTER_STEP",
                "step-signature",
                true,
                false,
                ControlProtocol.WORKER_CAPABILITIES
        );
    }

    @FunctionalInterface
    private interface FakeInvocation {
        Object invoke(String method, Object[] args);
    }

    private static final class ProcessHarness implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final ExecutorService reads;
        private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        private final Thread stderrReader;
        private boolean closed;

        private ProcessHarness(Path project) throws Exception {
            String classpath = System.getProperty("pickleball.workbench.test.classpath");
            if (classpath == null || classpath.isBlank()) {
                throw new IllegalStateException("pickleball.workbench.test.classpath was not configured by Gradle.");
            }
            String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                    .toString();
            process = new ProcessBuilder(
                    java,
                    "-cp",
                    classpath,
                    "tools.dscode.workbench.WorkbenchApplication",
                    "mcp",
                    project.toString()
            ).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            reads = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "workbench-mcp-test-stdout");
                thread.setDaemon(true);
                return thread;
            });
            stderrReader = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(stderr);
                } catch (Exception ignored) {
                    // Process shutdown closes the stream.
                }
            }, "workbench-mcp-test-stderr");
            stderrReader.setDaemon(true);
            stderrReader.start();
        }

        private void initialize() throws Exception {
            sendRequest(1, "initialize", """
                    {
                      "protocolVersion":"2025-11-25",
                      "capabilities":{},
                      "clientInfo":{"name":"workbench-test","version":"1.0"}
                    }
                    """);
            JsonNode initialized = readResponse(STARTUP_TIMEOUT);
            assertEquals("pickleball-workbench", initialized.at("/result/serverInfo/name").asText());
            assertTrue(initialized.at("/result/capabilities/tools").isObject());
            notification("notifications/initialized", "{}");
        }

        private JsonNode request(int id, String method, String paramsJson) throws Exception {
            sendRequest(id, method, paramsJson);
            return readResponse(RESPONSE_TIMEOUT);
        }

        private void sendRequest(int id, String method, String paramsJson) throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"id\":" + id
                    + ",\"method\":" + JSON.writeValueAsString(method)
                    + ",\"params\":" + paramsJson + "}");
        }

        private JsonNode toolCall(int id, String name, String argumentsJson) throws Exception {
            return request(id, "tools/call", "{\"name\":" + JSON.writeValueAsString(name)
                    + ",\"arguments\":" + argumentsJson + "}");
        }

        private void notification(String method, String paramsJson) throws Exception {
            send("{\"jsonrpc\":\"2.0\",\"method\":" + JSON.writeValueAsString(method)
                    + ",\"params\":" + paramsJson + "}");
        }

        private void send(String json) throws Exception {
            String frame = JSON.writeValueAsString(JSON.readTree(json));
            if (frame.indexOf('\n') >= 0 || frame.indexOf('\r') >= 0) {
                throw new AssertionError("MCP stdio frame contains an embedded newline: " + frame);
            }
            writer.write(frame);
            writer.newLine();
            writer.flush();
        }

        private JsonNode readResponse(Duration timeout) throws Exception {
            try {
                String line = callWithTimeout(reads, reader::readLine, timeout);
                assertNotNull(line, () -> "MCP server closed stdout before returning a response. stderr=" + stderrText());
                return JSON.readTree(line);
            } catch (java.util.concurrent.TimeoutException failure) {
                throw new AssertionError(
                        "Timed out waiting for MCP response after " + timeout.toSeconds()
                                + "s. processAlive=" + process.isAlive()
                                + ", stderr=" + stderrText(),
                        failure
                );
            }
        }

        @Override
        public void close() throws Exception {
            if (closed) return;
            closed = true;
            try {
                writer.close();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroy();
                    if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                    throw new AssertionError("Workbench MCP process did not exit after stdin closed. stderr=" + stderrText());
                }
                assertEquals(0, process.exitValue(), () -> "Workbench MCP process failed. stderr=" + stderrText());
                String extra = callWithTimeout(reads, reader::readLine, Duration.ofSeconds(1));
                assertTrue(extra == null || extra.isBlank(), () -> "Non-protocol/extra MCP stdout: " + extra);
            } finally {
                reads.shutdownNow();
                process.destroyForcibly();
                reader.close();
            }
        }

        private String stderrText() {
            return stderr.toString(StandardCharsets.UTF_8);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }
    }

    private static <T> T callWithTimeout(
            ExecutorService executor,
            Callable<T> action,
            Duration timeout
    ) throws Exception {
        Future<T> future = executor.submit(action);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }
}
