package tools.dscode.common.coordination;

import tools.dscode.control.protocol.RunConfigs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Copies a consumer project's configs into one run directory.
 * The copy is created once. A later start of the same run keeps edits.
 * A normal test never calls this.
 */
public final class RunConfigCopy {
    public static final String DIRECTORY_NAME = RunConfigs.DIRECTORY;
    public static final String BUNDLED_CHROME_HEADLESS =
            "META-INF/pickleball/configs/CHROME_HEADLESS.yaml";

    private RunConfigCopy() {
    }

    public static void copyIfAbsent(Path project, Path destination, String configuredPath) throws IOException {
        if (project == null || destination == null) return;
        if (containsFile(destination)) return;
        Files.createDirectories(destination);
        Path source = resolveRoot(project, configuredPath);
        if (source != null && Files.isDirectory(source)) {
            copyTree(source, destination);
        } else {
            copyClasspath(selectSource(project, configuredPath), destination);
        }
        ensureBundledHeadless(destination);
    }

    static String selectSource(Path project, String configuredPath) {
        if (configuredPath == null) {
            String discovered = discover(project);
            return discovered == null || discovered.isBlank() ? "configs" : discovered.trim();
        }
        return configuredPath.isBlank() ? "configs" : configuredPath.trim();
    }

    static Path resolveRoot(Path project, String configuredPath) {
        String selected = selectSource(project, configuredPath);
        if (selected.regionMatches(true, 0, "classpath:", 0, "classpath:".length())) {
            return classpathDirectory(selected.substring("classpath:".length()));
        }
        if (selected.regionMatches(true, 0, "file:", 0, "file:".length())) {
            Path file = fileUri(selected);
            return file != null && Files.isDirectory(file) ? file.toAbsolutePath().normalize() : null;
        }
        Path direct = Path.of(selected);
        if (direct.isAbsolute() && Files.isDirectory(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        if (project != null) {
            Path relative = project.resolve(selected).normalize();
            if (Files.isDirectory(relative)) return relative;
            Path testResources = project.resolve("src/test/resources").resolve(stripSlashes(selected)).normalize();
            if (Files.isDirectory(testResources)) return testResources;
            Path mainResources = project.resolve("src/main/resources").resolve(stripSlashes(selected)).normalize();
            if (Files.isDirectory(mainResources)) return mainResources;
        }
        return classpathDirectory(selected);
    }

    private static String discover(Path project) {
        String property = System.getProperty("pkb_configpath");
        if (property != null && !property.isBlank()) return property.trim();
        if (project == null) return null;
        String found = null;
        for (Path file : List.of(
                project.resolve("pickleball.properties"),
                project.resolve("pickleball_local.properties"),
                project.resolve("src/test/resources/pickleball.properties"),
                project.resolve("src/test/resources/pickleball_local.properties")
        )) {
            String value = propertyValue(file, "pkb_configpath");
            if (value != null && !value.isBlank()) found = value.trim();
        }
        return found;
    }

    private static String propertyValue(Path file, String key) {
        if (!Files.isRegularFile(file)) return null;
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
                int split = indexOfSeparator(trimmed);
                if (split <= 0) continue;
                String name = trimmed.substring(0, split).trim();
                if (!name.equalsIgnoreCase(key)) continue;
                return trimmed.substring(split + 1).trim();
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    private static int indexOfSeparator(String line) {
        int equals = line.indexOf('=');
        int colon = line.indexOf(':');
        if (equals < 0) return colon;
        if (colon < 0) return equals;
        return Math.min(equals, colon);
    }

    private static void copyClasspath(String configuredPath, Path destination) throws IOException {
        String resource = configuredPath;
        if (resource.regionMatches(true, 0, "classpath:", 0, "classpath:".length())) {
            resource = resource.substring("classpath:".length());
        }
        if (resource.regionMatches(true, 0, "file:", 0, "file:".length())) return;
        Path fromClasspath = classpathDirectory(resource);
        if (fromClasspath != null) {
            copyTree(fromClasspath, destination);
            return;
        }
        copyJarResource(stripSlashes(resource), destination);
    }

    private static Path classpathDirectory(String resource) {
        String name = stripSlashes(resource);
        if (name.isBlank()) return null;
        ClassLoader loader = loader();
        URL url;
        try {
            url = loader.getResource(name);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) return null;
        try {
            Path path = Path.of(url.toURI());
            return Files.isDirectory(path) ? path.toAbsolutePath().normalize() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void copyJarResource(String resource, Path destination) throws IOException {
        if (resource.isBlank()) return;
        URL url = loader().getResource(resource);
        if (url == null || !"jar".equalsIgnoreCase(url.getProtocol())) return;
        try (FileSystem fs = FileSystems.newFileSystem(jarUri(url), Map.of())) {
            Path root = fs.getPath(resource);
            if (Files.isDirectory(root)) copyTree(root, destination);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception ignored) {
            // A missing packaged tree is not a project file to invent.
        }
    }

    private static void ensureBundledHeadless(Path destination) throws IOException {
        if (hasBaseName(destination, "CHROME_HEADLESS")) return;
        try (InputStream in = loader().getResourceAsStream(BUNDLED_CHROME_HEADLESS)) {
            if (in == null) return;
            Files.write(destination.resolve("CHROME_HEADLESS.yaml"), in.readAllBytes());
        }
    }

    private static boolean containsFile(Path root) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(Files::isRegularFile);
        }
    }

    private static boolean hasBaseName(Path root, String base) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).anyMatch(path -> {
                String name = path.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String stem = dot > 0 ? name.substring(0, dot) : name;
                return stem.equalsIgnoreCase(base);
            });
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Path sourceRoot = source.toAbsolutePath().normalize();
        Path destRoot = destination.toAbsolutePath().normalize();
        if (sourceRoot.equals(destRoot) || destRoot.startsWith(sourceRoot) || sourceRoot.startsWith(destRoot)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path path : walk.toList()) {
                Path relative = sourceRoot.relativize(path);
                if (relative.toString().isEmpty()) continue;
                Path target = destRoot.resolve(relative.toString()).normalize();
                if (!target.startsWith(destRoot)) continue;
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(path)) {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static Path fileUri(String value) {
        try {
            return Path.of(URI.create(value));
        } catch (Exception ignored) {
            return Path.of(value.substring("file:".length()));
        }
    }

    private static URI jarUri(URL url) {
        String text = url.toString();
        int bang = text.indexOf("!/");
        return URI.create(bang >= 0 ? text.substring(0, bang) : text);
    }

    private static String stripSlashes(String value) {
        String text = value == null ? "" : value.trim().replace('\\', '/');
        while (text.startsWith("/")) text = text.substring(1);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }

    private static ClassLoader loader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? RunConfigCopy.class.getClassLoader() : loader;
    }

    /** Visible so tests can name the run folder the same way the GUI does. */
    public static Path runConfigDirectory(Path runData) {
        return runData.resolve(RunConfigs.DIRECTORY);
    }
}
