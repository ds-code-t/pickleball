package tools.dscode.studio.build;

import tools.dscode.studio.process.ProcessResult;

public record GradleRunResult(
        String wrapper,
        ProcessResult process
) {
}
