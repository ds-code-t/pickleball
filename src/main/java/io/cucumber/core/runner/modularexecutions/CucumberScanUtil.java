package io.cucumber.core.runner.modularexecutions;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.CucumberPropertiesParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.cucumber.core.runner.modularexecutions.FilePathResolver.findFileDirectoryPaths;
import static tools.dscode.testengine.DynamicSuiteConfigUtils.getFeaturePaths;

public final class CucumberScanUtil {

    /**
     * Custom, non-standard CucumberScanUtil option.
     *
     * This is intentionally removed from the properties before passing them to
     * CucumberPropertiesParser, then applied directly to parsed Feature objects.
     */
    public static final String PKB_FILTER_FEATURE_NAME = "pkb_featurename";

    // Cache parsed Features keyed by normalized, sorted feature-URI list
    private static final ConcurrentHashMap<String, List<Feature>> FEATURE_CACHE = new ConcurrentHashMap<>();

    private static String globalFeaturePathsString;

    public static synchronized String getGlobalFeaturePathsString() {
        if (globalFeaturePathsString == null) {
            List<String> features = getFeaturePaths().stream().map(FilePathResolver::toAbsoluteFileUri).toList();
            globalFeaturePathsString = features.isEmpty() ? normalizeKey(List.of(DEFAULT_FEATURE_DIRS)) : normalizeKey(features);
        }
        return globalFeaturePathsString;
    }

    // Default directories if cucumber.features is not provided
    private static final String[] DEFAULT_FEATURE_DIRS = {
            "src/test/resources/features",
            "src/main/resources/features"
    };

    private CucumberScanUtil() {
    }

    public static List<Pickle> listPicklesByTags(String tagString) {
        String featurePathsOption = getGlobalFeaturePathsString();
        List<Feature> features = FEATURE_CACHE.computeIfAbsent(featurePathsOption, k -> {
            Map<String, String> featureOptions = new HashMap<>();
            featureOptions.put("cucumber.features", k);
            RuntimeOptions runtimeOptions = new CucumberPropertiesParser().parse(featureOptions).build();
            return parseFeatures(runtimeOptions);
        });

        Map<String, String> cucumberProps = new HashMap<>();
        cucumberProps.put("cucumber.filter.tags", tagString);
        RuntimeOptions cucumberOptions = new CucumberPropertiesParser().parse(cucumberProps).build();
        Filters filters = new Filters(cucumberOptions);

        return features.stream()
                .flatMap(f -> f.getPickles().stream())
                .filter(filters::test)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * List matching scenarios (Pickles) for the given cucumber.* properties.
     *
     * Important keys you may pass:
     * - cucumber.features (comma-separated URIs; file: or classpath:)
     * - pkb_featurename (custom literal Feature: name filter)
     * - cucumber.filter.tags (tag expression)
     * - cucumber.filter.name (regex)
     * - others are passed through; glue is not required for listing
     *
     * The custom pkb_featurename option is applied first at the
     * parsed Feature level, before Cucumber's Pickle filters are applied.
     */
    public static List<Pickle> listPickles(Map<String, String> cucumberProps) {
        Objects.requireNonNull(cucumberProps, "cucumberProps");

        // 1) Copy caller props and remove custom options before passing props
        // to CucumberPropertiesParser.
        Map<String, String> effectiveProps = new HashMap<>(cucumberProps);
        String featureName = removeFeatureNameFilter(effectiveProps);

        // 2) Ensure we have a concrete set of feature URIs in props (inject
        // defaults if missing).
        List<String> featureUris = resolveFeatureUris(effectiveProps.get("cucumber.features"));
        effectiveProps.put("cucumber.features", String.join(",", featureUris));

        // 3) Let Cucumber parse standard options (tags, name, lines, etc.).
        RuntimeOptions options = new CucumberPropertiesParser().parse(effectiveProps).build();

        // 4) Load & cache features for this URI set.
        String cacheKey = normalizeKey(featureUris);
        List<Feature> features = FEATURE_CACHE.computeIfAbsent(cacheKey, k -> parseFeatures(options));

        // 5) Apply custom Feature: name filtering first, then Cucumber's
        // standard Pickle filters (tags, name, line filters).
        Filters filters = new Filters(options);
        Stream<Feature> featureStream = features.stream();
        if (featureName != null) {
            featureStream = featureStream.filter(feature -> featureName.equals(getFeatureName(feature).orElse(null)));
        }

        return featureStream
                .flatMap(f -> f.getPickles().stream())
                .filter(filters::test)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Clear cached parsed features (e.g., after file changes).
     */
    public static void clearCache() {
        FEATURE_CACHE.clear();
    }

    /**
     * Finds exactly one Pickle by literal Gherkin feature name and literal scenario name.
     *
     * The featureName is the text after "Feature:".
     * The scenarioName is the text after "Scenario:" or "Scenario Outline:".
     */
    public static Pickle getPickleByFeatureAndScenarioName(String featureName, String scenarioName) {
        Objects.requireNonNull(featureName, "featureName");
        Objects.requireNonNull(scenarioName, "scenarioName");

        Map<String, String> props = new HashMap<>();
        props.put(PKB_FILTER_FEATURE_NAME, featureName);
        props.put("cucumber.filter.name", exactNameRegex(scenarioName));

        return requireSinglePickle(
                listPickles(props),
                "feature name [%s] and scenario name [%s]".formatted(featureName, scenarioName)
        );
    }

    /**
     * Finds exactly one Pickle by literal scenario name.
     *
     * The scenarioName is the text after "Scenario:" or "Scenario Outline:".
     *
     * This searches across all resolved feature files/directories.
     */
    public static Pickle getPickleByScenarioName(String scenarioName) {
        Objects.requireNonNull(scenarioName, "scenarioName");

        Map<String, String> props = new HashMap<>();
        props.put("cucumber.filter.name", exactNameRegex(scenarioName));

        return requireSinglePickle(
                listPickles(props),
                "scenario name [%s]".formatted(scenarioName)
        );
    }

    // --------------------- internals ---------------------

    private static List<Feature> parseFeatures(Options featureOptions) {
        Supplier<ClassLoader> cl = Thread.currentThread()::getContextClassLoader;
        FeatureParser parser = new FeatureParser(UUID::randomUUID);
        return new FeaturePathFeatureSupplier(cl, featureOptions, parser).get();
    }

    private static String normalizeKey(List<String> featureUris) {
        return featureUris.stream().map(String::trim).sorted().collect(Collectors.joining(","));
    }

    private static List<String> resolveFeatureUris(String cucumberFeaturesProp) {
        if (cucumberFeaturesProp != null && !cucumberFeaturesProp.isBlank()) {
            return Arrays.stream(cucumberFeaturesProp.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(CucumberScanUtil::toUriString)
                    .collect(Collectors.toList());
        }

        // No cucumber.features provided → use defaults that actually exist
        List<String> uris = new ArrayList<>();
        try {
            for (String path : findFileDirectoryPaths("/src/**/*.feature")) {
                String u = toExistingUriOrNull(path);
                if (u != null) {
                    uris.add(u);
                }
            }
            return uris;
        } catch (IOException e) {
            if (uris.isEmpty()) {
                uris.add("classpath:features");
            }
            return uris;
        }
    }

    private static String toExistingUriOrNull(String pathOrUri) {
        String uri = toUriString(pathOrUri);
        if (uri.startsWith("classpath:")) {
            return uri; // let Cucumber resolve
        }

        try {
            String noScheme = uri.startsWith("file:") ? uri.substring("file:".length()) : uri;
            Path p = Paths.get(noScheme);
            return Files.exists(p) ? uri : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String toUriString(String pathOrUri) {
        String s = pathOrUri.trim();
        if (s.startsWith("classpath:") || s.startsWith("file:")) {
            return s;
        }

        Path abs = Paths.get(s).toAbsolutePath();
        return "file:" + abs.toString().replace('\\', '/');
    }

    private static Optional<String> getFeatureName(Feature feature) {
        Object rawName = feature.getName();
        if (rawName instanceof Optional<?> optionalName) {
            return optionalName.map(Object::toString);
        }
        return Optional.ofNullable(rawName).map(Object::toString);
    }

    private static Pickle requireSinglePickle(List<Pickle> matches, String selectionDescription) {
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No scenario found for " + selectionDescription);
        }

        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "Expected exactly one scenario for %s, but found %d. " +
                            "This can happen with duplicate scenario names or Scenario Outline examples."
                                    .formatted(selectionDescription, matches.size())
            );
        }

        return matches.get(0);
    }

    private static String exactNameRegex(String literalName) {
        return "^" + Pattern.quote(literalName) + "$";
    }

    private static String removeFeatureNameFilter(Map<String, String> props) {
        return trimToNull(props.remove(PKB_FILTER_FEATURE_NAME));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
