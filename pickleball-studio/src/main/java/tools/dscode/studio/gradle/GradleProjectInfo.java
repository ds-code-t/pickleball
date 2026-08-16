package tools.dscode.studio.gradle;

import java.util.List;

public record GradleProjectInfo(
        String path,
        String name,
        String description,
        String projectDirectory,
        String buildDirectory,
        String buildScript,
        int taskCount,
        List<GradleSourceDirectoryInfo> sourceDirectories
) {
    public GradleProjectInfo {
        sourceDirectories = List.copyOf(sourceDirectories);
    }
}
