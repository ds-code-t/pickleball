package tools.dscode.studio.workspace;

public record WorkspaceEntry(
        String path,
        boolean directory,
        long size
) {
}
