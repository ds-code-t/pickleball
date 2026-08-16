package tools.dscode.studio.gradle;

public record GradleSourceDirectoryInfo(
        String path,
        String kind,
        boolean generated
) {
}
