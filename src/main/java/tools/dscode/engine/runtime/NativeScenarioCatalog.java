package tools.dscode.engine.runtime;

import tools.dscode.engine.EngineLog;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.engine.source.ScenarioSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Features the native engine can RUN. The in-memory source is the scenario
 * universe. A component or service-call directory is used when it exists;
 * otherwise the same in-memory source is used so a run with nothing on disk
 * can still select by name.
 */
public final class NativeScenarioCatalog {
    private static final ThreadLocal<Catalog> CURRENT = new ThreadLocal<>();

    private NativeScenarioCatalog() {
    }

    public static void bind(Path project, List<EngineFeature> running) {
        CURRENT.set(new Catalog(
                project == null ? Path.of("").toAbsolutePath().normalize() : project,
                running == null ? List.of() : List.copyOf(running)
        ));
        EngineLog.log(project, "run", "catalog", "features=" + (running == null ? 0 : running.size()));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static boolean active() {
        return CURRENT.get() != null;
    }

    public static List<EngineFeature> features(String path) {
        Catalog catalog = CURRENT.get();
        if (catalog == null) return List.of();
        if (path == null || path.isBlank()) return catalog.running;
        Path file = Path.of(path);
        if (!file.isAbsolute()) file = catalog.project.resolve(file).normalize();
        if (Files.exists(file)) return new FileScenarioSource(file).load();
        return catalog.running;
    }

    public static ScenarioSource source(String path) {
        List<EngineFeature> features = features(path);
        return new ScenarioSource() {
            @Override
            public String id() {
                return path == null || path.isBlank() ? "memory" : path;
            }

            @Override
            public List<EngineFeature> load() {
                return features;
            }
        };
    }

    private record Catalog(Path project, List<EngineFeature> running) {
    }
}
