package tools.dscode.common.agentfast;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Built-in pages for {@code fixture:<name>}. No HTTP server. The address is a
 * {@code data:} URL Chrome and {@code navigate to:} already accept.
 */
public final class FixturePages {
    private FixturePages() {
    }

    /** The token unchanged when it is not {@code fixture:}. Null when the name is empty. */
    public static String resolve(String token) {
        if (token == null) return null;
        String text = token.trim();
        if (!text.toLowerCase(Locale.ROOT).startsWith("fixture:")) return text;
        String name = text.substring("fixture:".length()).trim();
        if (name.isEmpty()) return null;
        String html = html(name);
        return "data:text/html;charset=utf-8," + percent(html);
    }

    public static boolean isFixture(String token) {
        if (token == null) return false;
        String text = token.trim().toLowerCase(Locale.ROOT);
        return text.startsWith("fixture:") && text.length() > "fixture:".length();
    }

    private static String html(String name) {
        String safe = name.replace("<", "").replace(">", "").replace("&", "");
        return switch (safe) {
            case "panel" -> """
                    <!DOCTYPE html><html><head><title>Panel</title></head>
                    <body><button id="save">Save</button></body></html>
                    """;
            case "form" -> """
                    <!DOCTYPE html><html><head><title>Form</title></head>
                    <body><label for="email">Email</label><input id="email" name="email">
                    <button id="go">Go</button></body></html>
                    """;
            default -> """
                    <!DOCTYPE html><html><head><title>Fixture</title></head>
                    <body><p id="fixture">%s</p><button id="save">Save</button></body></html>
                    """.formatted(safe);
        };
    }

    private static String percent(String html) {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length * 3);
        for (byte bite : bytes) {
            int value = bite & 0xff;
            if ((value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z')
                    || (value >= '0' && value <= '9')) {
                out.append((char) value);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit(value >>> 4, 16)));
                out.append(Character.toUpperCase(Character.forDigit(value & 0xf, 16)));
            }
        }
        return out.toString();
    }
}
