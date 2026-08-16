package tools.dscode.studio.process;

import java.util.List;

public record ManagedProcessSummary(
        String id,
        List<String> command,
        String workingDirectory,
        ProcessState state,
        Integer exitCode,
        String startedAt,
        String completedAt,
        int timeoutSeconds
) {
    public ManagedProcessSummary {
        command = List.copyOf(command);
    }
}
