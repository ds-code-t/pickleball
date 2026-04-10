package io.cucumber.core.runner.modularexecutions;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.filter.Filters;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.messages.GherkinMessagesFeatureParser;
import io.cucumber.core.options.CucumberPropertiesParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static tools.dscode.pickleruntime.CucumberOptionResolver.features;

public final class CucumberScanUtil {

    private static final GherkinMessagesFeatureParser FEATURE_PARSER = new GherkinMessagesFeatureParser();
    /**
     * Cache only the resolved feature URIs per normalized feature-URI set.
     * This avoids retaining large feature source strings or parsed Feature graphs.
     */
    private static final ConcurrentHashMap<String, List<URI>> FEATURE_CACHE = new ConcurrentHashMap<>();

    /**
     * Small per-URI lock map to reduce duplicate concurrent parsing of the same file.
     * This is intentionally lightweight and only locks one URI at a time.
     */
    private static final ConcurrentHashMap<URI, Object> FEATURE_PARSE_LOCKS = new ConcurrentHashMap<>();

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

        List<Feature> features = getFreshFeaturesFromCachedUris(featurePathsOption, runtimeOptions);

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

        Map<String, String> effectiveProps = new HashMap<>(cucumberProps);
        List<String> featureUris = resolveFeatureUris(effectiveProps.get("cucumber.features"));
        effectiveProps.put("cucumber.features", String.join(",", featureUris));

        RuntimeOptions options = new CucumberPropertiesParser().parse(effectiveProps).build();

        String cacheKey = normalizeKey(featureUris);
        List<Feature> features = getFreshFeaturesFromCachedUris(cacheKey, options);

        Filters filters = new Filters(options);
        return features.stream()
                .flatMap(f -> f.getPickles().stream())
                .filter(filters::test)
                .collect(Collectors.toUnmodifiableList());
    }



    public static Feature getFeatureFromUri(URI uri) {
        Objects.requireNonNull(uri, "uri");

        Object lock = FEATURE_PARSE_LOCKS.computeIfAbsent(uri, k -> new Object());

        synchronized (lock) {
            try {
                String source = Files.readString(Path.of(uri));
                return FEATURE_PARSER
                        .parse(uri, source, UUID::randomUUID)
                        .orElseThrow(() -> new IllegalStateException("No feature parsed for: " + uri));
            } catch (IOException e) {
                throw new RuntimeException("Failed reading feature source from: " + uri, e);
            } finally {
                FEATURE_PARSE_LOCKS.remove(uri, lock);
            }
        }
    }

    /**
     * Clear cached feature URI lists.
     */
    public static void clearCache() {
        FEATURE_CACHE.clear();
        FEATURE_PARSE_LOCKS.clear();
        globalFeaturePathsString = null;
    }

    // --------------------- uri cache / rebuild ---------------------

    /**
     * Returns fresh Feature instances rebuilt from cached URIs.
     * The cache stores only URIs, never source text or parsed Feature graphs.
     */
    private static List<Feature> getFreshFeaturesFromCachedUris(String cacheKey, Options featureOptions) {
        List<URI> cachedUris = getOrLoadFeatureUris(cacheKey, featureOptions);
        List<Feature> rebuilt = new ArrayList<>(cachedUris.size());

        for (URI uri : cachedUris) {
            rebuilt.add(getFeatureFromUri(uri));
        }

        return rebuilt;
    }

    /**
     * Gets cached feature URIs for the feature set, loading them once
     * through the normal FeaturePathFeatureSupplier pipeline.
     */
    private static List<URI> getOrLoadFeatureUris(String cacheKey, Options featureOptions) {
        return FEATURE_CACHE.computeIfAbsent(cacheKey, k -> loadFeatureUris(featureOptions));
    }

    /**
     * Parses features once through the normal pipeline and caches only the URIs.
     */
    private static List<URI> loadFeatureUris(Options featureOptions) {
        return parseFeatures(featureOptions).stream()
                .map(feature -> Objects.requireNonNull(feature.getUri(), "feature uri"))
                .distinct()
                .collect(Collectors.toUnmodifiableList());
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
        System.out.println("Second call (rebuilt from cached URIs): " + again.size());
    }
}