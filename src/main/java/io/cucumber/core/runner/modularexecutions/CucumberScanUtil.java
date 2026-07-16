package io.cucumber.core.runner.modularexecutions;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.CucumberPropertiesParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import org.intellij.lang.annotations.Language;
import tools.dscode.testengine.DynamicSuiteConfigUtils;

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

import static io.cucumber.core.options.Constants.EXECUTION_LIMIT_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.EXECUTION_ORDER_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FEATURES_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_NAME_PROPERTY_NAME;
import static io.cucumber.core.options.Constants.FILTER_TAGS_PROPERTY_NAME;
import static io.cucumber.core.runner.ScenarioStep.createScenarioStep;
import static tools.dscode.common.GlobalConstants.COMPONENT_TAG_META_CHAR;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.testengine.DynamicSuiteConfigUtils.getFeaturePaths;
import static tools.dscode.testengine.PKB_props.PKB_FEATURES;
import static tools.dscode.testengine.PKB_props.PKB_FEATURE_NAME;
import static tools.dscode.testengine.PKB_props.PKB_LIMIT;
import static tools.dscode.testengine.PKB_props.PKB_NAME;
import static tools.dscode.testengine.PKB_props.PKB_ORDER;
import static tools.dscode.testengine.PKB_props.PKB_TAGS;

public final class CucumberScanUtil {

    // Cache parsed Features keyed by normalized, sorted feature-URI list
    private static final ConcurrentHashMap<String, List<Feature>> FEATURE_CACHE = new ConcurrentHashMap<>();

    private CucumberScanUtil() {
    }

    /**
     * Convenience wrapper around {@link #listPickles(Map)} that filters by tag expression only.
     */
    public static List<Pickle> listPicklesByTags(String tagString) {
        Objects.requireNonNull(tagString, "tagString");
        Map<String, String> props = new HashMap<>();
        props.put(PKB_TAGS, tagString);
        return listPickles(props);
    }

    static final @Language("RegExp") String tagRegexReplacement = "(?<!@)(" + COMPONENT_TAG_META_CHAR + "[A-Za-z])";
    final static String RUN_TAGS = "Run Tags";


    /**
     * List matching scenarios (Pickles) for the given pkb_* or cucumber.* properties.
     * <p>
     * Supported pkb_* keys (defined in {@link tools.dscode.testengine.PKB_props}; each maps to the equivalent Cucumber option):
     * - pkb_featurename — literal Feature: name filter (applied at Feature level)
     * - pkb_features — comma-separated feature paths/URIs → cucumber.features
     * - pkb_tags — tag expression → cucumber.filter.tags
     * - pkb_name — scenario name regex → cucumber.filter.name
     * - pkb_order — lexical | reverse | random | random:&lt;seed&gt; → cucumber.execution.order
     * - pkb_limit — max scenarios to return → cucumber.execution.limit
     * <p>
     * Feature paths: when {@code pkb_features} or {@code cucumber.features} is present in the
     * input map (even if the value is null or blank), that value is used. When neither key is
     * present, paths default to {@link DynamicSuiteConfigUtils#getFeaturePaths()}.
     * <p>
     * At least one scenario-level filter (tags, scenario name, or feature name) must have a
     * non-blank value; otherwise an empty list is returned. When filters are present but
     * nothing matches, {@link IllegalArgumentException} is thrown with filter details.
     */
    public static List<Pickle> listPickles(Map<String, String> cucumberProps) {
        Objects.requireNonNull(cucumberProps, "cucumberProps");

        if (!hasScenarioLevelFilters(cucumberProps)) {
            return List.of();
        }

        Map<String, String> effectiveProps = normalizePkbProps(cucumberProps);
        applyRunTagNormalization(effectiveProps, cucumberProps);
        String featureName = removeFeatureNameFilter(effectiveProps);

        List<String> featureUris = resolveFeatureUrisForListPickles(cucumberProps);
        effectiveProps.put(FEATURES_PROPERTY_NAME, String.join(",", featureUris));

        RuntimeOptions options = new CucumberPropertiesParser().parse(effectiveProps).build();

        String cacheKey = normalizeKey(featureUris);
        List<Feature> features = FEATURE_CACHE.computeIfAbsent(cacheKey, k -> parseFeatures(options));

        Filters filters = new Filters(options);
        Stream<Feature> featureStream = features.stream();
        if (featureName != null) {
            featureStream = featureStream.filter(feature -> featureName.equals(getFeatureName(feature).orElse(null)));
        }

        List<Pickle> pickles = featureStream
                .flatMap(f -> f.getPickles().stream())
                .filter(filters::test)
                .collect(Collectors.toCollection(ArrayList::new));

        if (pickles.isEmpty()) {
            throw noScenariosMatchedException(effectiveProps, featureName, featureUris);
        }

        return applyOrderAndLimit(options, pickles);
    }

    /**
     * Clear cached parsed features (e.g., after file changes).
     */
    public static void clearCache() {
        FEATURE_CACHE.clear();
    }

    /**
     * Finds exactly one Pickle by optional literal Gherkin feature name and literal scenario name.
     * <p>
     * The featureName is the text after "Feature:". If featureName is null or blank,
     * it is ignored and only the scenarioName filter is used.
     * <p>
     * The scenarioName is the text after "Scenario:" or "Scenario Outline:".
     */
    public static Pickle getPickleByFeatureAndScenarioName(String featureName, String scenarioName) {
        Objects.requireNonNull(scenarioName, "scenarioName");

        if (scenarioName.isBlank()) {
            throw new IllegalArgumentException("scenarioName must not be blank");
        }

        Map<String, String> props = new HashMap<>();

        String trimmedFeatureName = trimToNull(featureName);
        if (trimmedFeatureName != null) {
            props.put(PKB_FEATURE_NAME, trimmedFeatureName);
        }

        props.put(PKB_NAME, exactNameRegex(scenarioName.trim()));

        String selectionDescription = trimmedFeatureName == null
                ? "scenario name [%s]".formatted(scenarioName.trim())
                : "feature name [%s] and scenario name [%s]".formatted(trimmedFeatureName, scenarioName.trim());

        return requireSinglePickle(listPickles(props), selectionDescription);
    }


    /**
     * Finds exactly one Pickle by literal scenario name.
     * <p>
     * The scenarioName is the text after "Scenario:" or "Scenario Outline:".
     * <p>
     * This searches across all resolved feature files/directories.
     */
    public static Pickle getPickleByScenarioName(String scenarioName) {
        Objects.requireNonNull(scenarioName, "scenarioName");

        String trimmedScenarioName = scenarioName.trim();
        if (trimmedScenarioName.isBlank()) {
            throw new IllegalArgumentException("scenarioName must not be blank");
        }

        Map<String, String> props = new HashMap<>();
        props.put(PKB_NAME, exactNameRegex(trimmedScenarioName));

        return requireSinglePickle(
                listPickles(props),
                "scenario name [%s]".formatted(trimmedScenarioName)
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

    private static boolean hasScenarioLevelFilters(Map<String, String> inputProps) {
        return hasNonBlankFilterValue(inputProps, RUN_TAGS)
                || hasNonBlankFilterValue(inputProps, PKB_TAGS)
                || hasNonBlankFilterValue(inputProps, FILTER_TAGS_PROPERTY_NAME)
                || hasNonBlankFilterValue(inputProps, PKB_NAME)
                || hasNonBlankFilterValue(inputProps, FILTER_NAME_PROPERTY_NAME)
                || hasNonBlankFilterValue(inputProps, PKB_FEATURE_NAME);
    }

    private static boolean hasNonBlankFilterValue(Map<String, String> props, String key) {
        if (!props.containsKey(key)) {
            return false;
        }
        String value = props.get(key);
        return value != null && !value.isBlank();
    }

    private static IllegalArgumentException noScenariosMatchedException(
            Map<String, String> effectiveProps,
            String featureName,
            List<String> featureUris
    ) {
        StringBuilder message = new StringBuilder("No scenarios matched the provided filters:");
        appendFilterDetail(message, "tags", effectiveProps.get(FILTER_TAGS_PROPERTY_NAME));
        appendFilterDetail(message, "name", effectiveProps.get(FILTER_NAME_PROPERTY_NAME));
        appendFilterDetail(message, "featureName", featureName);
        if (!featureUris.isEmpty()) {
            message.append(" features=[").append(String.join(",", featureUris)).append(']');
        }
        return new IllegalArgumentException(message.toString());
    }

    private static void appendFilterDetail(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append(' ').append(label).append("=[").append(value).append(']');
        }
    }

    private static void applyRunTagNormalization(Map<String, String> effectiveProps, Map<String, String> inputProps) {
        String tagString = inputProps.getOrDefault(RUN_TAGS, inputProps.getOrDefault(PKB_TAGS, ""));
        if (tagString.isBlank()) {
            tagString = effectiveProps.getOrDefault(FILTER_TAGS_PROPERTY_NAME, "");
        }
        if (tagString.isBlank()) {
            return;
        }

        String normalizedTags = tagString.replaceAll(tagRegexReplacement, "@$1");
        effectiveProps.put(FILTER_TAGS_PROPERTY_NAME, normalizedTags);
    }

    private static Map<String, String> normalizePkbProps(Map<String, String> inputProps) {
        Map<String, String> props = new HashMap<>(inputProps);

        mapPkbToCucumber(props, PKB_TAGS, FILTER_TAGS_PROPERTY_NAME);
        mapPkbToCucumber(props, PKB_NAME, FILTER_NAME_PROPERTY_NAME);
        mapPkbToCucumber(props, PKB_ORDER, EXECUTION_ORDER_PROPERTY_NAME);
        mapPkbToCucumber(props, PKB_LIMIT, EXECUTION_LIMIT_PROPERTY_NAME);

        props.remove(PKB_TAGS);
        props.remove(PKB_NAME);
        props.remove(PKB_ORDER);
        props.remove(PKB_LIMIT);
        props.remove(PKB_FEATURES);

        return props;
    }

    private static void mapPkbToCucumber(Map<String, String> props, String pkbKey, String cucumberKey) {
        String pkbValue = trimToNull(props.get(pkbKey));
        if (pkbValue != null) {
            props.put(cucumberKey, pkbValue);
        }
    }

    private static List<String> resolveFeatureUrisForListPickles(Map<String, String> inputProps) {
        if (inputProps.containsKey(PKB_FEATURES)) {
            return resolveExplicitFeaturePaths(inputProps.get(PKB_FEATURES));
        }
        if (inputProps.containsKey(FEATURES_PROPERTY_NAME)) {
            return resolveExplicitFeaturePaths(inputProps.get(FEATURES_PROPERTY_NAME));
        }
        return resolveGlobalFeaturePaths();
    }

    private static List<String> resolveExplicitFeaturePaths(String featurePathsProp) {
        if (featurePathsProp == null || featurePathsProp.isBlank()) {
            return List.of();
        }

        return Arrays.stream(featurePathsProp.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(CucumberScanUtil::toUriString)
                .collect(Collectors.toList());
    }

    private static List<String> resolveGlobalFeaturePaths() {
        return getFeaturePaths().stream()
                .map(FilePathResolver::toAbsoluteFileUri)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<Pickle> applyOrderAndLimit(RuntimeOptions options, List<Pickle> pickles) {
        List<Pickle> ordered = options.getPickleOrder().orderPickles(pickles);
        int limit = options.getLimitCount();
        if (limit > 0 && ordered.size() > limit) {
            return List.copyOf(ordered.subList(0, limit));
        }
        return List.copyOf(ordered);
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
        return trimToNull(props.remove(PKB_FEATURE_NAME));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static Object getStepReturn(String stepAddress) {
        List<String> segments = Arrays.stream(stepAddress.split("\\.")).toList();
        try {
            Pickle gherkinMessagesPickle = getPickleByFeatureAndScenarioName(segments.get(0), segments.get(1));

            String stepText = segments.get(2);
            gherkinMessagesPickle.getSteps().removeIf(step -> !stepText.equals(step.getText()));
            if (gherkinMessagesPickle.getSteps().isEmpty())
                throw new IllegalArgumentException("Step not found: " + stepText);
            if (gherkinMessagesPickle.getSteps().size() > 1)
                throw new IllegalArgumentException("Multiple steps found with the same text: " + stepText);

            io.cucumber.messages.types.Pickle pickle = (io.cucumber.messages.types.Pickle) getProperty(gherkinMessagesPickle, "pickle");
            ScenarioStep currentScenarioNameStep = createScenarioStep(gherkinMessagesPickle);
            StepExtension stepExtension = (StepExtension) currentScenarioNameStep.childSteps.getFirst();
            if (pickle.getValueRow() != null && !pickle.getValueRow().isEmpty()) {
                stepExtension.getDefaultStepNodeMap().merge(pickle.getHeaderRow(), pickle.getValueRow());
                stepExtension.getStepParsingMap().addMaps(stepExtension.getDefaultStepNodeMap());
            }
            StepExtension newStep = new StepExtension(stepExtension.testCase, stepExtension.resolveAndClone(stepExtension.getStepParsingMap()));
            if (pickle.getValueRow() != null && !pickle.getValueRow().isEmpty()) {
                newStep.getDefaultStepNodeMap().merge(pickle.getHeaderRow(), pickle.getValueRow());
                newStep.getStepParsingMap().addMaps(newStep.getDefaultStepNodeMap());
            }
            return newStep.runAndGetReturnValue();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}