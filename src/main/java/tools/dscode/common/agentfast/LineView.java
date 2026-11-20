package tools.dscode.common.agentfast;

/** Line tokens. A safe bare value is printed as-is. Anything else is a JSON string. */
public final class LineView {
    private LineView() {
    }

    public static boolean bare(String value) {
        if (value == null || value.isEmpty()) return false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch >= 'A' && ch <= 'Z') continue;
            if (ch >= 'a' && ch <= 'z') continue;
            if (ch >= '0' && ch <= '9') continue;
            if (ch == '_' || ch == '.' || ch == '/' || ch == ':' || ch == '@' || ch == '+' || ch == '-') continue;
            return false;
        }
        return true;
    }

    /** Bare when {@link #bare} is true. Otherwise a JSON string literal, including newlines and non-ASCII. */
    public static String token(String value) {
        if (bare(value)) return value;
        String raw = value == null ? "" : value;
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            switch (ch) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (ch < 0x20 || ch > 0x7e) json.append(String.format("\\u%04x", (int) ch));
                    else json.append(ch);
                }
            }
        }
        json.append('"');
        return json.toString();
    }
}
