package tools.dscode.control.protocol;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Formatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared CLI and MCP text. Secret values go through {@link SecretRedactor}.
 * Ellipsis is ASCII {@code ...}.
 */
public final class OutputFormatter {
    private OutputFormatter() {
    }

    /** Blank is {@code ""}. A secret is redacted. A stored reference is unchanged. */
    public static String shown(String key, String value) {
        if (value == null || value.isEmpty()) return "\"\"";
        return SecretRedactor.display(key, value);
    }

    public static Map<String, String> redactMap(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        if (values == null) return out;
        values.forEach((key, value) -> out.put(key, value == null ? "" : SecretRedactor.display(key, value)));
        return out;
    }

    /**
     * Launch echo of the stored workspace. Unlike retained {@code pkb_run_profile}
     * serialization, this does not rewrite a stored reference to {@code ${protected:<key>}}.
     */
    public static String launchEcho(Map<String, String> stored) {
        if (stored == null || stored.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        new TreeMap<>(stored).forEach((key, value) -> {
            if (key == null || value == null) return;
            if (!out.isEmpty()) out.append(", ");
            out.append(key).append('=').append(quoteIfNeeded(SecretRedactor.display(key, value)));
        });
        return out.toString();
    }

    /** Walk maps and lists. Sensitive string fields are redacted before JSON. */
    public static Object redactValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                String name = String.valueOf(key);
                if (child instanceof String text) {
                    out.put(name, SecretRedactor.scrub(SecretRedactor.display(name, text)));
                } else out.put(name, redactValue(child));
            });
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>(collection.size());
            for (Object item : collection) out.add(redactValue(item));
            return out;
        }
        if (value instanceof String text) return SecretRedactor.scrub(text);
        return value;
    }

    /** U+2026 is a diamond on a default Windows console. CLI text uses ASCII. */
    public static String ascii(String text) {
        if (text == null) return "";
        return text.replace("\u2026", "...");
    }

    public static PrintStream wrap(PrintStream delegate) {
        if (delegate instanceof RedactingPrintStream) return delegate;
        return new RedactingPrintStream(delegate);
    }

    private static String quoteIfNeeded(String value) {
        if (value == null || value.isEmpty()) return "";
        boolean startsWithQuote = value.charAt(0) == '"' || value.charAt(0) == '\'';
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf(';') >= 0
                || value.indexOf('"') >= 0
                || startsWithQuote
                || !value.equals(value.trim());
        if (!quote) return value;
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '\\' || ch == '"' || ch == ',' || ch == ';') escaped.append('\\');
            escaped.append(ch);
        }
        return '"' + escaped.toString() + '"';
    }

    static final class RedactingPrintStream extends PrintStream {
        RedactingPrintStream(OutputStream out) {
            super(out, true, StandardCharsets.UTF_8);
        }

        @Override
        public void print(String s) {
            super.print(ascii(SecretRedactor.scrub(s)));
        }

        @Override
        public void print(Object obj) {
            print(String.valueOf(obj));
        }

        @Override
        public PrintStream format(String format, Object... args) {
            print(new Formatter().format(format, args).toString());
            return this;
        }

        @Override
        public PrintStream printf(String format, Object... args) {
            return format(format, args);
        }
    }
}
