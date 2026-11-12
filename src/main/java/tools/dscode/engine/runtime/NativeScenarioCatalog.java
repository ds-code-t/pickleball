package tools.dscode.engine.runtime;

import tools.dscode.control.protocol.PickleballPaths;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.source.FileScenarioSource;
import tools.dscode.engine.source.ScenarioSource;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Features the native engine can RUN. The in-memory source is the scenario
 * universe. A component, service-call, or data path is loaded the same way
 * Cucumber resolves it (classpath, {@code wd-file:}, or a real directory).
 * When that path does not exist, the in-memory source is used so a run with
 * nothing on disk can still select by name.
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
        List<EngineFeature> resolved = resolve(path, catalog.project);
        if (!resolved.isEmpty()) return resolved;
        if (!hasScheme(path)) {
            List<EngineFeature> fromWorkingDirectory = loadFile(Path.of("").toAbsolutePath().resolve(path));
            if (!fromWorkingDirectory.isEmpty()) return fromWorkingDirectory;
            List<EngineFeature> fromProject = loadFile(catalog.project.resolve(path));
            if (!fromProject.isEmpty()) return fromProject;
        }
        if (usesRunningSource(path)) return catalog.running;
        return List.of();
    }

    private static boolean usesRunningSource(String path) {
        String text = path == null ? "" : path.trim().replace('\\', '/');
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text.equals("src/test/resources/calls")
                || text.equals("src/test/resources/component")
                || text.equals("data")
                || text.equalsIgnoreCase("classpath:data")
                || text.equalsIgnoreCase("file:data");
    }

    private static boolean hasScheme(String path) {
        String text = path.trim();
        return text.regionMatches(true, 0, "file:", 0, "file:".length())
                || text.regionMatches(true, 0, "classpath:", 0, "classpath:".length())
                || text.regionMatches(true, 0, "wd-file:", 0, "wd-file:".length());
    }

    private static List<EngineFeature> resolve(String path, Path project) {
        String text = path.trim();
        if (text.regionMatches(true, 0, "file:", 0, "file:".length())) {
            String rest = text.substring("file:".length());
            if (rest.startsWith("/") || (rest.length() > 2 && rest.charAt(1) == ':')) {
                try {
                    Path file = Path.of(rest);
                    if (Files.exists(file)) return new FileScenarioSource(file).load();
                } catch (Exception ignored) {
                    return List.of();
                }
                return List.of();
            }
        }
        PickleballPaths.Parsed parsed = PickleballPaths.parse(text);
        if (parsed.kind() == PickleballPaths.Kind.FILE_SYSTEM) {
            List<EngineFeature> fromProject = loadFile(PickleballPaths.toFile(parsed, project));
            if (!fromProject.isEmpty()) return fromProject;
            return loadFile(PickleballPaths.toFile(parsed, Path.of("").toAbsolutePath()));
        }
        return loadClasspath(parsed.classpathResource());
    }

    private static List<EngineFeature> loadFile(Path file) {
        if (file == null || !Files.exists(file)) return List.of();
        try {
            file = file.toRealPath();
        } catch (java.io.IOException failure) {
            return List.of();
        }
        return new FileScenarioSource(file).load();
    }

    private static List<EngineFeature> loadClasspath(String resource) {
        if (resource == null || resource.isBlank()) return List.of();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) loader = NativeScenarioCatalog.class.getClassLoader();
        List<EngineFeature> found = new ArrayList<>();
        try {
            Enumeration<URL> urls = loader.getResources(resource);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (!"file".equals(url.getProtocol())) continue;
                try {
                    found.addAll(loadFile(Path.of(url.toURI())));
                } catch (URISyntaxException ignored) {
                    // The next classpath root may still hold the features.
                }
            }
        } catch (Exception failure) {
            EngineLog.log(null, "run", "catalog", failure.toString());
        }
        return found;
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