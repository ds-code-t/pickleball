package tools.dscode.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The only product class that still spells the former name of this core.
 * A stale key, environment variable, properties file, or entity directory
 * stops the run. Nothing reads those names.
 */
public final class LegacyNames {
    private static final String OLD = "dalton";
    private static final String FRESH = "store";

    private LegacyNames() {
    }

    public static Path legacyRoot(Path project) {
        return project.toAbsolutePath().normalize().resolve(".pickleball").resolve(OLD);
    }

    public static Path sqliteFile(Path project) {
        return legacyRoot(project).resolve(OLD + ".db");
    }

    public static List<String> moveLines(Path project) {
        List<String> lines = new ArrayList<>();
        if (project != null && Files.isRegularFile(project.resolve(OLD + ".properties"))) {
            lines.add("git mv " + OLD + ".properties " + FRESH + ".properties");
        }
        if (project != null && Files.isDirectory(project.resolve(OLD + "-entities"))) {
            lines.add("git mv " + OLD + "-entities " + FRESH + "-entities");
        }
        return lines;
    }

    /** {@code error}/{@code fix} text, or null when no former name is set. */
    public static String problem(Path project) {
        List<String> lines = new ArrayList<>();
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key != null && key.toLowerCase().startsWith("pkb_" + OLD)) {
                String value = System.getProperty(key);
                if (value != null && !value.isBlank()) {
                    lines.add("error: " + key + " is no longer read");
                    lines.add("fix: use " + key.replace(OLD, FRESH).replace(OLD.toUpperCase(), FRESH));
                }
            }
        }
        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            String key = entry.getKey();
            if (key != null && key.toUpperCase().startsWith("PKB_" + OLD.toUpperCase())) {
                String value = entry.getValue();
                if (value != null && !value.isBlank()) {
                    lines.add("error: " + key + " is no longer read");
                    lines.add("fix: use " + key.replace(OLD.toUpperCase(), FRESH.toUpperCase()));
                }
            }
        }
        if (project != null && Files.isRegularFile(project.resolve(OLD + ".properties"))) {
            lines.add("error: " + OLD + ".properties is no longer read");
            lines.add("fix: use " + FRESH + ".properties");
        }
        if (project != null && Files.isDirectory(project.resolve(OLD + "-entities"))
                && !Files.isDirectory(project.resolve(FRESH + "-entities"))) {
            lines.add("error: " + OLD + "-entities is no longer read");
            lines.add("fix: use " + FRESH + "-entities");
        }
        if (lines.isEmpty()) return null;
        return String.join("\n", lines);
    }

    public static void refuse(Path project) {
        String problem = problem(project);
        if (problem != null) throw new IllegalStateException(problem);
    }
}
