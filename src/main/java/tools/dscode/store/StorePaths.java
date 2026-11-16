package tools.dscode.store;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Store files live under the gitignored {@code .pickleball/store/} directory.
 * The database is a disposable working copy, never a committed artifact.
 */
public final class StorePaths {
    public static final String DIRECTORY = ".pickleball/store";
    /** Unused by {@link #database(Path)}. The live store is {@link #store(Path)}. */
    public static final String LOG_FILE = "store.log";

    private StorePaths() {
    }

    public static Path root(Path project) {
        return project.toAbsolutePath().normalize().resolve(".pickleball").resolve("store");
    }

    /** Old SQLite file. Migrate reads it. The core does not create it. */
    public static Path database(Path project) {
        return LegacyNames.sqliteFile(project);
    }

    /** Embedded source database for main. */
    public static Path mainDatabase(Path project) {
        return root(project).resolve("main.db");
    }

    /** Project-level run history. Not referenced from a source database file. */
    public static Path runState(Path project) {
        return root(project).resolve("runstate.db");
    }

    /** Independent sandbox copy. */
    public static Path sandboxDatabase(Path project, String name) {
        return sandboxHome(project, name).resolve("source.db");
    }

    /** Live store file. Present once the embedded database has been created. */
    public static Path store(Path project) {
        return mainDatabase(project);
    }

    public static boolean ready(Path project) {
        if (project == null) return false;
        return Files.isRegularFile(mainDatabase(project));
    }

    public static Path log(Path project) {
        return root(project).resolve(LOG_FILE);
    }

    /** Per-sandbox home. {@code main} is {@code .pickleball/store/sandboxes/main}. */
    public static Path sandboxHome(Path project, String name) {
        String box = name == null || name.isBlank() ? "main" : name;
        return root(project).resolve("sandboxes").resolve(box);
    }

    public static Path worker(Path project, String name) {
        return sandboxHome(project, name).resolve("worker");
    }

    public static Path classes(Path project, String name) {
        return sandboxHome(project, name).resolve("classes");
    }

    public static Path glueSources(Path project, String name) {
        return sandboxHome(project, name).resolve("src");
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
            throw new IllegalArgumentException("Store path is blank");
        }
        String value = raw.trim().replace('\\', '/');
        while (value.startsWith("./")) value = value.substring(2);
        if (value.startsWith("/") || value.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("Store paths must be relative: " + raw);
        }
        for (String part : value.split("/")) {
            if ("..".equals(part)) {
                throw new IllegalArgumentException("Store paths must not contain ..: " + raw);
            }
        }
        return value;
    }
}
