package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.SqlDialect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recording four steps must reuse the definition catalog and the ready schema.
 * A wall-clock bound is only a supplement to those counts.
 */
class StoreRunRecordingSpeedTest {
    @TempDir
    Path project;

    @Test
    void fourStepsReuseTheDefinitionCatalog() throws Exception {
        Path java = project.resolve("src/test/java/demo/Steps.java");
        Files.createDirectories(java.getParent());
        StringBuilder source = new StringBuilder("package demo;\npublic class Steps {\n");
        for (int i = 0; i < 40; i++) {
            source.append("    @When(\"log line ").append(i).append(" is {int}\")\n");
            source.append("    public void log").append(i).append("(int amount) {}\n");
        }
        source.append("    @When(\"the total is {int}\")\n    public void total(int amount) {}\n}\n");
        Files.writeString(java, source.toString(), StandardCharsets.UTF_8);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        String linked;
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement count = database.connection().prepareStatement(
                     "SELECT definition_id FROM step_definitions WHERE pattern = 'the total is {int}' AND removed = 0")) {
            try (var rows = count.executeQuery()) {
                assertTrue(rows.next(), "definitions were not stored");
                linked = rows.getString(1);
            }
        }
        String track = System.getProperty("pkb_store_track");
        String run = System.getProperty("pkb_run_id");
        String root = System.getProperty("pkb_store_project");
        int loadsBefore = StoreExecution.definitionLoads();
        int fullBefore = SqlDialect.schemaFullEnsures();
        int fastBefore = SqlDialect.schemaFastEnsures();
        long started = System.nanoTime();
        try {
            System.setProperty("pkb_store_track", "true");
            System.setProperty("pkb_run_id", "fast-run");
            System.setProperty("pkb_store_project", project.toAbsolutePath().normalize().toString());
            for (int step = 0; step < 4; step++) {
                StoreExecution.beforeStep(project, "Pay", "features/pay.feature", 2, "the total is 10", 3 + step);
                StoreExecution.afterStep("PASSED");
            }
            StoreExecution.finishScenario("PASSED");
        } finally {
            restore("pkb_store_track", track);
            restore("pkb_run_id", run);
            restore("pkb_store_project", root);
            StoreExecution.finishScenario("PASSED");
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(1, StoreExecution.definitionLoads() - loadsBefore, "definition catalog loaded more than once");
        assertEquals(0, SqlDialect.schemaFullEnsures() - fullBefore, "recording ran full schema DDL");
        assertTrue(SqlDialect.schemaFastEnsures() - fastBefore >= 4, "schema fast path did not run");
        assertTrue(millis < 5_000L, "recording took " + millis + " ms");
        var steps = StoreExecution.steps(project, "fast-run");
        assertEquals(4, steps.size(), steps.toString());
        assertEquals("the total is 10", steps.get(0).stepText());
        assertEquals(linked, steps.get(0).definitionId(), steps.toString());
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
