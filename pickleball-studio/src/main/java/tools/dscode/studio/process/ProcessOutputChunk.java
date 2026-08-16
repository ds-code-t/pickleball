package tools.dscode.studio.process;

public record ProcessOutputChunk(
        String id,
        ProcessState state,
        long stdoutOffset,
        String stdout,
        long nextStdoutOffset,
        boolean stdoutGap,
        boolean stdoutTruncated,
        long stderrOffset,
        String stderr,
        long nextStderrOffset,
        boolean stderrGap,
        boolean stderrTruncated
) {
}
