package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps inspect, page, and step replies at or under 2 KiB. Full text stays on disk. */
public final class CompactReply {
    public static final int LIMIT = 2048;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private CompactReply() {
    }

    public static Map<String, Object> shrink(Path project, String kind, Map<String, Object> body) {
        String json;
        try {
            json = JSON.writeValueAsString(body == null ? Map.of() : body);
        } catch (IOException failure) {
            json = String.valueOf(body);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length <= LIMIT) {
            return body == null ? Map.of() : body;
        }
        Path full = write(project, kind, json);
        Map<String, Object> compact = base(kind, json.getBytes(StandardCharsets.UTF_8).length, full);
        if (body != null && body.get("elapsedMs") != null) compact.put("elapsedMs", body.get("elapsedMs"));
        Object sentence = body == null ? null : body.get("sentence");
        if (sentence != null) compact.put("sentence", clip(String.valueOf(sentence)));
        return compact;
    }

    /** Returns {@code text} when it fits. Otherwise one JSON object and the full text on disk. */
    public static String shrinkText(Path project, String kind, String text) {
        String raw = text == null ? "" : text;
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= LIMIT) return raw;
        Path full = write(project, kind, raw);
        try {
            return JSON.writeValueAsString(base(kind, bytes.length, full));
        } catch (IOException failure) {
            return "{\"ok\":true,\"compact\":true,\"bytes\":" + bytes.length + "}";
        }
    }

    private static Map<String, Object> base(String kind, int bytes, Path full) {
        Map<String, Object> compact = new LinkedHashMap<>();
        compact.put("ok", true);
        compact.put("compact", true);
        compact.put("kind", kind == null ? "reply" : kind);
        compact.put("bytes", bytes);
        compact.put("full", full.toString());
        compact.put("sentence", clip((kind == null ? "reply" : kind) + " is on disk"));
        return compact;
    }

    private static String clip(String sentence) {
        if (sentence == null) return "";
        return sentence.length() <= 180 ? sentence : sentence.substring(0, 180);
    }

    private static Path write(Path project, String kind, String text) {
        Path dir = (project == null ? Path.of("") : project).resolve(".pickleball").resolve("agent-full");
        try {
            Files.createDirectories(dir);
            String safe = kind == null || kind.isBlank() ? "reply" : kind.replaceAll("[^A-Za-z0-9_-]", "-");
            Path file = dir.resolve(safe + "-" + System.currentTimeMillis() + "-" + SEQUENCE.incrementAndGet() + ".json");
            Files.writeString(file, text);
            return file;
        } catch (IOException failure) {
            return dir.resolve("unwritten.json");
        }
    }
}
