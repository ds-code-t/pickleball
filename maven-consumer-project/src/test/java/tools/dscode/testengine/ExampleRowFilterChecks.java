package tools.dscode.testengine;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.core.runner.util.CucumberQueryUtil;
import io.cucumber.messages.types.TableRow;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.testengine.PKB_props.PKB_EXAMPLE;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_FEATURE_NAME;
import static tools.dscode.testengine.PKB_props.PKB_NAME;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

public final class ExampleRowFilterChecks {
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

    @Test
    void absentAndBlankExampleAddNoFilter() throws IOException {
        withFeatures((feature, other) -> {
            assertTrue(CucumberScanUtil.listPickles(Map.of(PKB_FEATURES, feature.toString())).isEmpty());
            List<String> selected = named(scan(feature.toString(), null, null, ".*", null));
            List<String> blank = named(scan(feature.toString(), "   ", null, ".*", null));
            assertEquals(selected, blank);
            assertTrue(selected.contains("Plain:plain"));
        });
    }

    @Test
    void integerFiveIsTheSecondRowOfTheSecondTable() throws IOException {
        withFeatures((feature, other) -> {
            List<Pickle> rows = scan(feature.toString(), "5", null, "First outline", null);
            assertEquals(List.of("First outline:e"), named(rows));
            ExampleRowFilter.Coordinates coordinates = ExampleRowFilter.coordinatesOf(rows.getFirst());
            assertEquals(5, coordinates.overall());
            assertEquals(2, coordinates.table());
            assertEquals(2, coordinates.row());
        });
    }

    @Test
    void tableRowAndListSelectionKeepSourceOrder() throws IOException {
        withFeatures((feature, other) -> {
            assertEquals(List.of("Plain:plain"), named(scan(feature.toString(), "1.1", null, "Plain", null)));
            assertEquals(
                    List.of("a", "b", "e", "g", "h", "i", "j", "k"),
                    cells(scan(feature.toString(), "1 2 5 3.4 7-11", null, "Long outline", null))
            );
            assertEquals(
                    List.of("a", "e"),
                    cells(scan(feature.toString(), "5 1 5 2.2", null, "Long outline", null))
            );
            assertEquals(
                    List.of("First outline:e", "Short outline:e", "Long outline:e"),
                    named(scan(feature.toString(), "5", null, null, null))
            );
        });
    }

    @Test
    void shortOutlineContributesNothingAndZeroMatchesFails() throws IOException {
        withFeatures((feature, other) -> {
            List<Pickle> shortOnly = scan(feature.toString(), null, null, "Short outline", null);
            assertTrue(ExampleRowFilter.filterPickles("7", shortOnly).isEmpty());
            assertEquals(
                    List.of("Long outline:g"),
                    named(scan(feature.toString(), "7", null, "Short outline|Long outline", null))
            );
            IllegalArgumentException none = assertThrows(
                    IllegalArgumentException.class,
                    () -> scan(feature.toString(), "99", null, ".*", null)
            );
            assertTrue(none.getMessage().startsWith("No scenarios matched the provided filters:"));
            assertTrue(none.getMessage().contains("example=[99]"));
            IllegalArgumentException plain = assertThrows(
                    IllegalArgumentException.class,
                    () -> scan(feature.toString(), "2", null, "Plain", null)
            );
            assertTrue(plain.getMessage().contains("example=[2]"));
        });
    }

    @Test
    void invalidTokensAndTagIndexesFailClosed() throws IOException {
        for (String token : List.of("0", "5-3", "2.0", "3.", "1.2.3")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> ExampleRowFilter.parse(token));
            assertTrue(failure.getMessage().contains("'" + token + "'"));
            assertFalse(failure.getMessage().startsWith("No scenarios matched"));
        }
        withFeatures((feature, other) -> {
            IllegalArgumentException partial = assertThrows(
                    IllegalArgumentException.class,
                    () -> scan(feature.toString(), "1 2 0", null, "Long outline", null)
            );
            assertTrue(partial.getMessage().contains("'0'"));
            IllegalArgumentException renamed = assertThrows(
                    IllegalArgumentException.class,
                    () -> scan(feature.toString(), "1", "not @skip", "Tagged outline", null)
            );
            assertTrue(renamed.getMessage().startsWith("No scenarios matched the provided filters:"));
            assertEquals(
                    List.of("Tagged outline:c"),
                    named(scan(feature.toString(), "2.1", "not @skip", "Tagged outline", null))
            );
            String both = feature + "," + other;
            assertEquals(List.of("Long outline:g"), named(scan(both, "7", null, null, "Example filter")));
        });
    }

    private static void withFeatures(FeatureCase body) throws IOException {
        Path dir = Files.createTempDirectory("pkb-example-");
        try {
            Path feature = dir.resolve("example-filter.feature");
            Path other = dir.resolve("other.feature");
            Files.writeString(feature, FEATURE);
            Files.writeString(other, OTHER);
            CucumberScanUtil.clearCache();
            body.run(feature, other);
        } finally {
            deleteTree(dir);
        }
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

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface FeatureCase {
        void run(Path feature, Path other) throws IOException;
    }
}
