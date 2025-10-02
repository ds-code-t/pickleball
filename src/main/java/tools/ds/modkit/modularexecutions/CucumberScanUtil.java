package tools.ds.modkit.modularexecutions;

import io.cucumber.core.feature.FeatureParser;
import io.cucumber.core.feature.Options;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.options.CucumberPropertiesParser;
import io.cucumber.core.options.RuntimeOptions;
import io.cucumber.core.runtime.FeaturePathFeatureSupplier;
import io.cucumber.core.filter.Filters;
import tools.ds.modkit.EnsureInstalled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static tools.ds.modkit.modularexecutions.FilePathResolver.findFileDirectoryPaths;

public final class CucumberScanUtil {

    static {
        EnsureInstalled.ensureOrDie();
    }

    // Cache parsed Features keyed by normalized, sorted feature-URI list
    private static final ConcurrentHashMap<String, List<Feature>> FEATURE_CACHE = new ConcurrentHashMap<>();

    // Default directories if cucumber.features is not provided
    private static final String[] DEFAULT_FEATURE_DIRS = {
            "src/test/resources/features",
            "src/main/resources/features"
    };

    private CucumberScanUtil() {
    }

    /**
     * List matching scenarios (Pickles) for the given cucumber.* properties.
     * Important keys you may pass:
     * - cucumber.features      (comma-separated URIs; file: or classpath:)
     * - cucumber.filter.tags   (tag expression)
     * - cucumber.filter.name   (regex)
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

        // 3) Load & cache features for this URI set
        String cacheKey = normalizeKey(featureUris);
        List<Feature> features = FEATURE_CACHE.computeIfAbsent(cacheKey, k -> parseFeatures(options));

        // 4) Apply filters (tags, name, line filters)
        Filters filters = new Filters(options);
        return features.stream()
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

    // --------------------- internals ---------------------

    private static List<Feature> parseFeatures(Options featureOptions) {
        Supplier<ClassLoader> cl = Thread.currentThread()::getContextClassLoader;
        FeatureParser parser = new FeatureParser(UUID::randomUUID);
        return new FeaturePathFeatureSupplier(cl, featureOptions, parser).get();
    }

    private static String normalizeKey(List<String> featureUris) {
        return featureUris.stream().map(String::trim).sorted().collect(Collectors.joining("|"));
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
                if (u != null) uris.add(u);
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
        if (uri.startsWith("classpath:")) return uri; // let Cucumber resolve
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
        if (s.startsWith("classpath:") || s.startsWith("file:")) return s;
        Path abs = Paths.get(s).toAbsolutePath();
        return "file:" + abs.toString().replace('\\', '/');
    }

    // --------------------- demo ---------------------

    public static void main(String[] args) {
        Map<String, String> props = new HashMap<>();
        // Supply filters the same way you would to Cucumber:
        props.put("cucumber.filter.tags", "@TagK");
        // props.put("cucumber.filter.name", ".*Calculator.*");

        // Optionally pin features explicitly:
        // props.put("cucumber.features", "file:src/test/resources/features");

        List<Pickle> pickles = CucumberScanUtil.listPickles(props);
        System.out.println("Matching scenarios: " + pickles.size());
        for (Pickle p : pickles) {
            System.out.println(" - " + p.getName() + " [" + p.getUri() + ":" + p.getLocation().getLine() + "]");
        }

        // Cache demo
        List<Pickle> again = CucumberScanUtil.listPickles(props);
        System.out.println("Second call (cached): " + again.size());
    }
}
