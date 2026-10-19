package tools.dscode.common.coordination;

import tools.dscode.common.resources.ResourceLocations;
import tools.dscode.control.protocol.PickleballPaths;
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
 * The worker calls this after {@code pkb_configpath} is resolved and then loads this copy.
 * The copy is a faithful copy of the project files: real values and the original structure,
 * including nested YAML. It is not redacted. Redaction for logs and reports is a separate
 * path and does not rewrite the files the worker parses.
 * The copy is created once. A later start of the same run keeps edits.
 * A destination that contains only the bundled {@code CHROME_HEADLESS.yaml} is not finished.
 * A normal test never calls this. The run directory is not written back to {@code pkb_configpath}.
 */
public final class RunConfigCopy {
    public static final String DIRECTORY_NAME = RunConfigs.DIRECTORY;
    public static final String BUNDLED_CHROME_HEADLESS =
            "META-INF/pickleball/configs/CHROME_HEADLESS.yaml";

    private RunConfigCopy() {
    }

    public static void copyIfAbsent(Path project, Path destination, String configuredPath) throws IOException {
        copyIfAbsent(project, destination, configuredPath, ResourceLocations.workingDirectory());
    }

    static void copyIfAbsent(
            Path project,
            Path destination,
            String configuredPath,
            Path workingDirectory
    ) throws IOException {
        if (project == null || destination == null) return;
        if (containsFile(destination)) return;
        Files.createDirectories(destination);
        String selected = selectSource(project, configuredPath);
        Path source = resolveRoot(project, selected, workingDirectory);
        if (source != null && Files.isDirectory(source)) {
            copyTree(source, destination);
        } else {
            copyClasspath(selected, destination);
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
        return resolveRoot(project, configuredPath, ResourceLocations.workingDirectory());
    }

    static Path resolveRoot(Path project, String configuredPath, Path workingDirectory) {
        return ResourceLocations.existingDirectory(selectSource(project, configuredPath), workingDirectory);
    }

    static ResourceLocations.Location locate(Path project, String configuredPath, Path workingDirectory) {
        return ResourceLocations.locate(selectSource(project, configuredPath), workingDirectory);
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
        PickleballPaths.Parsed parsed = PickleballPaths.parse(configuredPath);
        if (parsed.kind() != PickleballPaths.Kind.CLASSPATH) {
            return;
        }
        String resource = parsed.classpathResource() == null ? "" : parsed.classpathResource();
        Path fromClasspath = ResourceLocations.classpathDirectory(resource);
        if (fromClasspath != null) {
            copyTree(fromClasspath, destination);
            return;
        }
        copyJarResource(resource, destination);
    }

    private static void copyJarResource(String resource, Path destination) throws IOException {
        if (resource.isBlank()) return;
        URL url = ResourceLocations.loader().getResource(resource);
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
        try (InputStream in = ResourceLocations.loader().getResourceAsStream(BUNDLED_CHROME_HEADLESS)) {
            if (in == null) return;
            Files.write(destination.resolve("CHROME_HEADLESS.yaml"), in.readAllBytes());
        }
    }

    private static boolean containsFile(Path root) throws IOException {
        if (!Files.isDirectory(root)) return false;
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).anyMatch(path -> !bundledHeadlessFile(path));
        }
    }

    private static boolean bundledHeadlessFile(Path path) {
        Path name = path.getFileName();
        return name != null && "CHROME_HEADLESS.yaml".equalsIgnoreCase(name.toString());
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

    private static URI jarUri(URL url) {
        String text = url.toString();
        int bang = text.indexOf("!/");
        return URI.create(bang >= 0 ? text.substring(0, bang) : text);
    }

    /** Visible so tests can name the run folder the same way the GUI does. */
    public static Path runConfigDirectory(Path runData) {
        return runData.resolve(RunConfigs.DIRECTORY);
    }
}
