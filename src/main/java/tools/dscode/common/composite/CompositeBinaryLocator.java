package tools.dscode.common.composite;

import tools.dscode.control.protocol.DirectControlProvider;
import tools.dscode.control.protocol.JavaEngineLauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the Java control engine. Order: an explicit path (property
 * {@code pkb_composite_bin}, then {@code pkb_runvars.pkb_composite_bin}, then
 * env {@code COMPOSITE_BIN}, then env {@code pkb_composite_bin}; the first
 * non-blank spec only), then {@link JavaEngineLauncher#checkEngineKey}, then
 * the in-repo Java engine launcher. An explicit executable is returned before
 * the engine key is checked, so that path still wins.
 */
public final class CompositeBinaryLocator {
    public static final String BIN_KEY = "pkb_composite_bin";
    public static final String ENGINE_KEY = "pkb_composite_engine";

    private CompositeBinaryLocator() {
    }

    public static Optional<Path> locate() {
        return locate(CompositeRuntime.sources());
    }

    public static Optional<Path> locate(Path project, Map<String, String> env, ClassLoader loader) {
        Map<String, String> properties = new LinkedHashMap<>();
        copyProperty(properties, BIN_KEY);
        copyProperty(properties, "pkb_runvars." + BIN_KEY);
        copyProperty(properties, CompositeMode.KEY);
        copyProperty(properties, "pkb_runvars." + CompositeMode.KEY);
        copyProperty(properties, ENGINE_KEY);
        copyProperty(properties, "pkb_runvars." + ENGINE_KEY);
        String home = System.getProperty("user.home", "");
        return locate(new CompositeRuntime.Sources(
                project == null ? Path.of("").toAbsolutePath() : project,
                env,
                loader,
                home.isBlank() ? null : Path.of(home),
                properties,
                true
        ));
    }

    public static Optional<Path> locate(CompositeRuntime.Sources sources) {
        CompositeRuntime.Sources resolved = sources == null ? CompositeRuntime.sources() : sources;
        Map<String, String> environment = resolved.env() == null ? Map.of() : resolved.env();
        Map<String, String> properties = resolved.properties() == null ? Map.of() : resolved.properties();
        List<DirectControlProvider.ExplicitEngineCandidate> candidates =
                DirectControlProvider.explicitEngineCandidates(properties, environment);
        if (!candidates.isEmpty()) {
            DirectControlProvider.ExplicitEngineCandidate candidate = candidates.get(0);
            Path base = candidate.compositeBin() ? null : resolved.project();
            Path explicit = executable(candidate.spec(), base);
            if (explicit != null) {
                CompositeLog.info("location", "binary " + explicit);
                if (!javaLauncherScript(explicit)) {
                    CompositeLog.overrideOnce(candidate.sourceKey(), explicit.toString());
                }
                return Optional.of(explicit);
            }
        }
        JavaEngineLauncher.checkEngineKey(properties, environment);
        Optional<Path> javaEngine = JavaEngineLocator.script(resolved.loader());
        if (javaEngine.isPresent()) {
            CompositeLog.info("location", "java " + javaEngine.get());
            return javaEngine;
        }
        CompositeLog.debug("location", "no DOM engine");
        return Optional.empty();
    }

    private static boolean javaLauncherScript(Path path) {
        try {
            String text = Files.readString(path);
            return text.contains("dev.domengine.cli.DomEngineMain");
        } catch (IOException failure) {
            return false;
        }
    }

    private static Path executable(String value, Path project) {
        if (value == null) return null;
        Path path = Path.of(value);
        if (!path.isAbsolute() && project != null) path = project.resolve(path);
        path = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isExecutable(path)) return null;
        return path;
    }

    private static void copyProperty(Map<String, String> properties, String key) {
        String value = System.getProperty(key);
        if (value != null) properties.put(key, value);
    }

    static List<Path> searchHomesForTests(Path project, Map<String, String> env) {
        List<Path> homes = new ArrayList<>();
        Optional<Path> located = locate(project, env, ClassLoader.getSystemClassLoader());
        located.ifPresent(homes::add);
        return homes;
    }
}
