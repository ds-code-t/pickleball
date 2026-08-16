package tools.dscode.studio.gradle;

public record GradleTaskInfo(
        String path,
        String name,
        String group,
        String description,
        boolean publicTask
) {
}
