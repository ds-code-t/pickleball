package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rebuild parity is the Win/Mac/Linux check. sqlite-jdbc ships the native library
 * for those three hosts; this test uses only {@link Path} and does not branch on OS.
 */
class DiagnosticSqliteIndexTest {
    @TempDir
    Path project;

    @Test
    void rebuildMatchesFilesAndFtsFindsTheSameTokenAfterDelete() throws Exception {
        assertTrue(DiagnosticSqliteIndex.driverPresent(), "sqlite-jdbc is a test runtime dependency");
        Path scenario = project.resolve("reports/diagnostic-runs/run-sql/scenarios/scene-sql");
        Files.createDirectories(scenario);
        Files.writeString(project.resolve("reports/diagnostic-runs/run-catalog.json"), """
                {"runs":[{"runId":"run-sql","outcome":"FAILED","purpose":"sqlite-token-alpha"}]}
                """);
        Files.writeString(project.resolve("reports/diagnostic-runs/run-sql/run-index.json"), """
                {"runId":"run-sql","outcome":"FAILED","failureSummary":"sqlite-token-alpha broke",
                 "runProfile":"pkb_browser=CHROME_HEADLESS","scenarios":[{"scenarioId":"scene-sql","name":"Scene","outcome":"FAILED"}]}
                """);
        Files.writeString(scenario.resolve("summary.json"), """
                {"name":"Scene","outcome":"FAILED"}
                """);
        Files.writeString(scenario.resolve("events.jsonl"), """
                {"type":"step","eventSeq":1,"text":"Then sqlite-token-alpha","status":"FAILED","message":"sqlite-token-alpha","stack":"java.lang.AssertionError: sqlite-token-alpha\\n\\tat Hidden.frame(Hidden.java:4)"}
                """);
        Files.createDirectories(project.resolve(".pickleball"));
        Files.writeString(project.resolve(".pickleball/agent-log"), "t\tada\trun-sql\tnote\tsqlite-token-alpha log\t-\n");

        List<String> expected = collectedBodies();
        assertFalse(expected.isEmpty());
        int inserted = DiagnosticSqliteIndex.rebuild(project);
        assertTrue(inserted > 0, "inserted " + inserted);
        assertTrue(Files.isRegularFile(DiagnosticSqliteIndex.database(project)));
        List<String> stored = new ArrayList<>(DiagnosticSqliteIndex.storedBodies(project));
        assertEquals(sorted(expected), sorted(stored));

        DiagnosticQuery.Outcome search = DiagnosticQuery.query(project, Map.of("op", "search", "text", "sqlite-token-alpha"));
        assertEquals(0, search.exit());
        assertTrue(search.json().contains("sqlite-token-alpha"), search.json());
        assertTrue(search.json().contains("diagnostics.db"), search.json());
        assertFalse(search.json().contains("Hidden.java"));

        Files.delete(DiagnosticSqliteIndex.database(project));
        assertFalse(Files.isRegularFile(DiagnosticSqliteIndex.database(project)));
        DiagnosticQuery.Outcome files = DiagnosticQuery.query(project, Map.of("op", "search", "text", "sqlite-token-alpha"));
        assertTrue(files.json().contains("sqlite-token-alpha"), files.json());
        assertFalse(files.json().contains("diagnostics.db"));

        assertEquals(inserted, DiagnosticSqliteIndex.rebuild(project));
        assertEquals(sorted(expected), sorted(DiagnosticSqliteIndex.storedBodies(project)));
    }

    private List<String> collectedBodies() {
        List<String> bodies = new ArrayList<>();
        for (Map<String, Object> request : List.of(
                page("runs", null),
                page("query", "scenario"),
                page("query", "event"),
                page("errors", null),
                page("query", "property"),
                page("query", "artifact"),
                page("query", "log")
        )) {
            DiagnosticQuery.Outcome outcome = DiagnosticQuery.query(project, request);
            assertEquals(0, outcome.exit(), outcome.json());
            Object rows = outcome.body().get("items");
            if (!(rows instanceof List<?> items)) continue;
            for (Object row : items) bodies.add(JsonLite.write(row));
        }
        return bodies;
    }

    private static Map<String, Object> page(String op, String kind) {
        java.util.LinkedHashMap<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("op", op);
        request.put("unpaged", Boolean.TRUE);
        request.put("limit", 100_000);
        if (kind != null) request.put("kind", kind);
        return request;
    }

    private static List<String> sorted(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }
}
