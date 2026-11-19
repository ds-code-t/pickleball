package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** One warning string for empty consumer glue, on stderr and in the run report. */
public final class NoopNotice {
    public static final String MARK = "noop:true";
    public static final String WARNING =
            "WARNING: noop consumer glue (noop:true). The step reported SUCCESS without acting. Set pkb_noop_glue=fail to fail the step.";

    private NoopNotice() {
    }

    public static void warnIfPresent(String rendered, Appendable err) {
        if (rendered == null || err == null || !rendered.contains(MARK)) return;
        try {
            err.append(WARNING).append(System.lineSeparator());
        } catch (IOException ignored) {
            // The result line is already printed.
        }
    }

    /** Appends the warning to {@code .pickleball/runs/<runId>/run-report.txt} when the result is a noop. */
    public static void noteReport(Path project, String runId, String rendered) {
        if (rendered == null || !rendered.contains(MARK) || runId == null || runId.isBlank()) return;
        Path root = project == null ? Path.of("") : project;
        String safe = runId.replaceAll("[^A-Za-z0-9._-]", "_");
        Path file = root.resolve(".pickleball").resolve("runs").resolve(safe).resolve("run-report.txt");
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    WARNING + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ignored) {
            // The stderr warning still fired.
        }
    }
}
