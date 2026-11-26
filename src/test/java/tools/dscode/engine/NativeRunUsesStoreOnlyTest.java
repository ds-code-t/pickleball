package tools.dscode.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.store.StoreHydrator;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlConnections;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeRunUsesStoreOnlyTest {
    @TempDir
    Path project;

    private final Map<String, String> previous = new LinkedHashMap<>();

    @AfterEach
    void release() throws Exception {
        restore();
        StoreScope.clear();
        SqlConnections.closeProject(project);
    }

    @Test
    void deletedFeatureStillRunsFromTheStore() throws Exception {
        pinStore();
        Path feature = writeFeature();
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        Files.delete(feature);
        assertTrue(Files.notExists(feature));

        RunResult result = PickleballEngine.run(
                new FileScenarioSource(feature),
                EngineOptions.of().project(project));

        assertEquals(Status.PASSED, result.status(), result.toString());
        assertEquals(1, result.scenarios().size(), result.toString());
        assertEquals("Kept", result.scenarios().getFirst().name());
        assertTrue(result.scenarios().getFirst().steps().stream().anyMatch(step ->
                step.text().contains("ASSERT: \"a\" equals \"a\"") && step.status() == Status.PASSED), result.toString());
        String log = Files.readString(project.resolve(".pickleball/engine/engine.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains("Hidden"), log);
        assertTrue(log.contains("skipped enabled=0"), log);
    }

    @Test
    void missingRowNamesStoreHydrate() throws Exception {
        pinStore();
        Path feature = writeFeature();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                PickleballEngine.run(new FileScenarioSource(feature), EngineOptions.of().project(project)));
        assertTrue(failure.getMessage().contains("error:"), failure.getMessage());
        assertTrue(failure.getMessage().contains("store hydrate"), failure.getMessage());
        assertTrue(Files.exists(feature));
    }

    private void pinStore() {
        set("pkb_store", "true");
        set("pkb_store_project", project.toAbsolutePath().normalize().toString());
        set("pkb_engine_log", "ingest");
    }

    private Path writeFeature() throws Exception {
        Path feature = project.resolve("src/test/resources/features/rows.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, """
                Feature: Rows
                  Scenario: Kept
                    * ASSERT: "a" equals "a"
                  @pkb-ignore
                  Scenario: Hidden
                    * ASSERT: "hidden" equals "visible"
                """, StandardCharsets.UTF_8);
        return feature;
    }

    private void set(String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void restore() {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        previous.clear();
    }
}
