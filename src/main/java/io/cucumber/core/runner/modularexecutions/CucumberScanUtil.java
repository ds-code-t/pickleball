package io.cucumber.core.runner.modularexecutions;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.CucumberPropertiesParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static tools.dscode.pickleruntime.CucumberOptionResolver.features;

public final class CucumberScanUtil {

    /**
     * Cache only the AspectJ-modified source per feature URI, keyed by the normalized feature-URI set.
     * This avoids reusing parsed Feature/Pickle object graphs across calls.
     */
    private static final ConcurrentHashMap<String, Map<URI, String>> FEATURE_CACHE = new ConcurrentHashMap<>();

    private static String globalFeaturePathsString;

    // Default directories if cucumber.features is not provided
    private static final String[] DEFAULT_FEATURE_DIRS = {
            "src/test/resources/features",
            "src/main/resources/features"
    };

    private CucumberScanUtil() {
    }

    public static synchronized String getGlobalFeaturePathsString() {
        if (globalFeaturePathsString == null) {
            List<String> features = features().stream()
                    .map(FilePathResolver::toAbsoluteFileUri)
                    .toList();
            globalFeaturePathsString = features.isEmpty()
                    ? normalizeKey(List.of(DEFAULT_FEATURE_DIRS))
                    : normalizeKey(features);
        }
        return globalFeaturePathsString;
    }

    public static List<Pickle> listPicklesByTags(String tagString) {
        String featurePathsOption = getGlobalFeaturePathsString();

        Map<String, String> featureOptions = new HashMap<>();
        featureOptions.put("cucumber.features", featurePathsOption);
        RuntimeOptions runtimeOptions = new CucumberPropertiesParser().parse(featureOptions).build();

        List<Feature> features = getFreshFeaturesFromCachedSources(featurePathsOption, runtimeOptions);

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
     * Important keys you may pass:
     * - cucumber.features (comma-separated URIs; file: or classpath:)
     * - cucumber.filter.tags (tag expression)
     * - cucumber.filter.name (regex)
     * - (others are passed through; glue not required for listing)
     */
    public static List<Pickle> listPickles(Map<String, String> cucumberProps) {
        Objects.requireNonNull(cucumberProps, "cucumberProps");

        // 1) Ensure we have a concrete set of feature URIs in props (inject defaults if missing)
        Map<String, String> effectiveProps = new HashMap<>(cucumberProps);
        List<String> featureUris = resolveFeatureUris(effectiveProps.get("cucumber.features"));
        effectiveProps.put("cucumber.features", String.join(",", featureUris));

        // 2) Let Cucumber parse options (tags, name, lines, etc.)
        RuntimeOptions options = new CucumberPropertiesParser().parse(effectiveProps).build();

        // 3) Load cached modified source, rebuild fresh Feature objects every call
        String cacheKey = normalizeKey(featureUris);
        List<Feature> features = getFreshFeaturesFromCachedSources(cacheKey, options);

        // 4) Apply filters (tags, name, line filters)
        Filters filters = new Filters(options);
        return features.stream()
                .flatMap(f -> f.getPickles().stream())
                .filter(filters::test)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Clear cached modified feature source.
     */
    public static void clearCache() {
        FEATURE_CACHE.clear();
        globalFeaturePathsString = null;
    }

    // --------------------- source cache / rebuild ---------------------

    /**
     * Returns fresh Feature instances rebuilt from cached modified source.
     * The cache stores only source text, never parsed Feature graphs.
     */
    private static List<Feature> getFreshFeaturesFromCachedSources(String cacheKey, Options featureOptions) {
        Map<URI, String> cachedSources = getOrLoadModifiedSources(cacheKey, featureOptions);
        return rebuildFeaturesFromSources(cachedSources);
    }


    private static final Object LOCK = new Object();

    /**
     * Gets cached modified source for the feature set, loading it once by parsing through the normal
     * FeaturePathFeatureSupplier pipeline and extracting Feature.getSource().
     */
    private static Map<URI, String> getOrLoadModifiedSources(String cacheKey, Options featureOptions) {
        synchronized (LOCK) {
            return FEATURE_CACHE.computeIfAbsent(cacheKey, k -> loadModifiedSources(featureOptions));
        }
    }

    /**
     * Parses features once through the normal pipeline and caches only URI -> modified source.
     */
    private static Map<URI, String> loadModifiedSources(Options featureOptions) {
        List<Feature> parsedFeatures = parseFeatures(featureOptions);
        Map<URI, String> sourceMap = new LinkedHashMap<>();

        for (Feature feature : parsedFeatures) {
            URI uri = Objects.requireNonNull(feature.getUri(), "feature uri");
            String source = Objects.requireNonNull(feature.getSource(), "feature source");
            sourceMap.put(uri, source);
        }

        return Collections.unmodifiableMap(sourceMap);
    }

    /**
     * Rebuild fresh Feature instances from already-cached modified source strings.
     */
    private static List<Feature> rebuildFeaturesFromSources(Map<URI, String> sourceMap) {
        GherkinMessagesFeatureParser parser = new GherkinMessagesFeatureParser();
        List<Feature> rebuilt = new ArrayList<>(sourceMap.size());

        for (Map.Entry<URI, String> entry : sourceMap.entrySet()) {
            Feature feature = parser.parse(entry.getKey(), entry.getValue(), UUID::randomUUID)
                    .orElseThrow(() -> new IllegalStateException("No feature parsed for: " + entry.getKey()));
            rebuilt.add(feature);
        }

        return rebuilt;
    }

    // --------------------- internals ---------------------

    private static List<Feature> parseFeatures(Options featureOptions) {
        Supplier<ClassLoader> cl = Thread.currentThread()::getContextClassLoader;
        FeatureParser parser = new FeatureParser(UUID::randomUUID);
        return new FeaturePathFeatureSupplier(cl, featureOptions, parser).get();
    }

    private static String normalizeKey(List<String> featureUris) {
        return featureUris.stream()
                .map(String::trim)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private static List<String> resolveFeatureUris(String cucumberFeaturesProp) {
        if (cucumberFeaturesProp != null && !cucumberFeaturesProp.isBlank()) {
            return Arrays.stream(cucumberFeaturesProp.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(CucumberScanUtil::toUriString)
                    .collect(Collectors.toList());
        }

        List<String> features = features().stream()
                .map(FilePathResolver::toAbsoluteFileUri)
                .toList();

        return features.isEmpty()
                ? List.of(DEFAULT_FEATURE_DIRS)
                : features;
    }

    private static String toUriString(String pathOrUri) {
        String s = pathOrUri.trim();
        if (s.startsWith("classpath:") || s.startsWith("file:")) {
            return s;
        }
        return FilePathResolver.toAbsoluteFileUri(s);
    }

    // --------------------- demo ---------------------

    public static void main(String[] args) {
        Map<String, String> props = new HashMap<>();
        props.put("cucumber.filter.tags", "@TagK");

        List<Pickle> pickles = CucumberScanUtil.listPickles(props);
        System.out.println("Matching scenarios: " + pickles.size());
        for (Pickle p : pickles) {
            System.out.println(" - " + p.getName() + " [" + p.getUri() + ":" + p.getLocation().getLine() + "]");
        }

        List<Pickle> again = CucumberScanUtil.listPickles(props);
        System.out.println("Second call (rebuilt from cached source): " + again.size());
    }
}