package tools.dscode.studio.mcp;

public record StudioWorkspaceStatus(
        String root,
        String name,
        boolean mavenProject,
        boolean gradleProject,
        boolean gitRepository
) {
}
