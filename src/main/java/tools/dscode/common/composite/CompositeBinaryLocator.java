package tools.dscode.common.composite;

import tools.dscode.control.protocol.DirectControlProvider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Finds the {@code composite} binary. Order: {@code pkb_composite_bin},
 * {@code COMPOSITE_BIN}, {@code COMPOSITE_HOME} or a sibling {@code dom-composite}
 * checkout ({@code target/debug} then {@code target/release}), a classpath
 * native for this OS/arch, then a local Maven classifier jar
 * {@code dev.domcomposite:composite-native:2.4.0:<classifier>}.
 * {@code pkb_composite=on} also accepts {@code composite} on {@code PATH}.
 */
public final class CompositeBinaryLocator {
    public static final String VERSION = "2.4.0";
    public static final String BIN_KEY = "pkb_composite_bin";
    public static final String ENGINE_KEY = "pkb_composite_engine";

    private static boolean rustRequested(CompositeRuntime.Sources sources) {
        String engine = first(
                text(sources.properties() == null ? null : sources.properties().get(ENGINE_KEY)),
                text(sources.properties() == null ? null : sources.properties().get("pkb_runvars." + ENGINE_KEY)),
                text(sources.env() == null ? null : sources.env().get("PKB_COMPOSITE_ENGINE")),
                text(sources.env() == null ? null : sources.env().get(ENGINE_KEY))
        );
        return engine != null && "rust".equalsIgnoreCase(engine);
    }
    public static final String GROUP = "dev.domcomposite";
    public static final String NATIVE_ARTIFACT = "composite-native";
    public static final List<String> CLASSIFIERS = List.of(
            "linux-x64", "linux-aarch64", "windows-x64", "macos-aarch64", "macos-x64"
    );

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
        String named = first(
                text(properties.get(BIN_KEY)),
                text(properties.get("pkb_runvars." + BIN_KEY)),
                text(environment.get(DirectControlProvider.BIN_ENV)),
                text(environment.get(BIN_KEY))
        );
        Path explicit = executable(named, resolved.project());
        if (explicit != null) {
            CompositeLog.info("location", "binary " + explicit);
            return Optional.of(explicit);
        }
        if (!rustRequested(resolved)) {
            Optional<Path> javaEngine = JavaEngineLocator.script(resolved.loader());
            if (javaEngine.isPresent()) {
                CompositeLog.info("location", "java " + javaEngine.get());
                return javaEngine;
            }
        }
        Optional<Path> discovered = DirectControlProvider.resolveRustBinary(
                resolved.project(),
                environment
        );
        if (discovered.isPresent()) {
            CompositeLog.info("location", "binary " + discovered.get());
            return discovered;
        }
        String classifier = classifier();
        Optional<Path> extracted = extractResource(resolved.loader(), classifier);
        if (extracted.isPresent()) {
            CompositeLog.info("location", "extracted " + classifier + " " + extracted.get());
            return extracted;
        }
        Optional<Path> jar = extractMavenClassifier(classifier, resolved.home());
        if (jar.isPresent()) {
            CompositeLog.info("location", "classifier " + classifier + " " + jar.get());
            return jar;
        }
        if (CompositeMode.parse(CompositeMode.read(CompositeMode.KEY, resolved)).required()) {
            Path path = pathBinary(environment);
            if (path != null) {
                CompositeLog.info("location", "PATH " + path);
                return Optional.of(path);
            }
        }
        CompositeLog.debug("location", "no composite binary for " + classifier);
        return Optional.empty();
    }

    public static String classifier() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osName = os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux";
        String archName = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x64";
        return osName + "-" + archName;
    }

    public static String binaryName(String classifier) {
        return classifier != null && classifier.startsWith("windows") ? "composite.exe" : "composite";
    }

    static Optional<Path> extractResource(ClassLoader loader, String classifier) {
        if (loader == null || classifier == null) return Optional.empty();
        String name = binaryName(classifier);
        String resource = "dev/domcomposite/natives/" + classifier + "/" + name;
        URL url = loader.getResource(resource);
        if (url == null) return Optional.empty();
        try (InputStream in = url.openStream()) {
            return Optional.of(writeExecutable(classifier, name, in));
        } catch (IOException failure) {
            CompositeLog.debug("location", "resource extract failed: " + failure.getMessage());
            return Optional.empty();
        }
    }

    static Optional<Path> extractMavenClassifier(String classifier, Path home) {
        if (home == null || classifier == null) return Optional.empty();
        Path jar = home.resolve(".m2").resolve("repository").resolve("dev").resolve("domcomposite")
                .resolve(NATIVE_ARTIFACT).resolve(VERSION)
                .resolve(NATIVE_ARTIFACT + "-" + VERSION + "-" + classifier + ".jar");
        if (!Files.isRegularFile(jar)) return Optional.empty();
        String name = binaryName(classifier);
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(name);
            if (entry == null) entry = archive.getJarEntry("natives/" + classifier + "/" + name);
            if (entry == null) return Optional.empty();
            try (InputStream in = archive.getInputStream(entry)) {
                return Optional.of(writeExecutable(classifier, name, in));
            }
        } catch (IOException failure) {
            CompositeLog.debug("location", "classifier extract failed: " + failure.getMessage());
            return Optional.empty();
        }
    }

    /** Test hook. Extracts {@code name} from a jar the caller built. */
    public static Optional<Path> extractJarForTests(Path jar, String classifier) {
        if (jar == null || !Files.isRegularFile(jar)) return Optional.empty();
        String name = binaryName(classifier);
        try (JarFile archive = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (!entry.getName().endsWith(name)) continue;
                try (InputStream in = archive.getInputStream(entry)) {
                    return Optional.of(writeExecutable(classifier, name, in));
                }
            }
        } catch (IOException failure) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Path writeExecutable(String classifier, String name, InputStream in) throws IOException {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "pickleball-composite-bin", VERSION, classifier);
        Files.createDirectories(dir);
        Path dest = dir.resolve(name);
        Path tmp = dir.resolve(name + ".part");
        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        if (!dest.toFile().setExecutable(true)) {
            CompositeLog.debug("location", "could not mark executable " + dest);
        }
        return dest.toAbsolutePath().normalize();
    }

    private static Path pathBinary(Map<String, String> env) {
        String path = env.get("PATH");
        if (path == null || path.isBlank()) return null;
        String name = binaryName(classifier());
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path candidate = executable(Path.of(entry).resolve(name).toString(), null);
            if (candidate != null) return candidate;
        }
        return null;
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

    private static String first(String... values) {
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String text(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static List<Path> searchHomesForTests(Path project, Map<String, String> env) {
        List<Path> homes = new ArrayList<>();
        Optional<Path> located = locate(project, env, ClassLoader.getSystemClassLoader());
        located.ifPresent(homes::add);
        return homes;
    }
}
