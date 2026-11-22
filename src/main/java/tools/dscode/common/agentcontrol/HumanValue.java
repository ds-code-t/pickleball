package tools.dscode.common.agentcontrol;

/**
 * Person-facing text keeps a concrete value in quotes, then paints that quote as a chip.
 * The quote characters stay in the text so a copy of the sentence still contains the value.
 */
public final class HumanValue {
    private HumanValue() {
    }

    /** {@code abc} becomes {@code "abc"}. A quote or backslash inside the value is escaped. */
    public static String quote(String value) {
        String text = value == null ? "" : value;
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * Quotes {@code value} inside {@code instruction} when that value is present and not already quoted.
     * An instruction that does not contain the value gains the quoted value at the end.
     */
    public static String distinguish(String instruction, String value) {
        String words = instruction == null ? "" : instruction;
        if (value == null || value.isEmpty()) return words;
        String quoted = quote(value);
        if (words.contains(quoted)) return words;
        int at = rawAt(words, value);
        if (at >= 0) return words.substring(0, at) + quoted + words.substring(at + value.length());
        if (words.isEmpty()) return quoted;
        return words.endsWith(" ") ? words + quoted : words + " " + quoted;
    }

    /** Escapes HTML, then wraps each quoted span in {@code <span class="pkb-value">}. */
    public static String html(String text) {
        return chip(escape(text == null ? "" : text));
    }

    /** Wraps already-escaped {@code &quot;} or {@code &#39;} spans. The quote entities stay inside the chip. */
    public static String chip(String escaped) {
        if (escaped == null || escaped.isEmpty()) return "";
        StringBuilder out = new StringBuilder(escaped.length() + 32);
        int index = 0;
        while (index < escaped.length()) {
            int dq = escaped.indexOf("&quot;", index);
            int sq = escaped.indexOf("&#39;", index);
            if (dq < 0 && sq < 0) {
                out.append(escaped, index, escaped.length());
                break;
            }
            int at;
            String token;
            if (dq >= 0 && (sq < 0 || dq <= sq)) {
                at = dq;
                token = "&quot;";
            } else {
                at = sq;
                token = "&#39;";
            }
            int end = escaped.indexOf(token, at + token.length());
            if (end < 0) {
                out.append(escaped, index, escaped.length());
                break;
            }
            out.append(escaped, index, at);
            out.append("<span class=\"pkb-value\">");
            out.append(escaped, at, end + token.length());
            out.append("</span>");
            index = end + token.length();
        }
        return out.toString();
    }

    private static int rawAt(String words, String value) {
        int from = 0;
        while (from <= words.length()) {
            int at = words.indexOf(value, from);
            if (at < 0) return -1;
            boolean open = at > 0 && words.charAt(at - 1) == '"';
            int after = at + value.length();
            boolean close = after < words.length() && words.charAt(after) == '"';
            if (!(open && close)) return at;
            from = after;
        }
        return -1;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(current);
            }
        }
        return out.toString();
    }
}
