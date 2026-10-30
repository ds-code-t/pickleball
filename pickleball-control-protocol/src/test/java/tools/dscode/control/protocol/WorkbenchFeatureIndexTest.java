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
        assertTrue(first.elapsedMillis() >= 0);
        assertEquals(List.of(first.featureProperty()), java.util.List.of(String.join(",", WorkbenchFeatureIndex.prefilteredFeatures(first))));
        assertEquals(1, WorkbenchFeatureIndex.prefilteredFeatures(first).size());

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
    void exampleKeywordIsAScenarioAndScenariosIsAnExamplesTable() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("keywords.feature"), """
                @root
                Feature: Pair
                  @one
                  Scenario: First
                    Given a
                  @two
                  Example: Second
                    Given b
                  @rows
                  Scenario Outline: Find term
                    When search <term>
                    @alpha
                    Scenarios:
                      | term |
                      | ada  |
                      | bob  |
                """);

        WorkbenchFeatureIndex.Selection byTag = select("@two", null, null, List.of(), false);
        assertEquals(1, byTag.hits().size());
        WorkbenchFeatureIndex.Hit second = byTag.hits().getFirst();
        assertEquals("Second", second.scenarioName());
        assertEquals(7, second.line());
        assertEquals(7, second.startLine());
        assertTrue(second.tags().contains("two"));
        assertTrue(second.tags().contains("root"));
        assertFalse(second.tags().contains("one"));

        WorkbenchFeatureIndex.Selection both = select("@one or @two", null, null, List.of(), false);
        assertEquals(List.of("First", "Second"), both.hits().stream().map(WorkbenchFeatureIndex.Hit::scenarioName).toList());
        assertEquals(List.of(4, 7), both.hits().stream().map(WorkbenchFeatureIndex.Hit::line).toList());

        WorkbenchFeatureIndex.Selection row = select("@rows", null, "2", List.of(), false);
        assertEquals(1, row.hits().size());
        assertEquals("bob", row.hits().getFirst().values().getFirst());
        assertEquals(16, row.hits().getFirst().line());
        assertTrue(row.hits().getFirst().tags().contains("alpha"));
    }

    @Test
    void scenarioTagsColumnIsIndexedOnTheRow() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("components.feature"), """
                Feature: Components
                  Scenario Outline: Save customer
                    When save <name>
                    Examples:
                      | Scenario Tags           | name |
                      | %save_customer @extra   | ada  |
                      | plain                   | bob  |
                """);
        WorkbenchFeatureIndex.Selection tagged = select("@extra", null, null, List.of(), false);
        assertEquals(1, tagged.hits().size());
        assertEquals("ada", tagged.hits().getFirst().values().get(1));
        assertTrue(tagged.hits().getFirst().tags().contains("%save_customer"));
        assertTrue(tagged.hits().getFirst().tags().contains("extra"));
        WorkbenchFeatureIndex.Selection component = select("@%save_customer", null, null, List.of(), false);
        assertEquals(1, component.hits().size());
        WorkbenchFeatureIndex.Selection plain = select(null, null, "2", List.of(), false);
        assertEquals("bob", plain.hits().getFirst().values().get(1));
        assertFalse(plain.hits().getFirst().tags().contains("plain"));
    }

    @Test
    void docstringContentsDoNotCreatePhantomScenarios() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("doc.feature"), """
                Feature: Doc
                  @real
                  Scenario: Real
                    Given a
                      \"\"\"
                      Scenario: Phantom
                      @ghost
                      Example: Also phantom
                      \"\"\"
                    Then done
                  Scenario: Ticks
                    Given b
                      ```
                      Scenario: Tick phantom
                      @tickghost
                      ```
                    Then done
                """);

        WorkbenchFeatureIndex.Selection all = select(null, null, null, List.of(), false);
        assertEquals(List.of("Real", "Ticks"), all.hits().stream().map(WorkbenchFeatureIndex.Hit::scenarioName).toList());
        WorkbenchFeatureIndex.Selection ghost = select("@ghost or @tickghost", null, null, List.of(), false);
        assertTrue(ghost.hits().isEmpty());
        assertTrue(ghost.matchedNone());
    }

    @Test
    void nonEnglishLanguageAndUnknownKeywordsAreAlwaysIncluded() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        String english = """
                # language: en
                @smoke
                Feature: English
                  Scenario: Kept
                    Given a
                """;
        Files.writeString(features.resolve("en.feature"), english);
        Files.writeString(features.resolve("fr.feature"), """
                # language: fr
                Fonctionnalité: Bonjour
                  Scénario: Un
                    Soit ceci
                """);
        Files.writeString(features.resolve("odd.feature"), """
                Feature: Odd
                  Scenario: Real but unmodelled
                    Given a
                  Scénario: foreign
                """);
        Files.writeString(features.resolve("other.feature"), """
                Feature: Other
                  Note: ordinary description
                  As a shopper: I want speed
                  Scenario: Stay
                    Given stay
                """);

        WorkbenchFeatureIndex.Selection selection = select("@smoke", null, null, List.of(), false);
        assertEquals(List.of("Kept"), selection.hits().stream().map(WorkbenchFeatureIndex.Hit::scenarioName).toList());
        assertFalse(selection.matchedNone());
        assertFalse(selection.fullScanFallback());
        assertTrue(selection.featureProperty().contains("en.feature"));
        assertTrue(selection.featureProperty().contains("fr.feature"));
        assertTrue(selection.featureProperty().contains("odd.feature"));
        assertFalse(selection.featureProperty().contains("other.feature"));
        assertFalse(selection.featureProperty().contains("fr.feature:"));
        assertFalse(selection.hits().stream().anyMatch(hit -> hit.relativePath().contains("fr.feature")));
        assertFalse(selection.hits().stream().anyMatch(hit -> hit.relativePath().contains("odd.feature")));

        String relative = "src/test/resources/features/en.feature";
        WorkbenchFeatureIndex.Selection byLine = select(null, null, null, List.of(relative + ":4"), false);
        assertEquals(1, byLine.hits().size());
        assertTrue(byLine.featureProperty().contains(":4"));
        assertTrue(byLine.featureProperty().contains("fr.feature"));
        assertTrue(byLine.featureProperty().contains("odd.feature"));
        assertFalse(byLine.featureProperty().contains("fr.feature:"));

        WorkbenchFeatureIndex.Selection cached = select("@smoke", null, null, List.of(), false);
        assertEquals(0, cached.filesReparsed());
        assertEquals(selection.hits(), cached.hits());
        assertTrue(cached.featureProperty().contains("fr.feature"));
        assertTrue(cached.featureProperty().contains("odd.feature"));
    }

    @Test
    void freeTextDescriptionsDoNotForceInclusion() throws Exception {
        assertTrue(GherkinKeywords.supported("en"));
        assertTrue(GherkinKeywords.supported("fr"));
        assertFalse(GherkinKeywords.supported("not-a-language"));
        assertFalse(GherkinKeywords.isForeignKeyword("en", "Note"));
        assertFalse(GherkinKeywords.isForeignKeyword("en", "As a shopper"));
        assertFalse(GherkinKeywords.isForeignKeyword("en", "Ability"));
        assertTrue(GherkinKeywords.isForeignKeyword("en", "Scénario"));
        assertTrue(GherkinKeywords.isForeignKeyword("en", "I"));

        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("notes.feature"), """
                @kept
                Feature: Notes
                  Note: this is a description
                  As a shopper: I want a fast page
                  Business context: not a keyword
                  Scenario: Stay
                    Given stay
                """);
        Files.writeString(features.resolve("foreign.feature"), """
                Feature: Foreign word
                  Scenario: Hidden
                    Given a
                  I: this is a real keyword in another dialect
                """);
        Files.writeString(features.resolve("plain.feature"), """
                Feature: Plain
                  Scenario: Other
                    Given other
                """);

        WorkbenchFeatureIndex.Selection selection = select("@kept", null, null, List.of(), false);
        assertEquals(List.of("Stay"), selection.hits().stream().map(WorkbenchFeatureIndex.Hit::scenarioName).toList());
        assertTrue(selection.featureProperty().contains("notes.feature"));
        assertFalse(selection.featureProperty().contains("plain.feature"));
        assertTrue(selection.featureProperty().contains("foreign.feature"));
        assertFalse(selection.hits().stream().anyMatch(hit -> hit.relativePath().contains("foreign.feature")));
    }

    @Test
    void cacheSchemaVersionBumpForcesReparse() throws Exception {
        assertEquals(3, WorkbenchFeatureIndex.VERSION);
        assertEquals("feature-index.v3", WorkbenchFeatureIndex.CACHE_FILE);
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("one.feature"), """
                Feature: One
                  Scenario: Alpha
                    Given a
                """);
        WorkbenchFeatureIndex.Selection first = select(null, null, null, List.of(), false);
        assertEquals(1, first.filesReparsed());
        Path cache = WorkbenchFeatureIndex.cacheFile(project);
        byte[] bytes = Files.readAllBytes(cache);
        bytes[0] = 0;
        bytes[1] = 0;
        bytes[2] = 0;
        bytes[3] = 1;
        Files.write(cache, bytes);
        WorkbenchFeatureIndex.Selection again = select("@missing", null, null, List.of(), false);
        assertEquals(1, again.filesReparsed());
        assertEquals(0, again.cacheHits());
        assertTrue(again.matchedNone());
    }

    @Test
    void noMatchFeatureIsOutsideTheIndexedRoots() throws Exception {
        String property = WorkbenchFeatureIndex.noMatchFeatureProperty(project);
        assertTrue(property.startsWith("wd-file:"));
        assertTrue(property.contains("no-match.feature"));
        WorkbenchFeatureIndex.Selection selection = select(null, null, null, List.of(), true);
        assertEquals(0, selection.filesIndexed());
    }

    @Test
    void longNarrowedListIsAFileSoTheCommandLineStaysShort() throws Exception {
        Path features = project.resolve("src/test/resources/features/region/module/pack");
        Files.createDirectories(features);
        for (int i = 0; i < 80; i++) {
            Files.writeString(features.resolve("flow-" + i + ".feature"), """
                    @keep
                    Feature: Flow %d
                      Scenario: Row %d
                        Given ready
                    """.formatted(i, i));
        }
        // One file outside the tag, otherwise the match covers every indexed file and stays a full scan.
        Files.writeString(features.resolve("other.feature"), """
                Feature: Other
                  Scenario: Stay
                    Given stay
                """);
        WorkbenchFeatureIndex.Selection selection = select("@keep", null, null, List.of(), true);
        assertTrue(selection.narrowed(), selection.fallbackReason());
        assertEquals(80, selection.hits().size());
        assertEquals(81, selection.filesIndexed());
        String property = selection.featureProperty();
        assertTrue(property.startsWith("@"), property);
        assertTrue(property.length() < WorkbenchFeatureIndex.FEATURE_LIST_INLINE_LIMIT, property);
        String body = Files.readString(Path.of(property.substring(1)));
        assertTrue(body.contains("flow-0.feature"), body.substring(0, Math.min(body.length(), 200)));
        assertTrue(body.contains("flow-79.feature"));
        assertFalse(body.contains("other.feature"));
        assertTrue(body.length() > WorkbenchFeatureIndex.FEATURE_LIST_INLINE_LIMIT);
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
