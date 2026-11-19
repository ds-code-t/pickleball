package tools.dscode.common.agentfast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** PNG on disk. The reply names the path and the size and does not inline base64. */
public final class ScreenshotReply {
    private ScreenshotReply() {
    }

    public static Map<String, Object> write(Path file, byte[] png) throws IOException {
        byte[] bytes = png == null ? new byte[0] : png;
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, bytes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sentence", "Wrote the screenshot.");
        body.put("path", file.toString());
        body.put("byteSize", bytes.length);
        body.put("mime", "image/png");
        return body;
    }
}
