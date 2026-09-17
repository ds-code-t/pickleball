package tools.dscode.workbench.player;

import tools.dscode.control.protocol.PickleballLocalLayout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Session-only unsaved Gherkin for the worker diagnostic pack.
 * Workbench writes this sidecar; Pickleball core copies it into
 * {@code source/live-buffer.feature} when it differs from disk.
 */
public final class LiveBufferSidecar {
    public static final String FILE_NAME = "live-buffer.feature";

    private LiveBufferSidecar() {
    }

    public static Path path(Path projectRoot) {
        return PickleballLocalLayout.workbenchStateRoot(projectRoot).resolve(FILE_NAME);
    }

    public static void publish(Path projectRoot, Path originFile, String liveText) {
        if (projectRoot == null) return;
        Path sidecar = path(projectRoot);
        try {
            if (originFile == null || !Files.isRegularFile(originFile)) {
                Files.deleteIfExists(sidecar);
                return;
            }
            String live = liveText == null ? "" : liveText.replace("\r\n", "\n").replace('\r', '\n');
            String disk = Files.readString(originFile, StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n');
            if (live.isBlank() || live.equals(disk)) {
                Files.deleteIfExists(sidecar);
                return;
            }
            Files.createDirectories(sidecar.getParent());
            Files.writeString(sidecar, live, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Sidecar is best-effort evidence input, not a save path.
        }
    }
}
