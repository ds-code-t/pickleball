package tools.dscode.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code --url-key} reads a dotted config key such as {@code URL.forms} from
 * the project's YAML and properties. A literal http(s), file, data, about, or
 * fixture value is returned as itself.
 */
final class UrlKeys {
    private UrlKeys() {
    }

    static String resolve(Path project, String key) {
        if (key == null || key.isBlank()) return null;
        String wanted = key.trim();
        if (looksNavigable(wanted) || tools.dscode.common.agentfast.FixturePages.isFixture(wanted)) {
            return tools.dscode.common.agentfast.FixturePages.resolve(wanted);
        }
        if (project == null) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (Path root : List.of(
                project.resolve("src/test/resources"),
                project.resolve("src/main/resources"),
                project.resolve(".pickleball")
        )) {
            scan(root, values, 0);
        }
        String direct = values.get(wanted);
        if (direct != null && !direct.isBlank()) return direct;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(wanted) && entry.getValue() != null && !entry.getValue().isBlank()) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static boolean looksNavigable(String value) {
        String head = value.toLowerCase(Locale.ROOT);
        return head.startsWith("https://") || head.startsWith("http://")
                || head.startsWith("file:") || head.startsWith("data:")
                || head.startsWith("about:");
    }

    private static void scan(Path root, Map<String, String> values, int depth) {
        if (root == null || depth > 6 || !Files.isDirectory(root)) return;
        try (Stream<Path> children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = String.valueOf(child.getFileName());
                if (name.startsWith(".")) continue;
                if (Files.isDirectory(child)) {
                    scan(child, values, depth + 1);
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".properties"))) continue;
                try {
                    String text = Files.readString(child);
                    if (lower.endsWith(".properties")) properties(text, values);
                    else yaml(text, values);
                } catch (IOException ignored) {
                    // One unreadable config does not hide the others.
                }
            }
        } catch (IOException ignored) {
            // A missing tree is not a URL.
        }
    }

    private static void properties(String text, Map<String, String> values) {
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) eq = line.indexOf(':');
            if (eq <= 0) continue;
            values.put(line.substring(0, eq).trim(), strip(line.substring(eq + 1).trim()));
        }
    }

    private static void yaml(String text, Map<String, String> values) {
        List<String> stack = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            if (raw.isBlank() || raw.trim().startsWith("#")) continue;
            int indent = 0;
            while (indent < raw.length() && raw.charAt(indent) == ' ') indent++;
            String line = raw.trim();
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            while (!indents.isEmpty() && indent <= indents.get(indents.size() - 1)) {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            if (value.isEmpty() || "|".equals(value) || ">".equals(value)) {
                stack.add(key);
                indents.add(indent);
                continue;
            }
            String dotted = stack.isEmpty() ? key : String.join(".", stack) + "." + key;
            values.put(dotted, strip(value));
        }
    }

    private static String strip(String value) {
        if (value.length() >= 2) {
            char open = value.charAt(0);
            char close = value.charAt(value.length() - 1);
            if ((open == '"' && close == '"') || (open == '\'' && close == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        int comment = value.indexOf(" #");
        return comment < 0 ? value : value.substring(0, comment).trim();
    }
}
