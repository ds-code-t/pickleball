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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
        Map<String, Optional<Feature>> features = new HashMap<>();
        List<TestDescriptor> selected = new ArrayList<>();
        List<Coordinates> coordinates = new ArrayList<>();
        for (TestDescriptor test : tests) {
            if (!looksLikeScenario(test)) {
                continue;
            }
            Located located = locate(test, features);
            if (located == null) {
                if (unreadable(features, segment(test.getUniqueId(), FEATURE_SEGMENT))) {
                    test.removeFromHierarchy();
                    continue;
                }
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
            // Cucumber's name predicate uses find(), so a partial regex such as
            // "outline" selects "Long outline". Example rows run after that selection.
            if (!Pattern.compile(name.trim()).matcher(pickle.getName()).find()) {
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

    private static boolean unreadable(Map<String, Optional<Feature>> features, String featureValue) {
        if (featureValue == null) {
            return false;
        }
        Optional<Feature> loaded = features.get(featureValue);
        return loaded != null && loaded.isEmpty();
    }

    private static Located locate(TestDescriptor test, Map<String, Optional<Feature>> features) {
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
        Optional<Feature> feature = features.computeIfAbsent(featureValue, ExampleRowFilter::loadFeature);
        if (feature.isEmpty()) {
            return null;
        }
        for (Pickle pickle : feature.get().getPickles()) {
            if (pickle.getLocation() != null && pickle.getLocation().getLine() == line) {
                return new Located(pickle, coordinatesOf(pickle));
            }
        }
        return null;
    }

    private static Optional<Feature> loadFeature(String featureValue) {
        URI uri;
        try {
            uri = featureUri(featureValue);
        } catch (RuntimeException exception) {
            warnSkipped(featureValue, exception);
            return Optional.empty();
        }
        GherkinMessagesFeatureParser parser = new GherkinMessagesFeatureParser();
        try (InputStream input = open(uri, featureValue)) {
            if (input == null) {
                return Optional.empty();
            }
            return parser.parse(uri, input, UUID::randomUUID);
        } catch (RuntimeException | IOException exception) {
            warnSkipped(featureValue, exception);
            return Optional.empty();
        }
    }

    static URI featureUri(String featureValue) {
        String value = featureValue == null ? "" : featureValue;
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() != null || !value.contains("\\")) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // A space or a raw Windows path is not a legal URI until it is normalized.
        }
        String normalized = value.replace('\\', '/');
        if (normalized.regionMatches(true, 0, "classpath:", 0, "classpath:".length())) {
            return URI.create("classpath:" + normalized.substring("classpath:".length()).replace(" ", "%20"));
        }
        try {
            return Path.of(normalized).toUri();
        } catch (RuntimeException failure) {
            return URI.create(normalized.replace(" ", "%20"));
        }
    }

    /**
     * Classpath lookup key. Percent-encoding is decoded and Windows separators
     * become {@code /} before {@link ClassLoader#getResourceAsStream}.
     */
    static String classpathResource(String featureValue) {
        String external = featureValue == null ? "" : featureValue;
        String resource;
        try {
            URI uri = URI.create(external);
            if (uri.getScheme() != null && uri.getScheme().equalsIgnoreCase("classpath")) {
                resource = uri.getSchemeSpecificPart();
            } else if (external.toLowerCase(Locale.ROOT).startsWith("classpath:")) {
                resource = decodeClasspath(external).substring("classpath:".length());
            } else {
                resource = external;
            }
        } catch (IllegalArgumentException invalid) {
            resource = external.toLowerCase(Locale.ROOT).startsWith("classpath:")
                    ? decodeClasspath(external).substring("classpath:".length())
                    : external;
        }
        resource = resource.replace('\\', '/');
        while (resource.startsWith("/")) {
            resource = resource.substring(1);
        }
        return resource;
    }

    private static String decodeClasspath(String value) {
        String resource = value;
        if (resource.toLowerCase(Locale.ROOT).startsWith("classpath:")) {
            resource = resource.substring("classpath:".length());
        }
        try {
            return "classpath:" + URLDecoder.decode(resource.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return "classpath:" + resource;
        }
    }

    private static void warnSkipped(String featureValue, Exception exception) {
        String detail = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        System.err.println("WARNING: pkb_example skipped unreadable feature " + featureValue + ": " + detail);
    }

    private static InputStream open(URI uri, String featureValue) throws IOException {
        String external = uri.toString();
        String original = featureValue == null ? external : featureValue;
        if (external.toLowerCase(Locale.ROOT).startsWith("classpath:")
                || original.toLowerCase(Locale.ROOT).startsWith("classpath:")) {
            String resource = classpathResource(original);
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            if (loader == null) {
                loader = ExampleRowFilter.class.getClassLoader();
            }
            InputStream input = loader.getResourceAsStream(resource);
            if (input == null) {
                warnSkipped(original, new IOException("Classpath feature was not found: " + resource));
                return null;
            }
            return input;
        }
        try {
            return Files.newInputStream(Path.of(uri));
        } catch (RuntimeException | IOException first) {
            try {
                return uri.toURL().openStream();
            } catch (RuntimeException | IOException second) {
                warnSkipped(original, second);
                return null;
            }
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
