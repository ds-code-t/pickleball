package tools.dscode.engine.adapter.cucumber;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.FeatureParser;
import tools.dscode.engine.source.StoreScenarioSource;
import tools.dscode.store.StoreConfig;
import tools.dscode.store.StoreFeatureSource;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreQuery;
import tools.dscode.store.StoreRuntime;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * Cucumber features built from stored rows. A Store-on run uses this instead
 * of scanning a feature directory. A missing row is {@code error:} and
 * {@code fix: store hydrate}.
 */
public final class RowFeatureSupplier {
    private static final FeatureParser PARSER = resolveParser();

    private RowFeatureSupplier() {
    }

    public static List<Feature> load(List<URI> paths) {
        try {
            StoreRuntime.beforeFeatureLoad();
            Path project = StoreConfig.project();
            if (!StorePaths.ready(project)) throw StoreScenarioSource.missing(label(paths));
            LinkedHashSet<String> chosen = new LinkedHashSet<>();
            List<URI> requested = paths == null ? List.of() : paths;
            if (requested.isEmpty()) throw StoreScenarioSource.missing("");
            List<String> stored = new ArrayList<>();
            for (StoreQuery.FeatureSummary summary : StoreQuery.features(project)) stored.add(summary.path());
            for (URI uri : requested) {
                List<String> keys = StoreFeatureSource.keys(uri);
                if (keys.isEmpty()) throw StoreScenarioSource.missing(String.valueOf(uri));
                boolean hit = false;
                for (String key : keys) {
                    String normalized = key.replace('\\', '/');
                    String prefix = normalized.endsWith("/") ? normalized : normalized + "/";
                    for (String path : stored) {
                        if (path.equals(normalized) || path.startsWith(prefix)) {
                            chosen.add(path);
                            hit = true;
                        }
                    }
                }
                if (!hit) throw StoreScenarioSource.missing(String.valueOf(uri));
            }
            List<Feature> features = new ArrayList<>();
            for (String path : chosen) {
                StoreQuery.FileDetail detail = StoreQuery.file(project, path);
                if (detail == null || detail.text() == null) throw StoreScenarioSource.missing(path);
                String text = StoreScenarioSource.executableText(project, path, detail.text());
                if (text == null) continue;
                byte[] body = text.equals(detail.text())
                        ? text.getBytes(StandardCharsets.UTF_8)
                        : text.getBytes(StandardCharsets.UTF_8);
                parseBytes(URI.create("store:" + path), body).ifPresent(features::add);
            }
            return List.copyOf(features);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static Optional<Feature> parseStored(URI uri) {
        try {
            StoreRuntime.beforeFeatureLoad();
            byte[] bytes = StoreFeatureSource.bytesFor(uri);
            if (bytes == null) throw StoreScenarioSource.missing(String.valueOf(uri));
            List<String> keys = StoreFeatureSource.keys(uri);
            if (keys.isEmpty()) throw StoreScenarioSource.missing(String.valueOf(uri));
            String text = new String(bytes, StandardCharsets.UTF_8);
            String executable = StoreScenarioSource.executableText(StoreConfig.project(), keys.getFirst(), text);
            if (executable == null) return Optional.empty();
            byte[] body = executable.equals(text) ? bytes : executable.getBytes(StandardCharsets.UTF_8);
            return parseBytes(uri, body);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Parses rendered row text. The URI is not a project path, so the store aspect does not treat it as a missing row. */
    public static Optional<Feature> parseText(URI uri, String text) {
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        return parseBytes(uri, body);
    }

    private static Optional<Feature> parseBytes(URI uri, byte[] body) {
        try {
            return PARSER.parse(uri, new ByteArrayInputStream(body), UUID::randomUUID);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static String label(List<URI> paths) {
        if (paths == null || paths.isEmpty()) return "";
        return paths.getFirst().toString();
    }

    private static FeatureParser resolveParser() {
        FeatureParser best = null;
        ServiceLoader<FeatureParser> loaded = ServiceLoader.load(FeatureParser.class, RowFeatureSupplier.class.getClassLoader());
        for (FeatureParser candidate : loaded) {
            if (best == null || candidate.version().compareTo(best.version()) > 0) best = candidate;
        }
        if (best == null) throw new IllegalStateException("No Cucumber feature parser is available");
        return best;
    }
}
