package tools.dscode.studio.collaboration;

public record StudioEditorState(
        String clientSessionId,
        String path,
        boolean dirty,
        String baseSha256,
        String updatedAt
) {
}
