package tools.dscode.common.recording;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** File-backed record mode under {@code .pickleball/recordings/state.json}. */
public final class RecordingSession {
    public static final String RELATIVE = ".pickleball/recordings/state.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object LOCK = new Object();
    private static final TypeReference<LinkedHashMap<String, Object>> MAP = new TypeReference<>() {
    };

    private RecordingSession() {
    }

    public static Path file(Path project) {
        Path root = project == null ? Path.of("") : project;
        return root.resolve(RELATIVE);
    }

    public static Map<String, Object> start(Path project, String sessionId) {
        synchronized (LOCK) {
            Map<String, Object> state = read(project);
            int cursor = tools.dscode.control.protocol.AgentEvents.lineCount(project);
            String session = sessionId == null || sessionId.isBlank()
                    ? "rec-" + Long.toString(System.currentTimeMillis(), 36)
                    : sessionId.trim();
            state.put("status", "recording");
            state.put("sessionId", session);
            state.put("startedAt", Instant.now().toString());
            state.put("stoppedAt", "");
            state.put("cursor", cursor);
            state.put("endCursor", -1);
            state.put("absorbed", cursor);
            state.putIfAbsent("dropped", new ArrayList<Integer>());
            state.putIfAbsent("literals", new ArrayList<String>());
            state.putIfAbsent("renames", new LinkedHashMap<String, String>());
            state.putIfAbsent("context", "");
            write(project, state);
            tools.dscode.common.agentfast.PushChannel.bind(project, session);
            return copy(state);
        }
    }

    public static Map<String, Object> stop(Path project) {
        synchronized (LOCK) {
            Map<String, Object> state = read(project);
            state.put("status", "stopped");
            state.put("stoppedAt", Instant.now().toString());
            state.put("endCursor", tools.dscode.control.protocol.AgentEvents.lineCount(project));
            write(project, state);
            return copy(state);
        }
    }

    public static Map<String, Object> pause(Path project) {
        return status(project, "paused");
    }

    public static Map<String, Object> resume(Path project) {
        return status(project, "recording");
    }

    public static Map<String, Object> read(Path project) {
        synchronized (LOCK) {
            Path file = file(project);
            if (!Files.isRegularFile(file)) return empty();
            try {
                Map<String, Object> state = JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), MAP);
                if (state == null) return empty();
                state.putIfAbsent("status", "stopped");
                state.putIfAbsent("dropped", new ArrayList<>());
                state.putIfAbsent("literals", new ArrayList<>());
                state.putIfAbsent("renames", new LinkedHashMap<>());
                state.putIfAbsent("context", "");
                return state;
            } catch (IOException failure) {
                return empty();
            }
        }
    }

    public static void update(Path project, Map<String, Object> state) {
        synchronized (LOCK) {
            write(project, state == null ? empty() : state);
        }
    }

    public static boolean recording(Path project) {
        return "recording".equals(String.valueOf(read(project).get("status")));
    }

    public static String sessionId(Path project) {
        Object value = read(project).get("sessionId");
        return value == null ? "" : String.valueOf(value);
    }

    public static int cursor(Path project) {
        return number(read(project).get("cursor"));
    }

    public static int endCursor(Path project) {
        Object value = read(project).get("endCursor");
        if (value == null) return -1;
        int parsed = number(value);
        return parsed < 0 ? -1 : parsed;
    }

    private static Map<String, Object> status(Path project, String status) {
        synchronized (LOCK) {
            Map<String, Object> state = read(project);
            if ("stopped".equals(String.valueOf(state.get("status"))) && "recording".equals(status)) {
                return start(project, String.valueOf(state.getOrDefault("sessionId", "")));
            }
            state.put("status", status);
            write(project, state);
            return copy(state);
        }
    }

    private static void write(Path project, Map<String, Object> state) {
        Path file = file(project);
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(state), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("error: recording state could not be written", failure);
        }
    }

    private static Map<String, Object> empty() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("status", "stopped");
        state.put("sessionId", "");
        state.put("startedAt", "");
        state.put("stoppedAt", "");
        state.put("cursor", 0);
        state.put("endCursor", -1);
        state.put("dropped", new ArrayList<Integer>());
        state.put("literals", new ArrayList<String>());
        state.put("renames", new LinkedHashMap<String, String>());
        state.put("context", "");
        return state;
    }

    @SuppressWarnings("unchecked")
    static List<Integer> dropped(Map<String, Object> state) {
        Object value = state.get("dropped");
        List<Integer> dropped = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) dropped.add(number(item));
        }
        return dropped;
    }

    @SuppressWarnings("unchecked")
    static List<String> literals(Map<String, Object> state) {
        Object value = state.get("literals");
        List<String> literals = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) if (item != null) literals.add(String.valueOf(item));
        }
        return literals;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> renames(Map<String, Object> state) {
        Object value = state.get("renames");
        Map<String, String> names = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                if (key != null && item != null) names.put(String.valueOf(key), String.valueOf(item));
            });
        }
        return names;
    }

    static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static Map<String, Object> copy(Map<String, Object> state) {
        return new LinkedHashMap<>(state);
    }
}
