package tools.dscode.control.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDK-only JSON reader and compact writer. Shared by the protocol module. */
public final class JsonLite {
    private JsonLite() {
    }

    public static Object parse(String json) {
        if (json == null) throw new IllegalArgumentException("JSON is empty.");
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skip();
        if (!parser.end()) throw new IllegalArgumentException("Trailing data in JSON.");
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (key instanceof String name) map.put(name, item);
        });
        return map;
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String text -> {
                out.append('"');
                for (int index = 0; index < text.length(); index++) {
                    char ch = text.charAt(index);
                    switch (ch) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        case '\b' -> out.append("\\b");
                        case '\f' -> out.append("\\f");
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> {
                            if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                            else out.append(ch);
                        }
                    }
                }
                out.append('"');
            }
            case Boolean bool -> out.append(bool ? "true" : "false");
            case Integer number -> out.append(number);
            case Long number -> out.append(number);
            case Short number -> out.append(number);
            case Byte number -> out.append(number);
            case Number number -> {
                double doubleValue = number.doubleValue();
                if (Double.isFinite(doubleValue) && doubleValue == Math.rint(doubleValue)
                        && doubleValue >= Long.MIN_VALUE && doubleValue <= Long.MAX_VALUE) {
                    out.append((long) doubleValue);
                } else {
                    out.append(number);
                }
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) continue;
                    if (!first) out.append(',');
                    first = false;
                    write(key, out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int index = 0; index < list.size(); index++) {
                    if (index > 0) out.append(',');
                    write(list.get(index), out);
                }
                out.append(']');
            }
            default -> write(String.valueOf(value), out);
        }
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        private Object parseValue() {
            skip();
            if (end()) throw new IllegalArgumentException("Unexpected end of JSON.");
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
            if (ch == '-' || (ch >= '0' && ch <= '9')) return parseNumber();
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
                if (end() || text.charAt(index) != '"') {
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
                if (!peek(',')) throw new IllegalArgumentException("Expected ',' or '}'.");
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
                if (!peek(',')) throw new IllegalArgumentException("Expected ',' or ']'.");
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
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("Short \\u escape.");
                        }
                        int code = Integer.parseInt(text.substring(index, index + 4), 16);
                        value.append((char) code);
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Bad JSON escape.");
                }
            }
            throw new IllegalArgumentException("Unclosed JSON string.");
        }

        private Number parseNumber() {
            int start = index;
            if (peek('-')) index++;
            if (end() || text.charAt(index) < '0' || text.charAt(index) > '9') {
                throw new IllegalArgumentException("Bad JSON number.");
            }
            while (!end() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
            boolean real = false;
            if (!end() && text.charAt(index) == '.') {
                real = true;
                index++;
                while (!end() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
            }
            if (!end() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                real = true;
                index++;
                if (!end() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++;
                while (!end() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
            }
            String token = text.substring(start, index);
            if (!real) {
                try {
                    return Long.parseLong(token);
                } catch (NumberFormatException ignored) {
                    return Double.parseDouble(token);
                }
            }
            return Double.parseDouble(token);
        }

        private void skip() {
            while (!end()) {
                char ch = text.charAt(index);
                if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') return;
                index++;
            }
        }

        private boolean peek(char ch) {
            return !end() && text.charAt(index) == ch;
        }

        private boolean startsWith(String token) {
            return text.startsWith(token, index);
        }

        private boolean end() {
            return index >= text.length();
        }
    }
}
