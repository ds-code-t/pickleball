package tools.dscode.testengine;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser;
import io.cucumber.core.runner.util.CucumberQueryUtil;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.TableRow;
import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionParser;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import tools.dscode.control.protocol.ExampleRowSelector;

/**
 * Selects Examples rows after tag, name, and feature-name filters.
 * A normal scenario is table 1, row 1.
 */
public final class ExampleRowFilter {
    private static final String FEATURE_SEGMENT = "feature";
    private static final String SCENARIO_SEGMENT = "scenario";
    private static final String EXAMPLE_SEGMENT = "example";

    private final ExampleRowSelector selector;

    private ExampleRowFilter(ExampleRowSelector selector) {
        this.selector = selector;
    }

    public static boolean isInactive(String expression) {
        return ExampleRowSelector.isInactive(expression);
    }

    public static ExampleRowFilter parse(String expression) {
        return new ExampleRowFilter(ExampleRowSelector.parse(expression));
    }

    public boolean matches(int overall, int table, int row) {
        return selector.matches(overall, table, row);
    }

    public static List<Pickle> filterPickles(String expression, List<Pickle> pickles) {
        Objects.requireNonNull(pickles, "pickles");
        if (isInactive(expression)) {
            return List.copyOf(pickles);
        }
        return parse(expression).apply(pickles);
    }

    /**
     * Drops discovered tests that survived tag and name selection but do not match
     * {@code pkb_example}. An empty result fails the run.
     */
    public static void applyToDiscoveredSuite(
            TestDescriptor root,
            String example,
            String tags,
            String name
    ) {
        Objects.requireNonNull(root, "root");
        if (isInactive(example)) {
            return;
        }
        ExampleRowFilter filter = parse(example);
        List<TestDescriptor> tests = new ArrayList<>();
        collectTests(root, tests);
        Map<String, Feature> features = new HashMap<>();
        List<TestDescriptor> selected = new ArrayList<>();
        List<Coordinates> coordinates = new ArrayList<>();
        for (TestDescriptor test : tests) {
            if (!looksLikeScenario(test)) {
                continue;
            }
            Located located = locate(test, features);
            if (located == null) {
                throw new IllegalArgumentException(
                        "pkb_example could not locate a scenario row for " + test.getUniqueId()
                );
            }
            if (!passesPriorFilters(located.pickle(), tags, name)) {
                continue;
            }
            selected.add(test);
            coordinates.add(located.coordinates());
        }
        Set<TestDescriptor> kept = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < selected.size(); index++) {
            Coordinates row = coordinates.get(index);
            if (row == null || !filter.matches(row.overall(), row.table(), row.row())) {
                continue;
            }
            if (seen.add(row.identity())) {
                kept.add(selected.get(index));
            }
        }
        if (kept.isEmpty()) {
            throw noScenariosMatched(tags, name, example);
        }
        for (TestDescriptor test : selected) {
            if (!kept.contains(test)) {
                test.removeFromHierarchy();
            }
        }
    }

    public static IllegalArgumentException noScenariosMatched(String tags, String name, String example) {
        StringBuilder message = new StringBuilder("No scenarios matched the provided filters:");
        appendDetail(message, "tags", tags);
        appendDetail(message, "name", name);
        appendDetail(message, "example", example);
        return new IllegalArgumentException(message.toString());
    }

    private List<Pickle> apply(List<Pickle> pickles) {
        List<Pickle> kept = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Pickle pickle : pickles) {
            Coordinates row = coordinatesOf(pickle);
            if (row == null || !matches(row.overall(), row.table(), row.row())) {
                continue;
            }
            if (seen.add(row.identity())) {
                kept.add(pickle);
            }
        }
        return List.copyOf(kept);
    }

    static Coordinates coordinatesOf(Pickle pickle) {
        CucumberQueryUtil.GherkinView view = CucumberQueryUtil.describe(pickle);
        String uri = pickle.getUri() == null ? "" : pickle.getUri().toString();
        String scenarioId = view.scenario.getId();
        if (!view.scenarioOutline) {
            return new Coordinates(uri, scenarioId, 1, 1, 1);
        }
        if (view.exampleRow == null) {
            return null;
        }
        String rowId = view.exampleRow.getId();
        int tableNumber = 0;
        int overall = 0;
        for (Examples examples : view.scenario.getExamples()) {
            tableNumber++;
            List<TableRow> body = examples.getTableBody();
            if (body == null) {
                continue;
            }
            int rowInTable = 0;
            for (TableRow candidate : body) {
                rowInTable++;
                overall++;
                if (candidate.getId() != null && candidate.getId().equals(rowId)) {
                    return new Coordinates(uri, scenarioId, overall, tableNumber, rowInTable);
                }
            }
        }
        return null;
    }

    private static boolean passesPriorFilters(Pickle pickle, String tags, String name) {
        if (tags != null && !tags.isBlank()) {
            Expression expression = TagExpressionParser.parse(tags.trim());
            if (!expression.evaluate(pickle.getTags())) {
                return false;
            }
        }
        if (name != null && !name.isBlank()) {
            if (!Pattern.compile(name.trim()).matcher(pickle.getName()).matches()) {
                return false;
            }
        }
        return true;
    }

    private static void collectTests(TestDescriptor node, List<TestDescriptor> tests) {
        if (node.getType() == TestDescriptor.Type.TEST) {
            tests.add(node);
        }
        for (TestDescriptor child : node.getChildren()) {
            collectTests(child, tests);
        }
    }

    private static boolean looksLikeScenario(TestDescriptor test) {
        return segment(test.getUniqueId(), FEATURE_SEGMENT) != null
                && (segment(test.getUniqueId(), EXAMPLE_SEGMENT) != null
                || segment(test.getUniqueId(), SCENARIO_SEGMENT) != null);
    }

    private static Located locate(TestDescriptor test, Map<String, Feature> features) {
        String featureValue = segment(test.getUniqueId(), FEATURE_SEGMENT);
        String lineText = segment(test.getUniqueId(), EXAMPLE_SEGMENT);
        if (lineText == null) {
            lineText = segment(test.getUniqueId(), SCENARIO_SEGMENT);
        }
        if (featureValue == null || lineText == null) {
            return null;
        }
        int line;
        try {
            line = Integer.parseInt(lineText);
        } catch (NumberFormatException exception) {
            return null;
        }
        Feature feature = features.computeIfAbsent(featureValue, ExampleRowFilter::loadFeature);
        if (feature == null) {
            return null;
        }
        for (Pickle pickle : feature.getPickles()) {
            if (pickle.getLocation() != null && pickle.getLocation().getLine() == line) {
                return new Located(pickle, coordinatesOf(pickle));
            }
        }
        return null;
    }

    private static Feature loadFeature(String featureValue) {
        URI uri = URI.create(featureValue);
        GherkinMessagesFeatureParser parser = new GherkinMessagesFeatureParser();
        try (InputStream input = open(uri)) {
            Optional<Feature> parsed = parser.parse(uri, input, UUID::randomUUID);
            return parsed.orElse(null);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "pkb_example could not read feature " + featureValue,
                    exception
            );
        }
    }

    private static InputStream open(URI uri) throws IOException {
        String external = uri.toString();
        if (external.toLowerCase(Locale.ROOT).startsWith("classpath:")) {
            String resource = external.substring("classpath:".length());
            while (resource.startsWith("/")) {
                resource = resource.substring(1);
            }
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = ExampleRowFilter.class.getClassLoader();
            }
            InputStream input = loader.getResourceAsStream(resource);
            if (input == null) {
                throw new IOException("Classpath feature was not found: " + external);
            }
            return input;
        }
        try {
            return Files.newInputStream(Path.of(uri));
        } catch (RuntimeException | IOException first) {
            return uri.toURL().openStream();
        }
    }

    private static String segment(UniqueId uniqueId, String type) {
        String value = null;
        for (UniqueId.Segment segment : uniqueId.getSegments()) {
            if (type.equals(segment.getType())) {
                value = segment.getValue();
            }
        }
        return value;
    }

    private static void appendDetail(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append(' ').append(label).append("=[").append(value.trim()).append(']');
        }
    }

    record Coordinates(String uri, String scenarioId, int overall, int table, int row) {
        String identity() {
            return uri + "|" + scenarioId + "|" + overall;
        }
    }

    private record Located(Pickle pickle, Coordinates coordinates) {
    }
}
