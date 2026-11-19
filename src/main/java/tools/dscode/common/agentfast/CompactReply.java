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
        String label = kind == null || kind.isBlank() ? "reply" : kind;
        body.put("sentence", label + " has " + phrases.size() + " hits");
        try {
            return JSON.writeValueAsString(shrink(project, kind, body));
        } catch (IOException failure) {
            return "{\"ok\":true,\"compact\":true,\"bytes\":" + bytes.length + "}";
        }
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

    /** Drops trailing phrases, then shortens the sentence, until the object fits with room for elapsedMs. */
    private static void fit(Map<String, Object> compact) {
        int budget = LIMIT - 64;
        while (jsonSize(compact) > budget) {
            Object phrases = compact.get("phrases");
            if (phrases instanceof List<?> list && !list.isEmpty()) {
                List<Object> copy = new ArrayList<>(list);
                copy.remove(copy.size() - 1);
                if (copy.isEmpty()) compact.remove("phrases");
                else compact.put("phrases", copy);
                continue;
            }
            Object sentence = compact.get("sentence");
            String text = sentence == null ? "" : String.valueOf(sentence);
            if (text.length() > 24) {
                compact.put("sentence", text.substring(0, text.length() / 2));
                continue;
            }
            break;
        }
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
