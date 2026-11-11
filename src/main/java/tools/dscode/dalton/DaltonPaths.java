package tools.dscode.dalton;

import java.nio.file.Path;

/**
 * Dalton files live under the gitignored {@code .pickleball/dalton/} directory.
 * The database is a disposable working copy, never a committed artifact.
 */
public final class DaltonPaths {
    public static final String DIRECTORY = ".pickleball/dalton";
    public static final String DATABASE_FILE = "dalton.db";
    public static final String LOG_FILE = "dalton.log";

    private DaltonPaths() {
    }

    public static Path root(Path project) {
        return project.toAbsolutePath().normalize().resolve(".pickleball").resolve("dalton");
    }

    public static Path database(Path project) {
        return root(project).resolve(DATABASE_FILE);
    }

    public static Path log(Path project) {
        return root(project).resolve(LOG_FILE);
    }

    /** Project-relative display form, always with {@code /}. */
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
}
