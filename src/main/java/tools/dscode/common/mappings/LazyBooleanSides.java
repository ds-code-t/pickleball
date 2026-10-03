package tools.dscode.common.mappings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tools.dscode.common.evaluations.AviatorUtil.eval;
import static tools.dscode.common.evaluations.AviatorUtil.isTruthy;

/**
 * Splits {@code &&} and {@code ||} into sides before Aviator runs.
 * A side is pasted only when that operator actually reads it.
 * A single {@code |} is not a split.
 */
final class LazyBooleanSides {
    private static final String TOKEN_PREFIX = "pkbLazy";

    private LazyBooleanSides() {
    }

    static boolean containsOperator(String text) {
        return findOperator(text, 0, text.length(), false) >= 0;
    }

    static boolean evaluate(MappingProcessor processor, String expression) {
        String source = expression == null ? "" : expression;
        Sides sides = new Sides();
        String rewritten = rewrite(source, sides);
        if (sides.texts.isEmpty()) {
            return pasteAndBoolean(processor, source);
        }
        Object value = eval(rewritten, sides.environment(processor));
        return isTruthy(value);
    }

    private static String rewrite(String expression, Sides sides) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isEmpty()) {
            return sides.add("");
        }
        String parenInner = unwrap(trimmed, "(", ")");
        if (parenInner != null) {
            return rewrite(parenInner, sides);
        }
        List<String> orSides = splitBy(trimmed, "||");
        if (orSides.size() > 1) {
            return join(orSides, "||", sides);
        }
        List<String> andSides = splitBy(trimmed, "&&");
        if (andSides.size() > 1) {
            return join(andSides, "&&", sides);
        }
        String expanded = expandGroups(trimmed, sides);
        if (!expanded.equals(trimmed)) {
            return expanded;
        }
        return sides.add(trimmed);
    }

    private static String join(List<String> parts, String operator, Sides sides) {
        StringBuilder built = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) {
                built.append(' ').append(operator).append(' ');
            }
            built.append(sides.add(parts.get(index)));
        }
        return built.toString();
    }

    private static List<String> splitBy(String text, String operator) {
        List<String> parts = new ArrayList<>();
        int sideStart = 0;
        int paren = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (paren == 0 && startsWith(text, index, "<{")) {
                int close = matchingClose(text, index, "<{", "}>");
                if (close >= 0) {
                    index = close - 1;
                    continue;
                }
            }
            if (paren == 0 && startsWith(text, index, "~[~{")) {
                int close = matchingClose(text, index, "~[~{", "}~]~");
                if (close >= 0) {
                    index = close - 1;
                    continue;
                }
            }
            if (current == '(') {
                paren++;
                continue;
            }
            if (current == ')' && paren > 0) {
                paren--;
                continue;
            }
            if (paren == 0 && text.startsWith(operator, index)) {
                parts.add(text.substring(sideStart, index));
                index += operator.length() - 1;
                sideStart = index + 1;
            }
        }
        if (!parts.isEmpty()) {
            parts.add(text.substring(sideStart));
        }
        return parts;
    }

    private static String expandGroups(String text, Sides sides) {
        StringBuilder built = new StringBuilder();
        boolean changed = false;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote != 0) {
                built.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                built.append(current);
                continue;
            }
            if (startsWith(text, index, "<{")) {
                int close = matchingClose(text, index, "<{", "}>");
                if (close >= 0) {
                    String span = text.substring(index, close);
                    String body = text.substring(index + 2, close - 2);
                    if (containsOperator(body)) {
                        built.append(sides.add(span));
                        changed = true;
                    } else {
                        built.append(span);
                    }
                    index = close - 1;
                    continue;
                }
            }
            if (startsWith(text, index, "~[~{")) {
                int close = matchingClose(text, index, "~[~{", "}~]~");
                if (close >= 0) {
                    String span = text.substring(index, close);
                    String body = text.substring(index + 4, close - 4);
                    if (containsOperator(body)) {
                        built.append(sides.add(span));
                        changed = true;
                    } else {
                        built.append(span);
                    }
                    index = close - 1;
                    continue;
                }
            }
            if (current == '(') {
                int close = matchingClose(text, index, "(", ")");
                if (close >= 0) {
                    String span = text.substring(index, close);
                    String inner = text.substring(index + 1, close - 1);
                    if (containsOperator(inner)) {
                        built.append(sides.add(span));
                        changed = true;
                    } else {
                        built.append(span);
                    }
                    index = close - 1;
                    continue;
                }
            }
            built.append(current);
        }
        return changed ? built.toString() : text;
    }

    private static boolean pasteAndBoolean(MappingProcessor processor, String side) {
        String pasted = processor.resolveWholeText(side);
        if (pasted == null || pasted.isBlank() || isWholeUnresolvedReference(pasted)) {
            return false;
        }
        if (containsOperator(pasted) && !pasted.trim().equals(side.trim())) {
            return evaluate(processor, pasted);
        }
        return isTruthy(eval(pasted, processor));
    }

    private static boolean isWholeUnresolvedReference(String text) {
        String trimmed = text.trim();
        if (trimmed.length() < 3) {
            return false;
        }
        if (trimmed.charAt(0) == '<' && trimmed.charAt(trimmed.length() - 1) == '>'
                && trimmed.indexOf('<', 1) < 0 && trimmed.indexOf('>') == trimmed.length() - 1) {
            return true;
        }
        return trimmed.startsWith("~[~") && trimmed.endsWith("~]~")
                && trimmed.indexOf("~[~", 3) < 0
                && trimmed.lastIndexOf("~]~") == trimmed.length() - 3;
    }

    private static int findOperator(String text, int start, int end, boolean topLevelOnly) {
        int paren = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < end; index++) {
            char current = text.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (topLevelOnly && current == '(') {
                paren++;
                continue;
            }
            if (topLevelOnly && current == ')' && paren > 0) {
                paren--;
                continue;
            }
            if (!topLevelOnly || paren == 0) {
                if (operatorAt(text, index) != null && index < end) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static String operatorAt(String text, int index) {
        if (index + 1 >= text.length()) {
            return null;
        }
        char current = text.charAt(index);
        char next = text.charAt(index + 1);
        if ((current == '|' && next == '|') || (current == '&' && next == '&')) {
            return text.substring(index, index + 2);
        }
        return null;
    }

    private static String unwrap(String text, String open, String close) {
        if (!text.startsWith(open) || !text.endsWith(close)) {
            return null;
        }
        int end = matchingClose(text, 0, open, close);
        if (end != text.length()) {
            return null;
        }
        return text.substring(open.length(), text.length() - close.length());
    }

    static int matchingClose(String text, int openIndex, String open, String close) {
        if (!startsWith(text, openIndex, open)) {
            return -1;
        }
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = openIndex; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }
            if (startsWith(text, index, open)) {
                depth++;
                index += open.length() - 1;
                continue;
            }
            if (startsWith(text, index, close)) {
                depth--;
                if (depth == 0) {
                    return index + close.length();
                }
                index += close.length() - 1;
            }
        }
        return -1;
    }

    private static boolean startsWith(String text, int index, String token) {
        return text.startsWith(token, index);
    }

    private static final class Sides {
        private final List<String> texts = new ArrayList<>();

        private String add(String side) {
            String token = TOKEN_PREFIX + texts.size();
            texts.add(side == null ? "" : side.trim());
            return token;
        }

        private HashMap<String, Object> environment(MappingProcessor processor) {
            return new HashMap<>() {
                private final Set<String> reading = new HashSet<>();
                private final HashMap<String, Boolean> resolved = new HashMap<>();

                @Override
                public Object get(Object key) {
                    if (!(key instanceof String name) || !name.startsWith(TOKEN_PREFIX)) {
                        return super.get(key);
                    }
                    int index;
                    try {
                        index = Integer.parseInt(name.substring(TOKEN_PREFIX.length()));
                    } catch (NumberFormatException ex) {
                        return super.get(key);
                    }
                    if (index < 0 || index >= texts.size()) {
                        return super.get(key);
                    }
                    if (resolved.containsKey(name)) {
                        return resolved.get(name);
                    }
                    if (!reading.add(name)) {
                        throw new IllegalStateException("Cyclic boolean side " + name);
                    }
                    try {
                        boolean value = read(processor, texts.get(index));
                        resolved.put(name, value);
                        return value;
                    } finally {
                        reading.remove(name);
                    }
                }
            };
        }

        private static boolean read(MappingProcessor processor, String raw) {
            String side = raw == null ? "" : raw.trim();
            if (side.isEmpty()) {
                return false;
            }
            String expression = unwrap(side, "<{", "}>");
            if (expression == null) {
                expression = unwrap(side, "~[~{", "}~]~");
            }
            if (expression != null && containsOperator(expression)) {
                return evaluate(processor, expression);
            }
            if (containsOperator(side)) {
                return evaluate(processor, side);
            }
            return pasteAndBoolean(processor, side);
        }
    }
}
