package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticQueryTest {
    @TempDir
    Path project;

    @Test
    void eachOpReadsTheFixtureAndStaysUnderTheDefaultBudget() throws Exception {
        writeFixture();
        DiagnosticQuery.Outcome runs = DiagnosticQuery.query(project, Map.of("op", "runs"));
        assertEquals(0, runs.exit());
        assertTrue(runs.json().getBytes(StandardCharsets.UTF_8).length <= DiagnosticQuery.DEFAULT_BUDGET_BYTES, runs.json());
        assertTrue(runs.json().contains("\"runId\":\"run-1\""));
        assertTrue(runs.json().contains("\"where\":\"reports/diagnostic-runs/run-1/run-index.json\"")
                || runs.json().contains("run-index.json"));
        assertFalse(runs.json().contains("/workspace"));
        assertFalse(runs.json().contains("at tools.dscode"));

        DiagnosticQuery.Outcome got = DiagnosticQuery.query(project, Map.of("op", "get", "runId", "run-1", "kind", "scenario", "scenarioId", "scenario-1"));
        assertEquals(0, got.exit());
        assertTrue(got.json().contains("\"name\":\"Stay put\""));
        assertTrue(got.json().contains("summary.json"));

        DiagnosticQuery.Outcome events = DiagnosticQuery.query(project, Map.of(
                "op", "query", "runId", "run-1", "kind", "event", "limit", 10
        ));
        assertEquals(0, events.exit());
        assertTrue(events.json().contains("navigate"));
        assertFalse(events.json().contains("secret-stack-line-2"));

        DiagnosticQuery.Outcome steps = DiagnosticQuery.query(project, Map.of(
                "op", "get", "runId", "run-1", "kind", "step", "id", "2"
        ));
        assertEquals(0, steps.exit());
        assertTrue(steps.json().contains("Then stay"));
        assertTrue(steps.json().contains("\"stackHead\":\"java.lang.AssertionError: left\""));
        assertFalse(steps.json().contains("Frame.java"));

        DiagnosticQuery.Outcome search = DiagnosticQuery.query(project, Map.of("op", "search", "text", "café"));
        assertEquals(0, search.exit());
        assertTrue(search.json().contains("caf"));

        DiagnosticQuery.Outcome props = DiagnosticQuery.query(project, Map.of("op", "props", "runId", "run-1"));
        assertEquals(0, props.exit());
        assertTrue(props.json().contains("pkb_browser"));
        assertTrue(props.json().contains("CHROME_HEADLESS"));
        assertTrue(props.json().contains("\"key\":\"color\""));

        DiagnosticQuery.Outcome errors = DiagnosticQuery.query(project, Map.of("op", "errors", "runId", "run-1"));
        assertEquals(0, errors.exit());
        assertTrue(errors.json().contains("AssertionError") || errors.json().contains("left"));
        assertTrue(errors.json().contains("cluster-title") || errors.json().contains("\"kind\":\"error\""));
        assertFalse(errors.json().contains(".png"));
    }

    @Test
    void cursorTruncationAndFieldProjection() throws Exception {
        StringBuilder runs = new StringBuilder("{\"runs\":[");
        for (int index = 0; index < 25; index++) {
            if (index > 0) runs.append(',');
            runs.append("{\"runId\":\"run-").append(index).append("\",\"outcome\":\"PASSED\"}");
        }
        runs.append("]}");
        Path root = project.resolve("reports/diagnostic-runs");
        Files.createDirectories(root);
        Files.writeString(root.resolve("run-catalog.json"), runs.toString());

        DiagnosticQuery.Outcome first = DiagnosticQuery.query(project, Map.of("op", "runs", "limit", 10));
        assertTrue(first.json().contains("\"truncated\":\"…+15 more, use cursor\""), first.json());
        assertTrue(first.json().contains("\"nextCursor\":\"10\""));
        DiagnosticQuery.Outcome second = DiagnosticQuery.query(project, Map.of("op", "runs", "limit", 10, "cursor", "10"));
        assertTrue(second.json().contains("run-10"), second.json());
        assertFalse(second.json().contains("run-0\""));
        assertTrue(second.json().contains("…+5 more, use cursor"));

        DiagnosticQuery.Outcome fields = DiagnosticQuery.query(project, Map.of(
                "op", "runs", "limit", 1, "fields", "runId,outcome"
        ));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projected = (List<Map<String, Object>>) fields.body().get("items");
        assertEquals(java.util.Set.of("runId", "outcome"), projected.getFirst().keySet());
        assertFalse(projected.getFirst().containsKey("where"));
    }

    @Test
    void defaultBudgetDropsItemsInsteadOfGrowing() throws Exception {
        StringBuilder runs = new StringBuilder("{\"runs\":[");
        for (int index = 0; index < 30; index++) {
            if (index > 0) runs.append(',');
            runs.append("{\"runId\":\"budget-").append(index).append("\",\"outcome\":\"PASSED\",\"purpose\":\"")
                    .append("x".repeat(80)).append("\"}");
        }
        runs.append("]}");
        Path root = project.resolve("reports/diagnostic-runs");
        Files.createDirectories(root);
        Files.writeString(root.resolve("run-catalog.json"), runs.toString());
        DiagnosticQuery.Outcome outcome = DiagnosticQuery.query(project, Map.of("op", "runs"), 280);
        assertEquals(0, outcome.exit());
        assertTrue(outcome.json().getBytes(StandardCharsets.UTF_8).length <= 280
                || ((List<?>) outcome.body().get("items")).size() <= 1, outcome.json());
        assertTrue(outcome.json().contains("…+"));
        assertTrue(outcome.json().contains("more, use cursor"));
    }

    @Test
    void privateIndexWinsAndWhereNamesThatFile() throws Exception {
        Path index = project.resolve(".pickleball/runs/run-private/diagnostic/run-private/run-index.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, "{\"runId\":\"run-private\",\"outcome\":\"FAILED\",\"failureSummary\":\"private boom\"}");
        Path shared = project.resolve("reports/diagnostic-runs/run-private/run-index.json");
        Files.createDirectories(shared.getParent());
        Files.writeString(shared, "{\"runId\":\"run-private\",\"outcome\":\"PASSED\"}");
        Files.createDirectories(project.resolve("reports/diagnostic-runs"));
        Files.writeString(project.resolve("reports/diagnostic-runs/run-catalog.json"),
                "{\"runs\":[{\"runId\":\"run-private\",\"outcome\":\"PASSED\"}]}");
        DiagnosticQuery.Outcome got = DiagnosticQuery.query(project, Map.of("op", "get", "runId", "run-private"));
        assertTrue(got.json().contains("FAILED"), got.json());
        assertTrue(got.json().contains(".pickleball/runs/run-private/diagnostic/run-private/run-index.json"), got.json());
        DiagnosticQuery.Outcome errors = DiagnosticQuery.query(project, Map.of("op", "errors", "runId", "run-private"));
        assertTrue(errors.json().contains("private boom"), errors.json());
    }

    @Test
    void usageIsStructured() {
        DiagnosticQuery.Outcome missing = DiagnosticQuery.query(project, Map.of("op", "search"));
        assertEquals(2, missing.exit());
        assertEquals("missing text", missing.body().get("error"));
        assertTrue(String.valueOf(missing.body().get("fix")).contains("query search"));
        DiagnosticQuery.Outcome bad = DiagnosticQuery.query(project, new LinkedHashMap<>(Map.of("op", "runs", "limit", 0)));
        assertEquals(2, bad.exit());
        assertEquals("bad limit", bad.body().get("error"));
    }

    @Test
    void jsonLiteRoundTripKeepsStringsAndNumbers() {
        Object parsed = JsonLite.parse("{\"n\":2,\"t\":\"a\\nb\",\"ok\":true,\"z\":null}");
        assertEquals("{\"n\":2,\"t\":\"a\\nb\",\"ok\":true,\"z\":null}", JsonLite.write(parsed));
    }

    @Test
    void workerLogsAreQueryableWithTheShortLog() throws Exception {
        Path log = project.resolve(".pickleball/workbench/logs/worker-1.err.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "compile\nworker-token-gamma failed\n");
        DiagnosticQuery.Outcome logs = DiagnosticQuery.query(project, Map.of("op", "query", "kind", "log", "text", "worker-token-gamma"));
        assertEquals(0, logs.exit(), logs.json());
        assertTrue(logs.json().contains("worker-token-gamma"), logs.json());
        assertTrue(logs.json().contains("\"source\":\"worker\""), logs.json());
        assertTrue(logs.json().contains("worker-1.err.log"), logs.json());
    }

    private void writeFixture() throws Exception {
        Path root = project.resolve("reports/diagnostic-runs");
        Path scenario = root.resolve("run-1/scenarios/scenario-1");
        Files.createDirectories(scenario.resolve("screenshots"));
        Files.writeString(root.resolve("run-catalog.json"), """
                {"schemaVersion":1,"runs":[
                  {"runId":"run-1","outcome":"FAILED","lineage":{"runPurpose":"workbench-discover"}},
                  {"runId":"run-2","outcome":"PASSED","purpose":"quiet"}
                ]}
                """);
        Files.writeString(root.resolve("run-1/run-index.json"), """
                {"runId":"run-1","outcome":"FAILED","counts":{"total":1,"failed":1},
                 "runProfile":"pkb_browser=CHROME_HEADLESS, pkb_parallel=4",
                 "failureSummary":"Stay put failed",
                 "scenarios":[{"scenarioId":"scenario-1","name":"Stay put","outcome":"FAILED"}]}
                """);
        Files.writeString(root.resolve("run-1/clusters.json"), """
                {"clusters":[{"id":"c1","title":"cluster-title","message":"cluster-title"}]}
                """);
        Files.writeString(root.resolve("run-1/environment.json"), """
                {"color":"café","nested":{"skip":true}}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"scenarioId":"scenario-1","name":"Stay put","outcome":"FAILED","lastStepText":"Then stay"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"type":"step","eventSeq":1,"text":"Given navigate","status":"PASSED"}
                {"type":"step","eventSeq":2,"text":"Then stay","status":"FAILED","message":"left","stack":"java.lang.AssertionError: left\\nat tools.dscode.Frame.stay(Frame.java:9)\\nmore"}
                {"type":"log","eventSeq":3,"level":"INFO","text":"saw café"}
                """);
        Files.write(scenario.resolve("screenshots/frame-1.png"), new byte[]{1, 2, 3});
        Files.writeString(scenario.resolve("trace.jsonl.gz"), "not-read");
        Path log = project.resolve(".pickleball/agent-log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "2026-01-01T00:00:00Z\tada\trun-1\tnote\tcafé log\t-\n");
    }
}
