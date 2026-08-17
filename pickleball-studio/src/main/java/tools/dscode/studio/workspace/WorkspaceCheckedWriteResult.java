package tools.dscode.studio.workspace;

public record WorkspaceCheckedWriteResult(
        String path,
        boolean written,
        boolean blockedByDirtyEditor,
        String expectedSha256,
        String actualSha256,
        String newSha256,
        int characters,
        String message
) {
}
