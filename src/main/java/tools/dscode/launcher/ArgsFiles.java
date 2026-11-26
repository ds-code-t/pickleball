package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code @<file>} and {@code --args-file=<file>} on every verb. The file is a JSON object of flags.
 * Explicit flags win. {@code call} keeps its own args-file reader. MCP uses {@link #merge}.
 */
public final class ArgsFiles {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KNOWN = knownFlags();

    private ArgsFiles() {
    }

    public record Expansion(String[] argv, boolean fromFile) {
    }

    public static boolean mentions(String[] args) {
        if (args == null) return false;
        for (String token : args) {
            if (token == null) continue;
            if (token.startsWith("--args-file=") || "--args-file".equals(token) || token.startsWith("--args-json=")) {
                return true;
            }
            if (isAtFile(token)) return true;
        }
        return false;
    }

    public static Expansion expand(String[] args) {
        if (args == null || args.length == 0 || !mentions(args)) return new Expansion(args, false);
        String command = args[0] == null ? "" : args[0];
        if ("call".equals(command)) return expandCall(args);
        Map<String, String> fromFile = new LinkedHashMap<>();
        Map<String, String> explicit = new LinkedHashMap<>();
        List<String> positionals = new ArrayList<>();
        boolean usedFile = false;
        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            String file = fileToken(token, args, index);
            if (file != null) {
                if ("--args-file".equals(token)) index++;
                mergeFile(command, file, fromFile);
                usedFile = true;
                continue;
            }
            if (token.startsWith("--args-json=")) {
                mergeJson(command, token.substring("--args-json=".length()), fromFile);
                usedFile = true;
                continue;
            }
            if (token.startsWith("--") && token.length() > 2) {
                String body = token.substring(2);
                int equals = body.indexOf('=');
                if (equals >= 0) {
                    explicit.put(body.substring(0, equals), body.substring(equals + 1));
                } else if (index + 1 < args.length && args[index + 1] != null && !args[index + 1].startsWith("--")
                        && !isAtFile(args[index + 1])) {
                    explicit.put(body, args[++index]);
                } else {
                    explicit.put(body, "true");
                }
                continue;
            }
            positionals.add(token);
        }
        Map<String, String> merged = merge(command, explicit, fromFile);
        List<String> rebuilt = new ArrayList<>();
        rebuilt.add(command);
        rebuilt.addAll(positionals);
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || "args-file".equals(key) || "args-json".equals(key)) continue;
            String value = entry.getValue();
            if (value == null || "true".equals(value)) rebuilt.add("--" + key);
            else rebuilt.add("--" + key + "=" + value);
        }
        return new Expansion(rebuilt.toArray(String[]::new), usedFile);
    }

    /**
     * File flags under explicit flags. Explicit wins. An unknown key fails and the message
     * does not include the value.
     */
    public static Map<String, String> merge(String command, Map<String, String> explicit, Map<String, ?> file) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (file != null) {
            for (Map.Entry<String, ?> entry : file.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    throw failure("args file has an empty key", "remove the empty key from the args file");
                }
                if (!known(command, key)) {
                    throw failure("unknown args key " + key, "remove " + key + " from the args file");
                }
                String value = scalar(entry.getValue(), key);
                if (value == null) continue;
                merged.put(key, value);
            }
        }
        if (explicit != null) {
            for (Map.Entry<String, String> entry : explicit.entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                merged.put(entry.getKey(), entry.getValue() == null ? "true" : entry.getValue());
            }
        }
        merged.remove("args-file");
        merged.remove("args-json");
        return merged;
    }

    private static Expansion expandCall(String[] args) {
        boolean hasFile = false;
        for (String token : args) {
            if (token != null && (token.startsWith("--args-file=") || "--args-file".equals(token))) hasFile = true;
        }
        List<String> rebuilt = new ArrayList<>();
        boolean fromFile = false;
        for (String token : args) {
            if (token != null && isAtFile(token) && !hasFile) {
                Path path = atPath(token);
                rebuilt.add("--args-file=" + path);
                fromFile = true;
                continue;
            }
            if (token != null && isAtFile(token)) {
                fromFile = true;
                continue;
            }
            rebuilt.add(token);
        }
        return new Expansion(rebuilt.toArray(String[]::new), fromFile);
    }

    private static void mergeFile(String command, String file, Map<String, String> into) {
        Path path = Path.of(file);
        if (!path.isAbsolute()) path = Path.of("").toAbsolutePath().resolve(file).normalize();
        if (!Files.isRegularFile(path)) {
            throw failure("args file not found", "pass --args-file=<file> or @<file> that exists");
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw failure("args file cannot be read", "pass a readable JSON object of flags");
        }
        mergeJson(command, text, into);
    }

    private static void mergeJson(String command, String text, Map<String, String> into) {
        JsonNode node;
        try {
            node = JSON.readTree(text == null ? "" : text);
        } catch (Exception failure) {
            throw failure("args file is not a JSON object", "pass a JSON object of flags");
        }
        if (!node.isObject()) {
            throw failure("args file is not a JSON object", "pass a JSON object of flags");
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> raw.put(entry.getKey(), jsonValue(entry.getValue())));
        into.putAll(merge(command, Map.of(), raw));
    }

    private static Object jsonValue(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        if (value.isTextual()) return value.asText();
        return value;
    }

    private static String scalar(Object value, String key) {
        if (value == null) return null;
        if (value instanceof Boolean flag) return flag ? "true" : null;
        if (value instanceof Number || value instanceof String) return String.valueOf(value);
        throw failure("args file value for " + key + " is not a string, number, or boolean",
                "use a string, number, or boolean for " + key);
    }

    static boolean known(String command, String key) {
        if (key == null || key.isBlank()) return false;
        if (KNOWN.contains(key)) return true;
        if (openFamily(command) && key.matches("[A-Za-z][A-Za-z0-9_-]*")) return true;
        return false;
    }

    private static boolean openFamily(String command) {
        return "store".equals(command) || "engine".equals(command) || "domengine".equals(command)
                || "dashboard".equals(command) || "log".equals(command) || "log-tail".equals(command);
    }

    private static String fileToken(String token, String[] args, int index) {
        if (token.startsWith("--args-file=")) return token.substring("--args-file=".length());
        if ("--args-file".equals(token)) {
            if (index + 1 >= args.length || args[index + 1] == null || args[index + 1].isBlank()) {
                throw failure("args file not found", "pass --args-file=<file>");
            }
            return args[index + 1];
        }
        if (isAtFile(token)) return atPath(token).toString();
        return null;
    }

    private static boolean isAtFile(String token) {
        if (token == null || token.length() < 2 || !token.startsWith("@") || token.startsWith("--")) return false;
        String rest = token.substring(1);
        return rest.endsWith(".json") || rest.indexOf('/') >= 0 || rest.indexOf('\\') >= 0;
    }

    private static Path atPath(String token) {
        return Path.of(token.substring(1));
    }

    private static IllegalArgumentException failure(String error, String fix) {
        return new IllegalArgumentException(WorkbenchCliHelp.structured(error, fix));
    }

    private static Set<String> knownFlags() {
        Set<String> names = new LinkedHashSet<>();
        add(names, WorkbenchCliFlags.COMMAND_LINE);
        add(names, WorkbenchCliFlags.AGENT);
        add(names, WorkbenchCliFlags.SESSION);
        add(names, WorkbenchCommandLine.MAJOR_FLAGS);
        add(names, new ArrayList<>(WorkbenchCommandLine.TRAILING_VALUE_FLAGS));
        for (List<String> used : WorkbenchCliFlags.USED_BY.values()) add(names, used);
        for (String extra : List.of(
                "args-file", "args-json", "args", "args-b64", "project", "noop", "once", "follow",
                "url", "browser", "headed", "panel", "out", "body", "action", "area", "steps",
                "activate", "ops", "source", "channel", "dry-run", "confirm", "force", "path",
                "glue", "sandbox", "text", "window", "exe", "human-override", "refind", "identity",
                "purpose", "via", "page", "budget-ms", "aria-ref", "stop-on-fail", "format", "where",
                "limit", "offset", "session", "sessionId", "run-id", "agent-id", "fields", "budget"
        )) {
            names.add(extra);
        }
        return names;
    }

    private static void add(Set<String> names, List<String> flags) {
        if (flags == null) return;
        for (String flag : flags) {
            if (flag == null || flag.isBlank()) continue;
            String token = flag.startsWith("--") ? flag.substring(2) : flag;
            int equals = token.indexOf('=');
            if (equals >= 0) token = token.substring(0, equals);
            names.add(token.toLowerCase(Locale.ROOT));
            names.add(token);
        }
    }
}
