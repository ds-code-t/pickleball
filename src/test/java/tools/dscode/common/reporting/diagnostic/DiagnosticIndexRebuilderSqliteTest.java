package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.DiagnosticQuery;
import tools.dscode.control.protocol.DiagnosticSqliteIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticIndexRebuilderSqliteTest {
    @TempDir
    Path project;

    @Test
    void catalogRebuildWritesADisposableIndexWithoutChangingTheJson() throws Exception {
        Path runs = project.resolve("reports/diagnostic-runs");
        Path run = runs.resolve("run-hook");
        Files.createDirectories(run.resolve("scenarios/scene"));
        Files.writeString(run.resolve("run-index.json"), """
                {"runId":"run-hook","outcome":"FAILED","failureSummary":"hook-token-beta"}
                """);
        Files.writeString(run.resolve("scenarios/scene/summary.json"), """
                {"name":"Hook","outcome":"FAILED"}
                """);
        DiagnosticIndexRebuilder.rebuildRunCatalog(runs);
        String catalog = Files.readString(runs.resolve("run-catalog.json"));
        assertTrue(catalog.contains("run-hook"), catalog);
        assertTrue(Files.isRegularFile(runs.resolve("diagnostics.db")));
        DiagnosticQuery.Outcome search = DiagnosticQuery.query(project, Map.of("op", "search", "text", "hook-token-beta"));
        assertEquals(0, search.exit(), search.json());
        assertTrue(search.json().contains("hook-token-beta"), search.json());
        assertTrue(search.json().contains("diagnostics.db"), search.json());

        Files.delete(DiagnosticSqliteIndex.database(project));
        Files.writeString(run.resolve("manifest.json"), """
                {"runId":"run-hook","outcome":"FAILED"}
                """);
        DiagnosticIndexRebuilder.rebuildRunIndex(run);
        assertTrue(Files.isRegularFile(DiagnosticSqliteIndex.database(project)));
        assertTrue(Files.readString(run.resolve("run-index.json")).contains("run-hook"));
    }
}
