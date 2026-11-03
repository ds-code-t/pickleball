package tools.dscode.control.protocol;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One live Workbench session, schema {@code session-descriptor.v1}.
 *
 * <p>The value is JDK-only so the controller and the launcher share it.
 * {@link SessionDescriptorWriter} is the only writer.</p>
 */
public record SessionDescriptor(
        String sessionId,
        String runId,
        String workspacePath,
        String browserType,
        String cdpDebuggerAddress,
        String currentUrl,
        List<String> windowHandles,
        State state,
        Instant startedAt,
        Instant updatedAt,
        String compositeSessionDir,
        String compositeSessionId
) {
    public static final String SCHEMA = "session-descriptor.v1";
    public static final String CONTRACT_VERSION = "1";
    public static final String FILE_NAME = "descriptor.json";

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");

    /**
     * Same descriptor without a composite session. Existing callers stay valid.
     * A later attach fills {@code compositeSessionDir} and {@code compositeSessionId}.
     */
    public SessionDescriptor(
            String sessionId,
            String runId,
            String workspacePath,
            String browserType,
            String cdpDebuggerAddress,
            String currentUrl,
            List<String> windowHandles,
            State state,
            Instant startedAt,
            Instant updatedAt
    ) {
        this(
                sessionId, runId, workspacePath, browserType, cdpDebuggerAddress, currentUrl,
                windowHandles, state, startedAt, updatedAt, null, null
        );
    }

    public enum State {
        IDLE,
        RUNNING,
        PAUSED,
        HELD,
        STOPPING,
        STOPPED;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static State fromWire(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("state is required.");
            }
            try {
                return State.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Unknown session state: " + value);
            }
        }

        /**
         * Same state is a refresh. {@code stopped} is terminal.
         * {@code stopping} can only finish. The others move through pause, hold, and stop.
         */
        public boolean allows(State next) {
            if (next == null) return false;
            if (next == this) return true;
            return switch (this) {
                case IDLE -> next == RUNNING || next == STOPPING || next == STOPPED;
                case RUNNING -> next == PAUSED || next == HELD || next == STOPPING || next == STOPPED;
                case PAUSED -> next == RUNNING || next == HELD || next == STOPPING || next == STOPPED;
                case HELD -> next == RUNNING || next == PAUSED || next == STOPPING || next == STOPPED;
                case STOPPING -> next == STOPPED;
                case STOPPED -> false;
            };
        }
    }

    public SessionDescriptor {
        sessionId = token(sessionId, "sessionId");
        runId = RunView.requireSafeId(runId);
        if (workspacePath == null || workspacePath.isBlank() || !Path.of(workspacePath).isAbsolute()) {
            throw new IllegalArgumentException("workspacePath must be an absolute path.");
        }
        workspacePath = Path.of(workspacePath).normalize().toString();
        browserType = blankToNull(browserType);
        cdpDebuggerAddress = blankToNull(cdpDebuggerAddress);
        currentUrl = blankToNull(currentUrl);
        windowHandles = copyHandles(windowHandles);
        if (state == null) throw new IllegalArgumentException("state is required.");
        if (startedAt == null || updatedAt == null) {
            throw new IllegalArgumentException("startedAt and updatedAt are required.");
        }
        compositeSessionDir = blankToNull(compositeSessionDir);
        compositeSessionId = blankToNull(compositeSessionId);
        if (compositeSessionDir != null && !Path.of(compositeSessionDir).isAbsolute()) {
            throw new IllegalArgumentException("compositeSessionDir must be an absolute path.");
        }
    }

    public String contractVersion() {
        return CONTRACT_VERSION;
    }

    public SessionDescriptor withState(State next, Instant updated) {
        if (!state.allows(next)) {
            throw new IllegalStateException(
                    "Cannot move session " + sessionId + " from " + state.wire() + " to "
                            + (next == null ? "null" : next.wire()) + "."
            );
        }
        if (updated == null) throw new IllegalArgumentException("updatedAt is required.");
        return new SessionDescriptor(
                sessionId, runId, workspacePath, browserType, cdpDebuggerAddress, currentUrl,
                windowHandles, next, startedAt, updated,
                compositeSessionDir, compositeSessionId
        );
    }

    /** Same session, with the page fields replaced. Composite join fields stay. */
    public SessionDescriptor withPage(
            String browserType,
            String cdpDebuggerAddress,
            String currentUrl,
            List<String> windowHandles,
            Instant updated
    ) {
        if (updated == null) throw new IllegalArgumentException("updatedAt is required.");
        return new SessionDescriptor(
                sessionId, runId, workspacePath, browserType, cdpDebuggerAddress, currentUrl,
                windowHandles, state, startedAt, updated,
                compositeSessionDir, compositeSessionId
        );
    }

    /** Records the composite registry directory and session id. State does not change. */
    public SessionDescriptor withComposite(String sessionDir, String sessionId, Instant updated) {
        if (updated == null) throw new IllegalArgumentException("updatedAt is required.");
        return new SessionDescriptor(
                this.sessionId, runId, workspacePath, browserType, cdpDebuggerAddress, currentUrl,
                windowHandles, state, startedAt, updated,
                sessionDir, sessionId
        );
    }

    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        field(json, "schema", SCHEMA);
        field(json, "contractVersion", CONTRACT_VERSION);
        field(json, "sessionId", sessionId);
        field(json, "runId", runId);
        field(json, "workspacePath", workspacePath);
        field(json, "browserType", browserType);
        field(json, "cdpDebuggerAddress", cdpDebuggerAddress);
        field(json, "currentUrl", currentUrl);
        json.append("  \"windowHandles\": ");
        appendHandles(json);
        json.append(",\n");
        field(json, "state", state.wire());
        field(json, "compositeSessionDir", compositeSessionDir);
        field(json, "compositeSessionId", compositeSessionId);
        field(json, "startedAt", startedAt.toString());
        json.append("  \"updatedAt\": ").append(quoted(updatedAt.toString())).append('\n');
        json.append("}\n");
        return json.toString();
    }

    public static SessionDescriptor parse(String json) {
        if (!(parseJson(json) instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Session descriptor must be a JSON object.");
        }
        Map<String, Object> map = stringsOnly(raw);
        String schema = requiredText(map, "schema");
        if (!SCHEMA.equals(schema)) {
            throw new IllegalArgumentException("Unsupported session descriptor schema: " + schema);
        }
        String contract = requiredText(map, "contractVersion");
        if (!CONTRACT_VERSION.equals(contract)) {
            throw new IllegalArgumentException("Unsupported contractVersion: " + contract);
        }
        return new SessionDescriptor(
                requiredText(map, "sessionId"),
                requiredText(map, "runId"),
                requiredText(map, "workspacePath"),
                optionalText(map, "browserType"),
                optionalText(map, "cdpDebuggerAddress"),
                optionalText(map, "currentUrl"),
                handles(map.get("windowHandles")),
                State.fromWire(requiredText(map, "state")),
                instant(requiredText(map, "startedAt"), "startedAt"),
                instant(requiredText(map, "updatedAt"), "updatedAt"),
                optionalPresent(map, "compositeSessionDir"),
                optionalPresent(map, "compositeSessionId")
        );
    }

    private static void field(StringBuilder json, String name, String value) {
        json.append("  \"").append(name).append("\": ").append(quoted(value)).append(",\n");
    }

    private void appendHandles(StringBuilder json) {
        if (windowHandles.isEmpty()) {
            json.append("[]");
            return;
        }
        json.append("[\n");
        for (int i = 0; i < windowHandles.size(); i++) {
            json.append("    ").append(quoted(windowHandles.get(i)));
            json.append(i + 1 < windowHandles.size() ? ",\n" : "\n");
        }
        json.append("  ]");
    }

    private static String quoted(String value) {
        if (value == null) return "null";
        return "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else escaped.append(ch);
                }
            }
        }
        return escaped.toString();
    }

    private static List<String> copyHandles(List<String> handles) {
        if (handles == null || handles.isEmpty()) return List.of();
        List<String> copy = new ArrayList<>(handles.size());
        for (String handle : handles) {
            if (handle == null || handle.isBlank()) {
                throw new IllegalArgumentException("window handle is blank.");
            }
            copy.add(handle);
        }
        return List.copyOf(copy);
    }

    private static String token(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        String trimmed = value.trim();
        if (!TOKEN.matcher(trimmed).matches() || trimmed.contains("..")) {
            throw new IllegalArgumentException(label + " must be a single token: " + value);
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Map<String, Object> stringsOnly(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (!(key instanceof String name)) {
                throw new IllegalArgumentException("Session descriptor keys must be strings.");
            }
            map.put(name, value);
        });
        return map;
    }

    private static String requiredText(Map<String, Object> map, String key) {
        String value = optionalText(map, key);
        if (value == null) throw new IllegalArgumentException(key + " is required.");
        return value;
    }

    private static String optionalText(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) throw new IllegalArgumentException("Missing " + key + ".");
        return textOrNull(map, key);
    }

    /** Absent composite fields stay null so an older descriptor still parses. */
    private static String optionalPresent(Map<String, Object> map, String key) {
        if (!map.containsKey(key)) return null;
        return textOrNull(map, key);
    }

    private static String textOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof String text) return text;
        throw new IllegalArgumentException(key + " must be a string or null.");
    }

    private static List<String> handles(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("windowHandles must be an array.");
        }
        List<String> handles = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("window handle must be a non-blank string.");
            }
            handles.add(text);
        }
        return List.copyOf(handles);
    }

    private static Instant instant(String value, String label) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException(label + " must be ISO-8601: " + value);
        }
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Session descriptor is empty.");
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skip();
        if (!parser.end()) throw new IllegalArgumentException("Trailing data in session descriptor.");
        return value;
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Object parseValue() {
            skip();
            if (end()) throw new IllegalArgumentException("Unexpected end of session descriptor.");
            char ch = text.charAt(index);
            if (ch == '{') return parseObject();
            if (ch == '[') return parseArray();
            if (ch == '"') return parseString();
            if (startsWith("null")) {
                index += 4;
                return null;
            }
            if (startsWith("true")) {
                index += 4;
                return Boolean.TRUE;
            }
            if (startsWith("false")) {
                index += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Unexpected JSON at " + index + ".");
        }

        private Map<String, Object> parseObject() {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return map;
            }
            while (true) {
                skip();
                if (text.charAt(index) != '"') {
                    throw new IllegalArgumentException("Expected a JSON string key.");
                }
                String key = parseString();
                skip();
                if (!peek(':')) throw new IllegalArgumentException("Expected ':' after " + key + ".");
                index++;
                map.put(key, parseValue());
                skip();
                if (peek('}')) {
                    index++;
                    return map;
                }
                if (!peek(',')) throw new IllegalArgumentException("Expected ',' or '}' in session descriptor.");
                index++;
            }
        }

        private List<Object> parseArray() {
            index++;
            List<Object> list = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skip();
                if (peek(']')) {
                    index++;
                    return list;
                }
                if (!peek(',')) throw new IllegalArgumentException("Expected ',' or ']' in windowHandles.");
                index++;
            }
        }

        private String parseString() {
            index++;
            StringBuilder value = new StringBuilder();
            while (!end()) {
                char ch = text.charAt(index++);
                if (ch == '"') return value.toString();
                if (ch != '\\') {
                    value.append(ch);
                    continue;
                }
                if (end()) break;
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(unicode());
                    default -> throw new IllegalArgumentException("Bad JSON escape.");
                }
            }
            throw new IllegalArgumentException("Unclosed JSON string.");
        }

        private char unicode() {
            if (index + 4 > text.length()) throw new IllegalArgumentException("Short JSON unicode escape.");
            int code = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(index++), 16);
                if (digit < 0) throw new IllegalArgumentException("Bad JSON unicode escape.");
                code = (code << 4) + digit;
            }
            return (char) code;
        }

        private void skip() {
            while (!end() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private boolean peek(char expected) {
            return !end() && text.charAt(index) == expected;
        }

        private boolean startsWith(String token) {
            return text.startsWith(token, index);
        }

        private boolean end() {
            return index >= text.length();
        }
    }
}
