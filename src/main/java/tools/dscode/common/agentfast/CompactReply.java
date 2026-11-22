package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Keeps inspect, page, and step replies at or under 2 KiB. Full text stays on disk. */
public final class CompactReply {
    public static final int LIMIT = 2048;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private CompactReply() {
    }

    public static Map<String, Object> shrink(Path project, String kind, Map<String, Object> body) {
        Map<String, Object> source = body == null ? Map.of() : body;
        String json;
        try {
            json = JSON.writeValueAsString(source);
        } catch (IOException failure) {
            json = String.valueOf(source);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length <= LIMIT) {
            return new LinkedHashMap<>(source);
        }
        Object text = source.get("text");
        boolean plain = text instanceof String;
        String payload = plain ? (String) text : json;
        Path full = write(project, kind, payload, plain);
        Map<String, Object> compact = base(kind, payload.getBytes(StandardCharsets.UTF_8).length, full);
        if (source.get("elapsedMs") != null) compact.put("elapsedMs", source.get("elapsedMs"));
        if (source.get("hits") != null) compact.put("hits", source.get("hits"));
        if (source.get("phrases") instanceof List<?> phrases && !phrases.isEmpty()) {
            compact.put("phrases", new ArrayList<>(phrases));
        }
        if (source.get("lines") instanceof List<?> lines && !lines.isEmpty()) {
            compact.put("lines", new ArrayList<>(lines));
        }
        if (source.get("kept") instanceof String kept && !kept.isBlank()) compact.put("kept", kept);
        Object sentence = source.get("sentence");
        if (sentence != null) compact.put("sentence", clip(String.valueOf(sentence)));
        fit(compact);
        return compact;
    }

    /** Returns {@code text} when it fits. Otherwise one JSON object and the full text on disk. */
    public static String shrinkText(Path project, String kind, String text) {
        String raw = text == null ? "" : text;
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= LIMIT) return raw;
        List<String> phrases = phrasesOf(raw);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", raw);
        body.put("hits", phrases.size());
        if (!phrases.isEmpty()) {
            int keep = Math.min(8, phrases.size());
            body.put("phrases", new ArrayList<>(phrases.subList(0, keep)));
        }
        String kept = keptLines(raw);
        if (!kept.isBlank()) body.put("kept", kept);
        List<String> lines = elementLines(raw);
        if (!lines.isEmpty()) body.put("lines", lines);
        String label = kind == null || kind.isBlank() ? "reply" : kind;
        body.put("sentence", label + " has " + phrases.size() + " hits");
        try {
            return JSON.writeValueAsString(shrink(project, kind, body));
        } catch (IOException failure) {
            return "{\"ok\":true,\"compact\":true,\"bytes\":" + bytes.length + "}";
        }
    }

    /**
     * Identity lines an agent still needs when the body is on disk:
     * {@code source=}, {@code frame=}, {@code query=}, {@code note=}, and the first xpath and counts.
     */
    static String keptLines(String raw) {
        StringBuilder kept = new StringBuilder();
        boolean xpath = false;
        boolean rawCount = false;
        boolean visible = false;
        for (String line : raw.split("\n", -1)) {
            String stored = null;
            if (line.startsWith("source=") || line.startsWith("frame=") || line.startsWith("query=") || line.startsWith("note=")) {
                stored = line;
            } else if (!xpath && line.startsWith("xpath=")) {
                xpath = true;
                String value = line.substring("xpath=".length());
                stored = value.length() > 60 ? "xpath=" + value.substring(0, 57) + "..." : line;
            } else if (!rawCount && line.startsWith("rawMatchCount=")) {
                rawCount = true;
                stored = line;
            } else if (!visible && line.startsWith("visibleCount=")) {
                visible = true;
                stored = line;
            }
            if (stored == null) continue;
            if (kept.length() > 0) kept.append('\n');
            kept.append(stored);
        }
        return kept.toString();
    }

    /** One compact element row per inspect hit, so a shrunk page still lists the rows. */
    static List<String> elementLines(String raw) {
        List<String> lines = new ArrayList<>();
        String frame = "top";
        String phrase = null;
        String visible = null;
        for (String line : raw.split("\n", -1)) {
            if (line.startsWith("frame=")) frame = line.substring("frame=".length());
            else if (line.startsWith("phrase=")) {
                if (phrase != null) lines.add(elementLine(phrase, visible, frame));
                phrase = line.substring("phrase=".length());
                visible = null;
            } else if (line.startsWith("visibleCount=")) {
                visible = line.substring("visibleCount=".length());
            }
        }
        if (phrase != null) lines.add(elementLine(phrase, visible, frame));
        return lines;
    }

    private static String elementLine(String phrase, String visible, String frame) {
        String shown = phrase == null ? "" : phrase.trim();
        if (shown.length() > 80) shown = shown.substring(0, 77) + "...";
        String count = visible == null || visible.isBlank() ? "0" : visible.trim();
        String where = frame == null || frame.isBlank() ? "top" : frame.trim();
        return "- element " + LineView.token(shown) + " visibleCount=" + LineView.token(count) + " frame=" + LineView.token(where);
    }

    private static List<String> phrasesOf(String raw) {
        List<String> phrases = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            if (!line.startsWith("phrase=")) continue;
            String phrase = line.substring("phrase=".length()).trim();
            if (phrase.length() > 80) phrase = phrase.substring(0, 77) + "...";
            phrases.add(phrase);
        }
        return phrases;
    }

    /**
     * Drops extra phrases, then extra element lines, until the object fits.
     * The first phrase and one element line stay.
     */
    private static void fit(Map<String, Object> compact) {
        int budget = LIMIT - 64;
        while (jsonSize(compact) > budget) {
            if (dropLast(compact, "phrases", 1)) continue;
            if (dropLast(compact, "lines", 1)) continue;
            Object sentence = compact.get("sentence");
            String text = sentence == null ? "" : String.valueOf(sentence);
            if (text.length() > 24) {
                compact.put("sentence", text.substring(0, text.length() / 2));
                continue;
            }
            Object kept = compact.get("kept");
            String keptText = kept == null ? "" : String.valueOf(kept);
            if (keptText.length() > 160) {
                compact.put("kept", keptText.substring(0, 157) + "...");
                continue;
            }
            if (clipElementLine(compact)) continue;
            break;
        }
    }

    private static boolean dropLast(Map<String, Object> compact, String key, int keep) {
        Object value = compact.get(key);
        if (!(value instanceof List<?> list) || list.size() <= keep) return false;
        List<Object> copy = new ArrayList<>(list);
        copy.remove(copy.size() - 1);
        compact.put(key, copy);
        return true;
    }

    /** Shortens the remaining element line instead of deleting the only row. */
    private static boolean clipElementLine(Map<String, Object> compact) {
        Object value = compact.get("lines");
        if (!(value instanceof List<?> list) || list.isEmpty()) return false;
        String text = String.valueOf(list.get(list.size() - 1));
        if (text.length() <= 48) return false;
        List<Object> copy = new ArrayList<>(list);
        copy.set(copy.size() - 1, text.substring(0, 45) + "...");
        compact.put("lines", copy);
        return true;
    }

    private static int jsonSize(Map<String, Object> compact) {
        try {
            return JSON.writeValueAsString(compact).getBytes(StandardCharsets.UTF_8).length;
        } catch (IOException failure) {
            return LIMIT + 1;
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

    /** Puts {@code elapsedMs} on a compact JSON object, or a trailing line on plain text. */
    public static String withElapsed(String printed, long elapsedMs) {
        long ms = Math.max(0L, elapsedMs);
        String trimmed = printed == null ? "" : printed.stripTrailing();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed.substring(0, trimmed.length() - 1) + ",\"elapsedMs\":" + ms + "}";
        }
        return trimmed + "\nelapsedMs=" + ms;
    }

    private static Path write(Path project, String kind, String text, boolean plainText) {
        Path dir = (project == null ? Path.of("") : project).resolve(".pickleball").resolve("agent-full");
        if (!enabled()) return dir.resolve("disabled.json");
        try {
            Files.createDirectories(dir);
            String safe = kind == null || kind.isBlank() ? "reply" : kind.replaceAll("[^A-Za-z0-9_-]", "-");
            String date = LocalDate.now().toString();
            String extension = plainText ? ".txt" : ".json";
            Path file = dir.resolve(safe + "-" + date + "-" + System.currentTimeMillis() + "-" + SEQUENCE.incrementAndGet() + extension);
            Files.writeString(file, text);
            prune(dir);
            return file;
        } catch (IOException failure) {
            return dir.resolve("unwritten.json");
        }
    }

    private static void prune(Path dir) throws IOException {
        long max = maxBytes();
        try (Stream<Path> listing = Files.list(dir)) {
            var files = listing.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::getFileName)).toList();
            long total = 0L;
            for (Path file : files) total += Files.size(file);
            int index = 0;
            while (total > max && index < files.size() - 1) {
                long size = Files.size(files.get(index));
                Files.deleteIfExists(files.get(index));
                total -= size;
                index++;
            }
        }
    }

    private static boolean enabled() {
        String raw = setting("pkb_agent_full", "PKB_AGENT_FULL");
        if (raw == null || raw.isBlank()) return true;
        return !"off".equalsIgnoreCase(raw.trim()) && !"false".equalsIgnoreCase(raw.trim());
    }

    private static long maxBytes() {
        String raw = setting("pkb_agent_full_max_bytes", "PKB_AGENT_FULL_MAX_BYTES");
        if (raw == null || raw.isBlank()) return 1_048_576L;
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed < 256 ? 256 : parsed;
        } catch (NumberFormatException failure) {
            return 1_048_576L;
        }
    }

    private static String setting(String property, String env) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) return value;
        return System.getenv(env);
    }
}
