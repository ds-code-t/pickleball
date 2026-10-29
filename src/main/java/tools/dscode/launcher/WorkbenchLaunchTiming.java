package tools.dscode.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Cheap Workbench launch stages. One line on stderr and, when a run folder
 * exists, the same line in {@code launch-timing.txt}. A normal {@code mvn test}
 * does not call this. Elapsed time is since this class loaded in that JVM.
 * The launcher JVM and the test JVM each have their own clock.
 */
public final class WorkbenchLaunchTiming {
    public static final String FILE_NAME = "launch-timing.txt";
    private static final long STARTED = System.nanoTime();

    private WorkbenchLaunchTiming() {
    }

    public static void note(Path runFolder, String stage, String detail) {
        long elapsed = (System.nanoTime() - STARTED) / 1_000_000L;
        record(runFolder, "launcher", stage, elapsed, detail);
    }

    public static void record(String jvm, String stage, long elapsedMs, String detail) {
        record(runFolderFromProperties(), jvm, stage, elapsedMs, detail);
    }

    public static void record(Path runFolder, String jvm, String stage, long elapsedMs, String detail) {
        String line = format(jvm, stage, elapsedMs, detail);
        if (echoStderr()) System.err.println(line);
        if (runFolder == null) return;
        Path file = runFolder.resolve(FILE_NAME);
        try {
            Files.createDirectories(runFolder);
            Files.writeString(
                    file,
                    line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // Timing must not fail the launch.
        }
    }

    public static String format(String jvm, String stage, long elapsedMs, String detail) {
        StringBuilder line = new StringBuilder();
        line.append("workbench launch jvm=").append(jvm == null ? "" : jvm)
                .append(" stage=").append(stage == null ? "" : stage)
                .append(" elapsedMs=").append(elapsedMs);
        if (detail != null && !detail.isBlank()) {
            line.append(' ').append(detail.trim());
        }
        return line.toString();
    }

    /**
     * Stderr is quiet unless an agent asks. The file is always written.
     * {@code -Dpkb_workbench_timing=true} wins over the environment variable.
     */
    static boolean echoStderr() {
        String property = System.getProperty("pkb_workbench_timing");
        if (property != null) return truthy(property);
        return truthy(System.getenv("PKB_WORKBENCH_TIMING"));
    }

    private static boolean truthy(String value) {
        if (value == null) return false;
        String token = value.trim();
        return "1".equals(token) || "true".equalsIgnoreCase(token) || "yes".equalsIgnoreCase(token);
    }

    private static Path runFolderFromProperties() {
        String runId = System.getProperty("pkb_run_id");
        if (runId == null || runId.isBlank()) return null;
        String cleaned = runId.trim();
        if (cleaned.contains("/") || cleaned.contains("\\") || cleaned.contains("..")) return null;
        String dir = System.getProperty("user.dir");
        if (dir == null || dir.isBlank()) return null;
        return Path.of(dir, ".pickleball", "runs", cleaned);
    }
}