package tools.dscode.common.agentfast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** PNG on disk. The reply names the path and the size and does not inline base64. */
public final class ScreenshotReply {
    private ScreenshotReply() {
    }

    public static Map<String, Object> write(Path file, byte[] png) throws IOException {
        byte[] bytes = png == null ? new byte[0] : png;
        durable(file, bytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sentence", "Wrote the screenshot.");
        body.put("path", file.toString());
        body.put("byteSize", bytes.length);
        body.put("mime", "image/png");
        return body;
    }

    /** Writes a sibling {@code .part}, forces it, then replaces the destination. The reply waits until the file is closed. */
    static void durable(Path file, byte[] bytes) throws IOException {
        byte[] payload = bytes == null ? new byte[0] : bytes;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName().toString() + ".part");
        try (FileChannel channel = FileChannel.open(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            channel.write(ByteBuffer.wrap(payload));
            channel.force(true);
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
