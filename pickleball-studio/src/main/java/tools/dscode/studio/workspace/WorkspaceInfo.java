package tools.dscode.studio.workspace;

import java.nio.file.Path;

public record WorkspaceInfo(
        Path root,
        String name,
        boolean mavenProject,
        boolean gradleProject,
        boolean gitRepository
) {
}
