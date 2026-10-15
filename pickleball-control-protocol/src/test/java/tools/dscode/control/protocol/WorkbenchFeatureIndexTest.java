package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchFeatureIndexTest {
    @TempDir
    Path project;

    @Test
    void cacheHitMissAndHashInvalidation() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("login.feature"), """
                @smoke
                Feature: Sign in
                  Scenario: Valid password
                    Given a user
                """);
        Files.writeString(features.resolve("other.feature"), """
                Feature: Other
                  Scenario: Stay
                    Given stay
                """);

        WorkbenchFeatureIndex.Selection first = select("@smoke", null, null, List.of(), false);
        assertEquals(1, first.hits().size());
        assertEquals("Valid password", first.hits().getFirst().scenarioName());
        assertEquals(2, first.filesIndexed());
        assertEquals(2, first.filesReparsed());
        assertEquals(0, first.cacheHits());
        assertTrue(first.narrowed());
        assertTrue(first.featureProperty().contains("login.feature"));
        assertFalse(first.featureProperty().contains("other.feature"));

        WorkbenchFeatureIndex.Selection hit = select("@smoke", null, null, List.of(), false);
        assertEquals(0, hit.filesReparsed());
        assertEquals(2, hit.cacheHits());
        assertEquals(first.hits(), hit.hits());

        Files.writeString(features.resolve("extra.feature"), """
                @smoke
                Feature: Extra
                  Scenario: Also smoke
                    Given more
                """);
        WorkbenchFeatureIndex.Selection miss = select("@smoke", null, null, List.of(), false);
        assertEquals(1, miss.filesReparsed());
        assertEquals(2, miss.cacheHits());
        assertEquals(2, miss.hits().size());

        Files.writeString(features.resolve("login.feature"), """
                @smoke
                Feature: Sign in
                  Scenario: Valid password renamed
                    Given a user
                """);
        WorkbenchFeatureIndex.Selection invalidated = select("@smoke", null, null, List.of(), false);
        assertEquals(1, invalidated.filesReparsed());
        assertTrue(invalidated.hits().stream().anyMatch(item -> item.scenarioName().equals("Valid password renamed")));
        assertFalse(invalidated.hits().stream().anyMatch(item -> item.scenarioName().equals("Valid password")));
    }

    @Test
    void refreshReparsesEveryFileAndCorruptCacheIsIgnored() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("one.feature"), """
                Feature: One
                  Scenario: Alpha
                    Given a
                """);
        select(null, null, null, List.of(), false);
        WorkbenchFeatureIndex.Selection refresh = select(null, null, null, List.of(), true);
        assertEquals(1, refresh.filesReparsed());
        assertEquals(0, refresh.cacheHits());
        assertFalse(refresh.narrowed());

        Files.writeString(WorkbenchFeatureIndex.cacheFile(project), "not-an-index");
        WorkbenchFeatureIndex.Selection afterCorrupt = select("@missing", null, null, List.of(), false);
        assertEquals(1, afterCorrupt.filesReparsed());
        assertTrue(afterCorrupt.matchedNone());
        assertNull(afterCorrupt.featureProperty());
    }

    @Test
    void unsupportedTagExpressionIsAFullScanFallback() {
        WorkbenchFeatureIndex.Selection selection = select("@smoke xor @wip", null, null, List.of(), false);
        assertTrue(selection.fullScanFallback());
        assertFalse(selection.narrowed());
        assertNull(selection.featureProperty());
        assertTrue(selection.hits().isEmpty());
        assertTrue(selection.fallbackReason().contains("unsupported tag expression"));
    }

    @Test
    void nameExampleAndPathLineResolveRowsWithArguments() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features.resolve("workflow"));
        Files.writeString(features.resolve("workflow/search.feature"), """
                @suite
                Feature: Search
                  @fast
                  Scenario Outline: Find term
                    When search <term>
                    @alpha
                    Examples:
                      | term |
                      | ada  |
                    @beta
                    Examples:
                      | term |
                      | bob  |
                  Scenario: Plain stay
                    Given stay
                """);
        Files.writeString(features.resolve("workflow/other.feature"), """
                Feature: Other
                  Scenario: Find term elsewhere
                    Given no
                """);

        WorkbenchFeatureIndex.Selection byName = select(null, "Find term", null, List.of(), false);
        assertEquals(3, byName.hits().size());

        WorkbenchFeatureIndex.Selection firstRow = select("@suite and @alpha", "Find term", "1", List.of(), false);
        assertEquals(1, firstRow.hits().size());
        WorkbenchFeatureIndex.Hit ada = firstRow.hits().getFirst();
        assertEquals("ada", ada.values().getFirst());
        assertEquals("term=ada", ada.argumentText());
        assertTrue(ada.tags().contains("alpha"));
        assertFalse(ada.tags().contains("beta"));

        WorkbenchFeatureIndex.Selection secondTable = select(null, "^Find term$", "2.1", List.of(), false);
        assertEquals(1, secondTable.hits().size());
        assertEquals("bob", secondTable.hits().getFirst().values().getFirst());

        String relative = "src/test/resources/features/workflow/search.feature";
        WorkbenchFeatureIndex.Selection byRowLine = select(null, null, null, List.of(relative + ":9"), false);
        assertEquals(1, byRowLine.hits().size());
        assertEquals("ada", byRowLine.hits().getFirst().values().getFirst());
        assertTrue(byRowLine.featureProperty().contains(relative));
        assertTrue(byRowLine.featureProperty().contains(":9"));
        assertFalse(byRowLine.featureProperty().contains("other.feature"));

        WorkbenchFeatureIndex.Selection byScenarioLine = select(null, null, null, List.of(relative + ":4"), false);
        assertEquals(2, byScenarioLine.hits().size());
        assertEquals(List.of("ada", "bob"), byScenarioLine.hits().stream().map(hit -> hit.values().getFirst()).toList());

        WorkbenchFeatureIndex.Selection plain = select(null, null, "2", List.of(), false);
        assertTrue(plain.hits().stream().noneMatch(hit -> hit.scenarioName().equals("Plain stay")));
        assertEquals(List.of("bob"), plain.hits().stream().map(hit -> hit.values().getFirst()).toList());
    }

    @Test
    void invalidNameAndExampleFailFast() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("one.feature"), """
                Feature: One
                  Scenario: Alpha
                    Given a
                """);
        IllegalArgumentException name = assertThrows(IllegalArgumentException.class,
                () -> select(null, "(unclosed", null, List.of(), false));
        assertTrue(name.getMessage().contains("Unparseable scenario name"));
        IllegalArgumentException example = assertThrows(IllegalArgumentException.class,
                () -> select(null, null, "0", List.of(), false));
        assertTrue(example.getMessage().contains("pkb_example"));
        IllegalArgumentException line = assertThrows(IllegalArgumentException.class,
                () -> select(null, null, null, List.of("not-a-feature"), false));
        assertTrue(line.getMessage().contains("Path:line"));
    }

    @Test
    void noMatchFeatureIsOutsideTheIndexedRoots() throws Exception {
        String property = WorkbenchFeatureIndex.noMatchFeatureProperty(project);
        assertTrue(property.startsWith("wd-file:"));
        assertTrue(property.contains("no-match.feature"));
        WorkbenchFeatureIndex.Selection selection = select(null, null, null, List.of(), true);
        assertEquals(0, selection.filesIndexed());
    }

    private WorkbenchFeatureIndex.Selection select(
            String tags,
            String name,
            String example,
            List<String> selects,
            boolean refresh
    ) {
        return WorkbenchFeatureIndex.select(new WorkbenchFeatureIndex.Request(
                project, List.of(), tags, name, example, selects, refresh
        ));
    }
}
