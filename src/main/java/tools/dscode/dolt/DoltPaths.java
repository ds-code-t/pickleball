package tools.dscode.dolt;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dolt files live under the gitignored {@code .pickleball/dolt/} directory.
 * The database is a disposable working copy, never a committed artifact.
 */
public final class DoltPaths {
    public static final String DIRECTORY = ".pickleball/dolt";
    /** Unused by {@link #database(Path)}. The live store is {@link #store(Path)}. */
    public static final String LOG_FILE = "dolt.log";

    private DoltPaths() {
    }

    public static Path root(Path project) {
        return project.toAbsolutePath().normalize().resolve(".pickleball").resolve("dolt");
    }

    /** Old SQLite file. Migrate reads it. The core does not create it. */
    public static Path database(Path project) {
        return LegacyNames.sqliteFile(project);
    }

    /** Dolt data directory. Present once the server has initialized the repository. */
    public static Path store(Path project) {
        return project.toAbsolutePath().normalize().resolve(".pickleball").resolve("dolt").resolve("data");
    }

    public static boolean ready(Path project) {
        if (project == null) return false;
        return Files.isDirectory(store(project).resolve("pickleball").resolve(".dolt"));
    }

    public static Path log(Path project) {
        return root(project).resolve(LOG_FILE);
    }

    public static String display(Path project, Path file) {
        Path root = project.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        Path relative;
        try {
            relative = root.relativize(absolute);
        } catch (IllegalArgumentException differentRoot) {
            relative = absolute;
        }
        return relative.toString().replace('\\', '/');
    }

    /**
     * Store and compare paths with {@code /} only. Rejects absolute paths and
     * {@code ..}. A Windows {@code src\\features\\a.feature} becomes
     * {@code src/features/a.feature}.
     */
    public static String normalizeRelative(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Dolt path is blank");
        }
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        if (value.startsWith("/") || value.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("Dolt paths must be relative: " + raw);
        }
        for (String part : value.split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Dolt paths must not contain ..: " + raw);
            }
        }
        return value;
    }
}
