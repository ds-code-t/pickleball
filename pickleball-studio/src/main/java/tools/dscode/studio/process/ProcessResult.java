package tools.dscode.studio.process;

import java.util.List;

public record ProcessResult(
        List<String> command,
        String workingDirectory,
        int exitCode,
        boolean timedOut,
        long durationMillis,
        String stdout,
        String stderr,
        boolean stdoutTruncated,
        boolean stderrTruncated
) {
    public ProcessResult {
        command = List.copyOf(command);
    }
}
