package tools.dscode.studio.build;

import tools.dscode.studio.process.ProcessResult;

public record MavenRunResult(String mavenVersion, ProcessResult process) {
}
