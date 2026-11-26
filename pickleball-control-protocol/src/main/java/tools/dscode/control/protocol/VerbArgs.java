package tools.dscode.control.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flag map shared by the CLI and MCP. Keys are flag names without {@code --}.
 * Positional tokens live under {@code args}, one token per line. {@code ARGV}
 * is the exact original argument vector, unit-separator encoded, and is not a flag.
 */
public final class VerbArgs {
    public static final String ARGS = "args";
    public static final String ARGV = "ARGV";
    public static final char SEPARATOR = '\u001f';

    private VerbArgs() {
    }

    /** Tokens after the verb. {@code --name=value}, {@code --name value}, and bare {@code --name}. */
    public static Map<String, String> fromTokens(List<String> tokens) {
        Map<String, String> flags = new LinkedHashMap<>();
        if (tokens == null || tokens.isEmpty()) return flags;
        List<String> positionals = new ArrayList<>();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token == null) continue;
            if (token.startsWith("--") && token.length() > 2) {
                String body = token.substring(2);
                int equals = body.indexOf('=');
                if (equals >= 0) {
                    flags.put(body.substring(0, equals), body.substring(equals + 1));
                } else if (index + 1 < tokens.size()) {
                    String next = tokens.get(index + 1);
                    if (next != null && !next.startsWith("--")) {
                        flags.put(body, next);
                        index++;
                    } else {
                        flags.put(body, "true");
                    }
                } else {
                    flags.put(body, "true");
                }
            } else if (!token.isBlank()) {
                positionals.add(token);
            }
        }
        if (!positionals.isEmpty()) flags.put(ARGS, String.join("\n", positionals));
        return flags;
    }

    /** Full argv, including the verb. The encoded vector is {@link #ARGV}. */
    public static Map<String, String> capture(String[] argv) {
        List<String> rest = new ArrayList<>();
        if (argv != null) {
            for (int index = 1; index < argv.length; index++) {
                if (argv[index] != null) rest.add(argv[index]);
            }
        }
        Map<String, String> flags = fromTokens(rest);
        flags.put(ARGV, encode(argv));
        return flags;
    }

    /**
     * Rebuild argv. The verb may contain spaces ({@code domengine session log} is not used;
     * pass the family as {@code verb} and the rest under {@code args}). {@code args} and
     * {@code ARGV} are not emitted as flags. A value of {@code true} or empty is a bare flag.
     */
    public static String[] toArgv(String verb, Map<String, String> flags) {
        List<String> out = new ArrayList<>();
        if (verb != null && !verb.isBlank()) {
            for (String part : verb.trim().split("\\s+")) {
                if (!part.isBlank()) out.add(part);
            }
        }
        Map<String, String> use = flags == null ? Map.of() : flags;
        String positionals = use.get(ARGS);
        if (positionals != null && !positionals.isEmpty()) {
            for (String token : positionals.split("\n", -1)) {
                if (!token.isEmpty()) out.add(token);
            }
        }
        for (Map.Entry<String, String> entry : use.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || ARGS.equals(key) || ARGV.equals(key)) continue;
            String value = entry.getValue();
            if (value == null || value.isEmpty() || "true".equals(value)) out.add("--" + key);
            else out.add("--" + key + "=" + value);
        }
        return out.toArray(String[]::new);
    }

    public static String encode(String[] argv) {
        if (argv == null || argv.length == 0) return "";
        return String.join(String.valueOf(SEPARATOR), argv);
    }

    public static String[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new String[0];
        return encoded.split(String.valueOf(SEPARATOR), -1);
    }
}
