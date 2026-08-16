package tools.dscode.studio.gradle;

import java.util.List;

public record GradleWorkspaceModel(
        String gradleVersion,
        String javaHome,
        String gradleUserHome,
        String rootProjectPath,
        List<GradleProjectInfo> projects
) {
    public GradleWorkspaceModel {
        projects = List.copyOf(projects);
    }
}
