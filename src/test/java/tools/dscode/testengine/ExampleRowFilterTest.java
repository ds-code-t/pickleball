package tools.dscode.testengine;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.core.runner.util.CucumberQueryUtil;
import io.cucumber.messages.types.TableRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.testengine.PKB_props.PKB_EXAMPLE;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_FEATURE_NAME;
import static tools.dscode.testengine.PKB_props.PKB_LIMIT;
import static tools.dscode.testengine.PKB_props.PKB_NAME;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

class ExampleRowFilterTest {
    private static final String FEATURE = """
            Feature: Example filter

              Scenario: Plain
                Given value plain

              Scenario Outline: First outline
                Given value <v>
                Examples:
                  | v |
                  | a |
                  | b |
                  | c |
                Examples:
                  | v |
                  | d |
                  | e |
                  | f |

              Scenario Outline: Short outline
                Given value <v>
                Examples:
                  | v |
                  | a |
                  | b |
                  | c |
                Examples:
                  | v |
                  | d |
                  | e |
                  | f |

              Scenario Outline: Long outline
                Given value <v>
                Examples:
                  | v |
                  | a |
                  | b |
                  | c |
                Examples:
                  | v |
                  | d |
                  | e |
                  | f |
                Examples:
                  | v |
                  | g |
                  | h |
                  | i |
                  | j |
                  | k |

              Scenario Outline: Tagged outline
                Given value <v>
                @skip
                Examples:
                  | v |
                  | a |
                  | b |
                Examples:
                  | v |
                  | c |
            """;

    private static final String OTHER = """
            Feature: Other

              Scenario Outline: Other outline
                Given value <v>
                Examples:
                  | v |
                  | a |
                  | b |
                  | c |
                  | d |
                  | e |
                  | f |
                  | g |
            """;

    @TempDir
    Path tempDir;

    private Path featureFile;
    private Path otherFile;

    @BeforeEach
    void writeFeatures() throws IOException {
        CucumberScanUtil.clearCache();
        featureFile = tempDir.resolve("example-filter.feature");
        otherFile = tempDir.resolve("other.feature");
        Files.writeString(featureFile, FEATURE);
        Files.writeString(otherFile, OTHER);
    }

    @Test
    void absentAndBlankExampleAddNoFilter() {
        assertTrue(CucumberScanUtil.listPickles(Map.of(PKB_FEATURES, featureFile.toString())).isEmpty());

        List<String> selected = named(scan(featureFile.toString(), null, null, ".*", null));
        List<String> blank = named(scan(featureFile.toString(), "   ", null, ".*", null));
        assertEquals(selected, blank);
        assertTrue(selected.contains("Plain:plain"));
        assertTrue(selected.contains("Long outline:k"));
    }

    @Test
    void integerFiveIsTableTwoRowTwoWhenEachTableHasThreeRows() {
        List<Pickle> rows = scan(featureFile.toString(), "5", null, "First outline", null);
        assertEquals(List.of("First outline:e"), named(rows));
        ExampleRowFilter.Coordinates coordinates = ExampleRowFilter.coordinatesOf(rows.getFirst());
        assertEquals(5, coordinates.overall());
        assertEquals(2, coordinates.table());
        assertEquals(2, coordinates.row());
    }

    @Test
    void tableRowSelectorKeepsNormalScenarioOnlyForOneAndOnePointOne() {
        assertEquals(List.of("Plain:plain"), named(scan(featureFile.toString(), "1", null, "Plain", null)));
        assertEquals(List.of("Plain:plain"), named(scan(featureFile.toString(), "1.1", null, "Plain", null)));
        assertEquals(List.of("First outline:b"), named(scan(featureFile.toString(), "1.2", null, "Plain|First outline", null)));

        IllegalArgumentException dropped = assertThrows(
                IllegalArgumentException.class,
                () -> scan(featureFile.toString(), "2", null, "Plain", null)
        );
        assertTrue(dropped.getMessage().startsWith("No scenarios matched the provided filters:"));
        assertTrue(dropped.getMessage().contains("example=[2]"));
    }

    @Test
    void listKeepsSourceOrderAndRunsADuplicateRowOnce() {
        assertEquals(
                List.of("a", "b", "e", "g", "h", "i", "j", "k"),
                cells(scan(featureFile.toString(), "1 2 5 3.4 7-11", null, "Long outline", null))
        );
        assertEquals(
                List.of("a", "e"),
                cells(scan(featureFile.toString(), "5 1 5 2.2", null, "Long outline", null))
        );
    }

    @Test
    void eachSelectedOutlineIsFilteredTheSameWay() {
        assertEquals(
                List.of("First outline:e", "Short outline:e", "Long outline:e"),
                named(scan(featureFile.toString(), "5", null, null, null))
        );
    }

    @Test
    void aShortOutlineContributesNothingWhileAnotherMatchDoesNotFail() {
        List<Pickle> shortOnly = scan(featureFile.toString(), null, null, "Short outline", null);
        assertTrue(ExampleRowFilter.filterPickles("7", shortOnly).isEmpty());
        assertEquals(
                List.of("Long outline:g"),
                named(scan(featureFile.toString(), "7", null, "Short outline|Long outline", null))
        );
    }

    @Test
    void zeroMatchesFailsLikeAnEmptySelection() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> scan(featureFile.toString(), "99", null, ".*", null)
        );
        assertTrue(failure.getMessage().startsWith("No scenarios matched the provided filters:"));
        assertTrue(failure.getMessage().contains("example=[99]"));
    }

    @Test
    void invalidTokensFailAndAreNotSkipped() {
        for (String token : List.of("0", "5-3", "2.0", "3.", "1.2.3", "01", "5-", "-5", "+5", "1-2.3", "1.2-3")) {
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ExampleRowFilter.parse(token),
                    token
            );
            assertTrue(failure.getMessage().contains("pkb_example"), token);
            assertTrue(failure.getMessage().contains("'" + token + "'"), failure.getMessage());
            assertFalse(failure.getMessage().startsWith("No scenarios matched"), token);
        }
        IllegalArgumentException partial = assertThrows(
                IllegalArgumentException.class,
                () -> scan(featureFile.toString(), "1 2 0", null, "Long outline", null)
        );
        assertTrue(partial.getMessage().contains("'0'"));
        assertFalse(partial.getMessage().startsWith("No scenarios matched"));

        IllegalArgumentException spacedRange = assertThrows(
                IllegalArgumentException.class,
                () -> ExampleRowFilter.parse("7 - 11")
        );
        assertTrue(spacedRange.getMessage().contains("'-'"));
    }

    @Test
    void tagExcludedRowsStillOccupyTheirSourceIndex() {
        IllegalArgumentException renamed = assertThrows(
                IllegalArgumentException.class,
                () -> scan(featureFile.toString(), "1", "not @skip", "Tagged outline", null)
        );
        assertTrue(renamed.getMessage().startsWith("No scenarios matched the provided filters:"));
        assertEquals(
                List.of("Tagged outline:c"),
                named(scan(featureFile.toString(), "3", "not @skip", "Tagged outline", null))
        );
        assertEquals(
                List.of("Tagged outline:c"),
                named(scan(featureFile.toString(), "2.1", "not @skip", "Tagged outline", null))
        );
    }

    @Test
    void featureNameRunsBeforeTheExampleFilter() {
        String both = featureFile + "," + otherFile;
        assertEquals(
                List.of("Long outline:g"),
                named(scan(both, "7", null, null, "Example filter"))
        );
        assertEquals(
                List.of("Other outline:g"),
                named(scan(both, "7", null, null, "Other"))
        );
    }

    @Test
    void exampleOnlyScanKeepsRowOneOfEachScenario() {
        assertEquals(
                List.of(
                        "Plain:plain",
                        "First outline:a",
                        "Short outline:a",
                        "Long outline:a",
                        "Tagged outline:a"
                ),
                named(scan(featureFile.toString(), "1", null, null, null))
        );
    }

    @Test
    void orderLimitStillAppliesAfterTheExampleFilter() {
        Map<String, String> props = new HashMap<>();
        props.put(PKB_FEATURES, featureFile.toString());
        props.put(PKB_NAME, "Long outline");
        props.put(PKB_EXAMPLE, "1 2 3");
        props.put(PKB_LIMIT, "1");
        assertEquals(List.of("a"), cells(CucumberScanUtil.listPickles(props)));
    }

    @Test
    void partialNameRegexRunsBeforeTheExampleFilter() {
        assertEquals(
                List.of(
                        "First outline:a",
                        "Short outline:a",
                        "Long outline:a",
                        "Tagged outline:a"
                ),
                named(scan(featureFile.toString(), "1", null, "outline", null))
        );
    }

    @Test
    void discoveryAppliesExampleAfterAPartialNameRegex() throws IOException {
        Feature feature = readFeature(featureFile);
        TestDescriptor root = suite(feature);
        ExampleRowFilter.applyToDiscoveredSuite(root, "1", null, "outline");
        Map<String, Integer> counts = counts(root);
        assertEquals(1, counts.getOrDefault("plain", 0));
        assertEquals(4, counts.getOrDefault("a", 0));
        assertEquals(0, counts.getOrDefault("b", 0));
        assertEquals(0, counts.getOrDefault("e", 0));
    }

    @Test
    void discoveryDropsSelectedRowsThatMissTheExample() throws IOException {
        Feature feature = readFeature(featureFile);
        TestDescriptor root = suite(feature);
        ExampleRowFilter.applyToDiscoveredSuite(root, "5", null, null);
        Map<String, Integer> counts = counts(root);
        assertEquals(3, counts.getOrDefault("e", 0));
        assertEquals(0, counts.getOrDefault("plain", 0));
        assertEquals(0, counts.getOrDefault("a", 0));
        assertEquals(3, root.getChildren().size());
    }

    @Test
    void discoveryLeavesTagMissesInTheTree() throws IOException {
        Feature feature = readFeature(featureFile);
        TestDescriptor root = suite(feature);
        ExampleRowFilter.applyToDiscoveredSuite(root, "3", "not @skip", null);
        Map<String, Integer> counts = counts(root);
        assertEquals(1, counts.getOrDefault("a", 0));
        assertEquals(1, counts.getOrDefault("b", 0));
        assertEquals(4, counts.getOrDefault("c", 0));
        assertEquals(0, counts.getOrDefault("plain", 0));
    }

    @Test
    void discoveryEmptyAndInvalidExampleDoNotRemoveRows() throws IOException {
        Feature feature = readFeature(featureFile);
        TestDescriptor root = suite(feature);
        int before = root.getChildren().size();
        ExampleRowFilter.applyToDiscoveredSuite(root, "   ", null, null);
        assertEquals(before, root.getChildren().size());

        TestDescriptor empty = suite(feature);
        IllegalArgumentException none = assertThrows(
                IllegalArgumentException.class,
                () -> ExampleRowFilter.applyToDiscoveredSuite(empty, "99", null, null)
        );
        assertTrue(none.getMessage().startsWith("No scenarios matched the provided filters:"));
        assertTrue(none.getMessage().contains("example=[99]"));
        assertEquals(before, empty.getChildren().size());

        IllegalArgumentException invalid = assertThrows(
                IllegalArgumentException.class,
                () -> ExampleRowFilter.applyToDiscoveredSuite(root, "5-3", null, null)
        );
        assertTrue(invalid.getMessage().contains("'5-3'"));
        assertFalse(invalid.getMessage().startsWith("No scenarios matched"));
        assertEquals(before, root.getChildren().size());
    }

    private static List<Pickle> scan(String features, String example, String tags, String name, String featureName) {
        CucumberScanUtil.clearCache();
        Map<String, String> props = new HashMap<>();
        props.put(PKB_FEATURES, features);
        if (example != null) props.put(PKB_EXAMPLE, example);
        if (tags != null) props.put(PKB_TAGS, tags);
        if (name != null) props.put(PKB_NAME, name);
        if (featureName != null) props.put(PKB_FEATURE_NAME, featureName);
        return CucumberScanUtil.listPickles(props);
    }

    private static List<String> named(List<Pickle> pickles) {
        List<String> names = new ArrayList<>();
        for (Pickle pickle : pickles) {
            names.add(pickle.getName() + ":" + cell(pickle));
        }
        return names;
    }

    private static List<String> cells(List<Pickle> pickles) {
        List<String> values = new ArrayList<>();
        for (Pickle pickle : pickles) {
            values.add(cell(pickle));
        }
        return values;
    }

    private static String cell(Pickle pickle) {
        TableRow exampleRow = CucumberQueryUtil.describe(pickle).exampleRow;
        if (exampleRow != null && exampleRow.getCells() != null && !exampleRow.getCells().isEmpty()) {
            return exampleRow.getCells().getFirst().getValue();
        }
        String text = pickle.getSteps().getFirst().getText();
        int space = text.lastIndexOf(' ');
        return space < 0 ? text : text.substring(space + 1);
    }

    private static Feature readFeature(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return new GherkinMessagesFeatureParser()
                    .parse(file.toUri(), input, UUID::randomUUID)
                    .orElseThrow();
        }
    }

    private static TestDescriptor suite(Feature feature) {
        UniqueId rootId = UniqueId.forEngine("cucumber");
        RowNode root = new RowNode(rootId, "suite", TestDescriptor.Type.CONTAINER);
        String uri = feature.getUri().toString();
        for (Pickle pickle : feature.getPickles()) {
            int line = pickle.getLocation().getLine();
            UniqueId id = rootId.append("feature", uri);
            if (pickle.getExamplesLocation().isPresent()) {
                id = id.append("scenario", "1")
                        .append("examples", "1")
                        .append("example", Integer.toString(line));
            } else {
                id = id.append("scenario", Integer.toString(line));
            }
            root.addChild(new RowNode(id, cell(pickle), TestDescriptor.Type.TEST));
        }
        return root;
    }

    private static Map<String, Integer> counts(TestDescriptor root) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TestDescriptor child : root.getChildren()) {
            if (child.getType() == TestDescriptor.Type.TEST) {
                counts.merge(child.getDisplayName(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private static final class RowNode extends AbstractTestDescriptor {
        private final TestDescriptor.Type type;

        private RowNode(UniqueId uniqueId, String displayName, TestDescriptor.Type type) {
            super(uniqueId, displayName);
            this.type = type;
        }

        @Override
        public TestDescriptor.Type getType() {
            return type;
        }
    }
}
