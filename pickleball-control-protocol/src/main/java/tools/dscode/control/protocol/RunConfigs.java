package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * The config files of one run, under {@code .pickleball/runs/<run-id>/config}.
 * Agents and the Workbench Config tab both read and write this directory.
 * Nothing here writes the consumer project's own configs.
 */
public final class RunConfigs {
    public static final String DIRECTORY = "config";

    private RunConfigs() {
    }

    public static Path directory(Path project, String runId) {
        return RunView.runDirectory(project, runId).resolve(DIRECTORY);
    }

    public static List<String> files(Path project, String runId) {
        Path root = directory(project, runId);
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    public static String read(Path project, String runId, String relative) {
        Path file = resolve(directory(project, runId), relative);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No config file " + relative + " in this run.");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + file + ": " + failure.getMessage(), failure);
        }
    }

    public static void write(Path project, String runId, String relative, String text) {
        Path root = directory(project, runId);
        Path file = resolve(root, relative);
        try {
            Files.createDirectories(file.getParent() == null ? root : file.getParent());
            Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not write " + file + ": " + failure.getMessage(), failure);
        }
    }

    static Path resolve(Path root, String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("A config file name is required.");
        }
        String cleaned = relative.trim().replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("..")) {
            throw new IllegalArgumentException("A config path must stay inside this run's config copy: " + relative);
        }
        Path rootAbs = root.toAbsolutePath().normalize();
        Path target = rootAbs.resolve(cleaned).normalize();
        if (!target.startsWith(rootAbs)) {
            throw new IllegalArgumentException("A config path must stay inside this run's config copy: " + relative);
        }
        return target;
    }
}
