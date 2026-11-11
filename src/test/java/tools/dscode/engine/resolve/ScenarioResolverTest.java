package tools.dscode.engine.resolve;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dalton.DaltonHydrator;
import tools.dscode.engine.source.DaltonScenarioSource;
import tools.dscode.engine.source.MemoryScenarioSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioResolverTest {
    private static final String FEATURE = """
            @all
            Feature: Box
              Scenario: Alpha
                Given background marker
              Scenario Outline: Beta
                Given hello <Name>
                Examples:
                  | Name |
                  | Ada  |
                  | Grace |
              @spot
              Scenario: Gamma
                Given other
            """;

    @Test
    void resolvesTagsNameFeatureExampleMarkerOrderAndLimit() {
        MemoryScenarioSource source = new MemoryScenarioSource(FEATURE);
        List<ScenarioResolver.Hit> tagged = ScenarioResolver.resolve(source, new ScenarioResolver.Query("@spot", null, "Box", null, null, null, null));
        assertEquals("Gamma", tagged.getFirst().scenario().name());
        List<ScenarioResolver.Hit> named = ScenarioResolver.resolve(source, ScenarioResolver.Query.of(null, "Bet", "Box", null));
        assertEquals(2, named.size());
        List<ScenarioResolver.Hit> second = ScenarioResolver.resolve(source, ScenarioResolver.Query.of(null, "Beta", null, "2"));
        assertEquals("Grace", second.getFirst().scenario().metadata().get("exampleValues"));
        List<ScenarioResolver.Hit> marker = ScenarioResolver.resolve(source, new ScenarioResolver.Query(null, null, null, null, "hello <Name>", "lexical", 1));
        assertEquals(1, marker.size());
        assertThrows(IllegalArgumentException.class, () -> ScenarioResolver.resolve(source, ScenarioResolver.Query.of("@missing", null, null, null)));
        assertTrue(ScenarioResolver.resolve(source, new ScenarioResolver.Query(null, null, null, null, null, null, null)).isEmpty());
    }

    @Test
    void resolvesADaltonRow(@TempDir Path project) throws Exception {
        Path file = project.resolve("features/box.feature");
        Files.createDirectories(file.getParent());
        Files.writeString(file, FEATURE);
        DaltonHydrator.hydrate(project, DaltonHydrator.Options.full());
        List<ScenarioResolver.Hit> hits = ScenarioResolver.resolve(
                new DaltonScenarioSource(project, "features/box.feature"),
                ScenarioResolver.Query.of("@all", "Alpha", "Box", null)
        );
        assertEquals("Alpha", hits.getFirst().scenario().name());
    }
}
