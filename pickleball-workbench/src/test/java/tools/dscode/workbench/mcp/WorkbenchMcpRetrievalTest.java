package tools.dscode.workbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.WorkbenchServices;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchMcpRetrievalTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void sixToolsAreThinForwardersOverTheSameQuery() throws Exception {
        Path scenario = project.resolve("reports/diagnostic-runs/run-9/scenarios/scene-9");
        Files.createDirectories(scenario.resolve("screenshots"));
        Files.writeString(project.resolve("reports/diagnostic-runs/run-catalog.json"), """
                {"runs":[{"runId":"run-9","outcome":"FAILED","lineage":{"runPurpose":"retrieval"}}]}
                """);
        Files.writeString(project.resolve("reports/diagnostic-runs/run-9/run-index.json"), """
                {"runId":"run-9","outcome":"FAILED","runProfile":"pkb_browser=EDGE, pkb_parallel=2",
                 "failureSummary":"scene failed","scenarios":[{"scenarioId":"scene-9","name":"Scene","outcome":"FAILED"}]}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"name":"Scene","outcome":"FAILED"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"type":"step","eventSeq":4,"text":"Then the café check","status":"FAILED","stack":"java.lang.AssertionError: café\\n\\tat Hidden.frame(Hidden.java:3)"}
                """);
        Files.write(scenario.resolve("screenshots/shot.png"), new byte[]{4});
        Files.createDirectories(project.resolve(".pickleball"));
        Files.writeString(project.resolve(".pickleball/agent-log"), "t\tada\trun-9\tnote\tcafé from the log\t-\n");

        WorkbenchMcpTools tools = new WorkbenchMcpTools(services(project), JSON);
        for (String name : java.util.List.of("wb_runs", "wb_get", "wb_query", "wb_search", "wb_props", "wb_errors")) {
            String description = tools.specifications().stream()
                    .filter(spec -> name.equals(spec.tool().name()))
                    .findFirst()
                    .orElseThrow()
                    .tool()
                    .description();
            assertFalse(description.contains("\n"), description);
            assertTrue(description.length() < 180, description);
        }

        String runs = JSON.writeValueAsString(tools.call("wb_runs", Map.of()));
        assertTrue(runs.getBytes(StandardCharsets.UTF_8).length <= 4096, runs);
        assertTrue(runs.contains("run-9"));
        assertTrue(runs.contains("\"where\""));
        assertFalse(runs.contains("shot.png"));

        String got = JSON.writeValueAsString(tools.call("wb_get", Map.of("runId", "run-9", "kind", "scenario", "scenarioId", "scene-9")));
        assertTrue(got.contains("Scene"));

        String query = JSON.writeValueAsString(tools.call("wb_query", Map.of("runId", "run-9", "kind", "step", "limit", 5)));
        assertTrue(query.contains("café check"));
        assertFalse(query.contains("Hidden.java"));

        String search = JSON.writeValueAsString(tools.call("wb_search", Map.of("text", "café")));
        assertTrue(search.contains("café"));

        String props = JSON.writeValueAsString(tools.call("wb_props", Map.of("runId", "run-9")));
        assertTrue(props.contains("pkb_browser"));
        assertTrue(props.contains("EDGE"));

        String errors = JSON.writeValueAsString(tools.call("wb_errors", Map.of("runId", "run-9")));
        assertTrue(errors.contains("AssertionError"));
        assertTrue(errors.contains("…") || errors.contains("stackHead") || errors.contains("scene failed"));
        assertFalse(errors.contains("Hidden.java"));

        var spec = tools.specifications().stream()
                .filter(item -> "wb_get".equals(item.tool().name()))
                .findFirst()
                .orElseThrow();
        McpSchema.CallToolResult rejected = spec.callHandler().apply(null, new McpSchema.CallToolRequest(
                "wb_get",
                Map.of(),
                null
        ));
        assertEquals(Boolean.TRUE, rejected.isError());
        String text = ((McpSchema.TextContent) rejected.content().getFirst()).text();
        assertTrue(text.contains("\"error\""));
        assertTrue(text.contains("\"fix\""));
        assertTrue(text.contains("run-id") || text.contains("runId"));

        assertThrows(WorkbenchMcpTools.ToolFailure.class, () -> tools.call("wb_props", Map.of()));
    }

    private static WorkbenchServices services(Path project) {
        return (WorkbenchServices) Proxy.newProxyInstance(
                WorkbenchServices.class.getClassLoader(),
                new Class<?>[]{WorkbenchServices.class},
                (proxy, method, args) -> {
                    if ("projectRoot".equals(method.getName())) return project;
                    if ("close".equals(method.getName())) return null;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
        );
    }
}
