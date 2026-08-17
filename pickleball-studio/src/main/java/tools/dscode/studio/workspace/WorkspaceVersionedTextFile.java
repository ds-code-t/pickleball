package tools.dscode.studio.workspace;

public record WorkspaceVersionedTextFile(
        String path,
        String content,
        String sha256
) {
}
