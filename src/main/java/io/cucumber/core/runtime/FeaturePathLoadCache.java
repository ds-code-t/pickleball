package io.cucumber.core.runtime;

import io.cucumber.core.feature.FeatureIdentifier;
import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.FeatureParserException;
import io.cucumber.core.resource.ClasspathSupport;
import io.cucumber.core.resource.ResourceScanner;
import io.cucumber.core.runner.Phase4Timing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Parses Cucumber features once per JVM for an unchanged path list.
 *
 * Surefire discovers the suite and then discovers it again before execution.
 * The second pass used to parse every feature file again. A cache hit returns
 * the same features when each file's size and last-modified time still match.
 * The first pass of many files is parsed in parallel. Scenario order stays the
 * URI order Cucumber already used. A normal run still selects the same files.
 */
public final class FeaturePathLoadCache {
    /** Test hook. Production stays false. The original supplier runs instead. */
    static volatile boolean bypass;

    private static final int CACHE_LIMIT = 4;
    private static final int PARALLEL_AT = 8;
    private static final AtomicInteger PARSES = new AtomicInteger();
    private static final AtomicInteger RESOURCE_PARSES = new AtomicInteger();
    private static final io.cucumber.core.gherkin.FeatureParser PARSER = resolveParser();
    private static final Map<String, Cached> CACHE = Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > CACHE_LIMIT;
        }
    });
    private static final Map<java.net.URI, ResourceCached> RESOURCE_CACHE = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<java.net.URI, ResourceCached> eldest) {
            return size() > 8000;
        }
    });

    private FeaturePathLoadCache() {
    }

    public static int parseCount() {
        return PARSES.get();
    }

    public static int resourceParseCount() {
        return RESOURCE_PARSES.get();
    }

    public static void clear() {
        CACHE.clear();
        RESOURCE_CACHE.clear();
        PARSES.set(0);
        RESOURCE_PARSES.set(0);
    }

    /**
     * JUnit's engine parses through {@code FeatureParser.parseResource} and
     * builds a new parser cache on every discovery. Surefire discovers twice.
     * This cache is the one that second pass hits.
     */
    public static Optional<Feature> parseResource(io.cucumber.core.resource.Resource resource, Supplier<Optional<Feature>> original) {
        if (bypass || resource == null) {
            return original.get();
        }
        java.net.URI uri = resource.getUri();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = FeaturePathLoadCache.class.getClassLoader();
        }
        long[] stat = stat(uri, loader);
        if (stat != null) {
            ResourceCached cached = RESOURCE_CACHE.get(uri);
            if (cached != null && cached.size == stat[0] && cached.modified == stat[1]) {
                Phase4Timing.mark("feature-parse-cache-hit");
                return cached.feature;
            }
        }
        Phase4Timing.mark("feature-parse-start");
        RESOURCE_PARSES.incrementAndGet();
        Optional<Feature> parsed = original.get();
        Phase4Timing.mark("feature-parse-done");
        if (stat != null && uri != null) {
            RESOURCE_CACHE.put(uri, new ResourceCached(stat[0], stat[1], parsed));
        }
        return parsed;
    }

    static List<Feature> load(List<URI> paths, Supplier<ClassLoader> classLoader, Supplier<List<Feature>> original) {
        if (bypass) {
            return original.get();
        }
        List<URI> requested = paths == null ? List.of() : paths;
        ClassLoader loader = classLoader == null ? FeaturePathLoadCache.class.getClassLoader() : classLoader.get();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        List<URI> found = enumerate(requested, loader);
        if (found.isEmpty()) {
            return original.get();
        }
        String pathsKey = loaderKey(loader, requested);
        String stamp = stamp(found, loader);
        if (stamp != null) {
            Cached cached = CACHE.get(pathsKey);
            if (cached != null && stamp.equals(cached.stamp)) {
                Phase4Timing.mark("feature-parse-cache-hit");
                return new ArrayList<>(cached.features);
            }
        }
        Phase4Timing.mark("feature-parse-start");
        List<Feature> features = parseAll(found, loader);
        Phase4Timing.mark("feature-parse-done");
        if (stamp != null) {
            CACHE.put(pathsKey, new Cached(stamp, features));
        }
        return new ArrayList<>(features);
    }

    private static String loaderKey(ClassLoader loader, List<URI> paths) {
        StringBuilder key = new StringBuilder();
        key.append(System.identityHashCode(loader)).append('\n');
        for (URI path : paths) {
            key.append(path).append('\n');
        }
        return key.toString();
    }

    private static List<URI> enumerate(List<URI> paths, ClassLoader loader) {
        List<URI> found = new ArrayList<>();
        ResourceScanner<Feature> scanner = new ResourceScanner<>(() -> loader, FeatureIdentifier::isFeature, resource -> {
            found.add(resource.getUri());
            return Optional.empty();
        });
        for (URI path : paths) {
            scanner.scanForResourcesUri(path);
        }
        return found;
    }

    private static List<Feature> parseAll(List<URI> uris, ClassLoader loader) {
        int count = uris.size();
        byte[][] bodies = new byte[count][];
        boolean parallel = count >= PARALLEL_AT && java.lang.Runtime.getRuntime().availableProcessors() > 1;
        if (parallel) {
            IntStream.range(0, count).parallel().forEach(index -> bodies[index] = read(uris.get(index), loader));
        } else {
            for (int index = 0; index < count; index++) {
                bodies[index] = read(uris.get(index), loader);
            }
        }
        Feature[] parsed = new Feature[count];
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        if (parallel) {
            IntStream.range(0, count).parallel().forEach(index -> parseOne(uris, bodies, parsed, failure, index));
        } else {
            for (int index = 0; index < count; index++) {
                parseOne(uris, bodies, parsed, failure, index);
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        FeaturePathFeatureSupplier.FeatureBuilder builder = new FeaturePathFeatureSupplier.FeatureBuilder();
        for (Feature feature : parsed) {
            if (feature != null) {
                builder.addUnique(feature);
            }
        }
        return builder.build();
    }

    private static void parseOne(
            List<URI> uris,
            byte[][] bodies,
            Feature[] parsed,
            AtomicReference<RuntimeException> failure,
            int index
    ) {
        if (failure.get() != null) {
            return;
        }
        try {
            PARSES.incrementAndGet();
            parsed[index] = PARSER.parse(uris.get(index), new java.io.ByteArrayInputStream(bodies[index]), UUID::randomUUID)
                    .orElse(null);
        } catch (IOException failureIo) {
            failure.compareAndSet(null, new FeatureParserException(
                    "Failed to parse resource at: " + uris.get(index), failureIo));
        } catch (RuntimeException failureRuntime) {
            failure.compareAndSet(null, failureRuntime);
        }
    }

    private static byte[] read(URI uri, ClassLoader loader) {
        try {
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                return Files.readAllBytes(Path.of(uri));
            }
            String name = ClasspathSupport.resourceName(uri);
            try (InputStream in = loader.getResourceAsStream(name)) {
                if (in == null) {
                    throw new IOException("Missing feature resource " + uri);
                }
                return in.readAllBytes();
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String stamp(List<URI> uris, ClassLoader loader) {
        List<String> rows = new ArrayList<>(uris.size());
        for (URI uri : uris) {
            long[] stat = stat(uri, loader);
            if (stat == null) {
                return null;
            }
            rows.add(uri.toString() + '\0' + stat[0] + '\0' + stat[1]);
        }
        Collections.sort(rows);
        return String.join("\n", rows);
    }

    private static long[] stat(URI uri, ClassLoader loader) {
        try {
            Path path = null;
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                path = Path.of(uri);
            } else if (uri != null && ClasspathSupport.CLASSPATH_SCHEME.equalsIgnoreCase(uri.getScheme())) {
                String name = ClasspathSupport.resourceName(uri);
                var url = loader.getResource(name);
                if (url != null && "file".equalsIgnoreCase(url.getProtocol())) {
                    path = Path.of(url.toURI());
                }
            }
            if (path == null || !Files.isRegularFile(path)) {
                return null;
            }
            return new long[] {Files.size(path), Files.getLastModifiedTime(path).toMillis()};
        } catch (Exception failure) {
            return null;
        }
    }

    private static io.cucumber.core.gherkin.FeatureParser resolveParser() {
        io.cucumber.core.gherkin.FeatureParser best = null;
        ServiceLoader<io.cucumber.core.gherkin.FeatureParser> loaded = ServiceLoader.load(
                io.cucumber.core.gherkin.FeatureParser.class,
                FeaturePathLoadCache.class.getClassLoader()
        );
        for (io.cucumber.core.gherkin.FeatureParser candidate : loaded) {
            if (best == null || candidate.version().compareTo(best.version()) > 0) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No Cucumber feature parser is available");
        }
        return best;
    }

    private record Cached(String stamp, List<Feature> features) {
    }

    private record ResourceCached(long size, long modified, Optional<Feature> feature) {
    }
}
