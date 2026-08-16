package tools.dscode.studio.build;

import tools.dscode.studio.process.ManagedProcessSummary;

public record ManagedGradleRunResult(
        String wrapper,
        ManagedProcessSummary process
) {
}
