package tools.dscode.studio.build;

import tools.dscode.studio.process.ManagedProcessSummary;

public record ManagedMavenRunResult(
        String mavenVersion,
        ManagedProcessSummary process
) {
}
