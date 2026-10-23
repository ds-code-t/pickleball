package tools.dscode.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Workbench-only tag fallback. The feature-index parser is unchanged.
 * Cucumber's tag expression language has {@code and}, {@code or}, and {@code not}.
 * An unescaped {@code xor} is rewritten only on the Maven {@code pkb_tags} string.
 */
public final class WorkbenchTagFallback {
    private WorkbenchTagFallback() {
    }

    /** {@code @smoke xor @wip} becomes {@code @smoke\ xor\ @wip}, which Cucumber accepts as one literal tag. */
    public static String escapedForm(String expression) {
        if (expression == null) return "";
        return expression.replace(" ", "\\ ");
    }

    /**
     * Rewrites each top-level unescaped {@code xor} to
     * {@code (A or B) and not (A and B)}, left to right.
     * An already escaped expression is returned unchanged.
     */
    public static String cucumberTags(String expression) {
        if (expression == null || expression.isBlank()) return expression;
        List<String> parts = splitTopLevelXor(expression);
        if (parts.size() < 2) return expression;
        String combined = parts.get(0).trim();
        for (int i = 1; i < parts.size(); i++) {
            String left = parenthesize(combined);
            String right = parenthesize(parts.get(i).trim());
            combined = "(" + left + " or " + right + ") and not (" + left + " and " + right + ")";
        }
        return combined;
    }

    public static String warning(String reason, String original) {
        String text = original == null ? "" : original;
        String rewritten = cucumberTags(text);
        boolean unchanged = rewritten == null || rewritten.equals(text);
        String escaped = unchanged ? text : escapedForm(text);
        StringBuilder message = new StringBuilder();
        message.append("WARNING: ").append(reason == null ? "unsupported tag expression" : reason)
                .append(". Cucumber accepts and, or, and not, not xor.")
                .append(" Working escaped form: --tags=").append(escaped);
        if (!unchanged) {
            message.append(". Maven pkb_tags=").append(rewritten);
        }
        return message.toString();
    }

    static List<String> splitTopLevelXor(String expression) {
        List<String> parts = new ArrayList<>();
        String source = expression == null ? "" : expression;
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        char quote = 0;
        for (int index = 0; index < source.length(); ) {
            char c = source.charAt(index);
            if (c == '\\' && index + 1 < source.length()) {
                current.append(c).append(source.charAt(index + 1));
                index += 2;
                continue;
            }
            if (quoted) {
                current.append(c);
                if (c == quote) quoted = false;
                index++;
                continue;
            }
            if (c == '"' || c == '\'') {
                quoted = true;
                quote = c;
                current.append(c);
                index++;
                continue;
            }
            if (c == '(') {
                depth++;
                current.append(c);
                index++;
                continue;
            }
            if (c == ')') {
                if (depth > 0) depth--;
                current.append(c);
                index++;
                continue;
            }
            if (depth == 0 && isXorOperator(source, index)) {
                parts.add(current.toString());
                current.setLength(0);
                index += 3;
                continue;
            }
            current.append(c);
            index++;
        }
        parts.add(current.toString());
        return parts;
    }

    private static boolean isXorOperator(String text, int index) {
        if (index + 3 > text.length()) return false;
        if (!text.regionMatches(true, index, "xor", 0, 3)) return false;
        if (index > 0) {
            char before = text.charAt(index - 1);
            if (isWord(before)) return false;
            if (index >= 2 && text.charAt(index - 2) == '\\' && before == ' ') return false;
        }
        int end = index + 3;
        if (end < text.length()) {
            char after = text.charAt(end);
            if (isWord(after) || after == '\\') return false;
        }
        return true;
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@';
    }

    private static String parenthesize(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("(") && text.endsWith(")") && balanced(text)) return text;
        return "(" + text + ")";
    }

    private static boolean balanced(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth < 0) return false;
            }
        }
        return depth == 0;
    }
}
