package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Read-only view of one consumer run under {@code .pickleball/runs/<run-id>/}.
 * The Workbench window and the launcher session commands both use this class.
 * It does not copy the run and it does not start a test.
 */
public final class RunView {
    public static final String RUNS_DIRECTORY = "runs";
    public static final String RECORD_FILE = "record.json";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");

    private RunView() {
    }

    public record Loaded(
            String runId,
            Path directory,
            Path recordFile,
            String recordText,
            List<Path> logs,
            List<Path> reports,
            List<Path> config
    ) {
    }

    public static Path runsRoot(Path project) {
        return PickleballLocalLayout.root(project).resolve(RUNS_DIRECTORY);
    }

    public static Path runDirectory(Path project, String runId) {
        return runsRoot(project).resolve(requireSafeId(runId));
    }

    public static List<String> runIds(Path project) {
        Path root = runsRoot(project);
        if (!Files.isDirectory(root)) return List.of();
        List<String> ids = new ArrayList<>();
        try (Stream<Path> children = Files.list(root)) {
            children.filter(Files::isDirectory).forEach(dir -> {
                String name = dir.getFileName().toString();
                if (!SAFE_ID.matcher(name).matches() || name.contains("..")) return;
                if (Files.isRegularFile(dir.resolve(RECORD_FILE))) ids.add(name);
            });
        } catch (IOException ignored) {
            return List.of();
        }
        ids.sort(Comparator.naturalOrder());
        return List.copyOf(ids);
    }

    public static Loaded load(Path project, String runId) {
        String safe = requireSafeId(runId);
        Path directory = runsRoot(project).resolve(safe);
        Path recordFile = directory.resolve(RECORD_FILE);
        if (!Files.isRegularFile(recordFile)) {
            throw new IllegalArgumentException("No run record for " + safe + " at " + recordFile);
        }
        String recordText;
        try {
            recordText = Files.readString(recordFile, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read run record " + recordFile + ": " + failure.getMessage(), failure);
        }
        List<Path> logs = new ArrayList<>();
        logs.addAll(logFiles(directory));
        logs.addAll(regularFiles(directory.resolve("session")).stream()
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .toList());
        logs.sort(Comparator.naturalOrder());
        return new Loaded(
                safe,
                directory.toAbsolutePath().normalize(),
                recordFile.toAbsolutePath().normalize(),
                recordText,
                List.copyOf(logs),
                reports(directory),
                regularFiles(directory.resolve("config"))
        );
    }

    public static String requireSafeId(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("run id is required.");
        }
        String value = runId.trim();
        if (!SAFE_ID.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(
                    "run id must be a single folder name (letters, digits, '.', '_' or '-'): " + runId
            );
        }
        return value;
    }

    private static List<Path> logFiles(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private static List<Path> reports(Path directory) {
        List<Path> files = new ArrayList<>();
        files.addAll(regularFiles(directory.resolve("reports")));
        files.addAll(regularFiles(directory.resolve("diagnostic")));
        files.sort(Comparator.naturalOrder());
        return List.copyOf(files);
    }

    private static List<Path> regularFiles(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> walk = Files.walk(directory)) {
            return walk.filter(Files::isRegularFile)
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        } catch (IOException ignored) {
            return List.of();
        }
    }
}
