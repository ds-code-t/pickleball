package tools.dscode.engine.parity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.coredefinitions.LogAndAssertSteps;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.source.StoreScenarioSource;
import tools.dscode.store.StoreHydrator;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlConnections;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParityHarnessTest {
    @TempDir
    Path project;

    private final Map<String, String> previous = new LinkedHashMap<>();

    @AfterEach
    void releaseStore() throws Exception {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        previous.clear();
        if (project != null) {
            StoreScope.clear();
            SqlConnections.closeProject(project);
        }
    }

    @Test
    void frameworkAssertMatchesAndSuitesAreInTheHarness() throws Exception {
        String assertFeature = """
                Feature: Assert
                  Scenario: One
                    * ASSERT: "a" equals "a"
                """;
        List<DualRun.Divergence> framework = ParityHarness.compare(assertFeature, LogAndAssertSteps.class);
        assertTrue(framework.isEmpty(), framework.toString());
        assertFalse(ParityHarness.featuresUnder(Path.of("src/test/resources")).isEmpty());
        assertFalse(ParityHarness.featuresUnder(Path.of("maven-consumer-project/src/test/resources/features")).isEmpty());
    }

    @Test
    void storedRowsMatchOnBothEngines() throws Exception {
        previous.put("pkb_store", System.getProperty("pkb_store"));
        previous.put("pkb_store_project", System.getProperty("pkb_store_project"));
        System.setProperty("pkb_store", "true");
        System.setProperty("pkb_store_project", project.toAbsolutePath().normalize().toString());
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
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        Files.delete(feature);
        String relative = "src/test/resources/features/rows.feature";
        List<EngineFeature> loaded = new StoreScenarioSource(project, relative).load();
        assertEquals(1, loaded.size());
        assertEquals(1, loaded.getFirst().scenarios().size());
        assertEquals("Kept", loaded.getFirst().scenarios().getFirst().name());
        List<DualRun.Divergence> diff = ParityHarness.compareStored(project, relative, LogAndAssertSteps.class);
        assertTrue(diff.isEmpty(), diff.toString());
    }
}
