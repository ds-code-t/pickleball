package tools.dscode.studio.workspace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class WorkspaceConcurrencyService {
    private final WorkspaceFileService files;

    public WorkspaceConcurrencyService(WorkspaceFileService files) {
        this.files = files;
    }

    public WorkspaceVersionedTextFile read(String path) {
        WorkspaceTextFile file = files.readText(path);
        return new WorkspaceVersionedTextFile(
                file.path(),
                file.content(),
                sha256(file.content())
        );
    }

    public WorkspaceCheckedWriteResult write(
            String path,
            String expectedSha256,
            String content
    ) {
        WorkspaceVersionedTextFile current = read(path);
        String expected = expectedSha256 == null ? "" : expectedSha256.trim();
        if (expected.isBlank()) {
            throw new IllegalArgumentException("expectedSha256 must not be blank");
        }
        if (!current.sha256().equalsIgnoreCase(expected)) {
            return new WorkspaceCheckedWriteResult(
                    current.path(),
                    false,
                    false,
                    expected,
                    current.sha256(),
                    current.sha256(),
                    0,
                    "Workspace file changed since it was read"
            );
        }

        String value = content == null ? "" : content;
        WorkspaceWriteResult written = files.writeText(path, value);
        String hash = sha256(value);
        return new WorkspaceCheckedWriteResult(
                written.path(),
                true,
                false,
                expected,
                current.sha256(),
                hash,
                written.charactersWritten(),
                "Workspace file written"
        );
    }

    public static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
