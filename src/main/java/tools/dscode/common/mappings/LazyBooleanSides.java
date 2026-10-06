package tools.dscode.common.mappings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static tools.dscode.common.evaluations.AviatorUtil.eval;
import static tools.dscode.common.evaluations.AviatorUtil.isTruthy;
import static tools.dscode.common.reporting.logging.LogForwarder.logInfo;

/**
 * Splits {@code &&}, {@code ||}, and {@code ?:} before any map paste.
 * A component is pasted only when that operator actually reads it.
 * A single {@code |} is not a split. A trailing {@code ?} is the boolean marker.
 */
final class LazyBooleanSides {
    private static final String TOKEN_PREFIX = "pkbLazy";

    private LazyBooleanSides() {
    }

    static boolean containsOperator(String text) {
        return findOperator(text, 0, text.length(), false) >= 0;
    }

    /**
     * Evaluates one expression reference and logs one info line.
     * Taken components are filled in. Skipped components stay as written.
     */
    static String evaluateReference(
            MappingProcessor processor,
            String expression,
            String open,
            String close
    ) {
        String source = expression == null ? "" : expression;
        EvalTrace trace = new EvalTrace();
        try {
            Object value = evaluateValue(processor, source, trace);
            String result = stringify(value);
            logInfo(reference(open, source, close) + " -> " + picture(trace, source) + " -> " + result);
            return result;
        } catch (RuntimeException ex) {
            RuntimeException named = unwrapTokenFailure(ex);
            String message = named.getMessage() == null ? named.toString() : named.getMessage();
            logInfo("evaluation failed " + reference(open, source, close)
                    + " -> " + picture(trace, source)
                    + " -> " + message);
            throw named;
        }
    }

    private static Object evaluateValue(
            MappingProcessor processor,
            String expression,
            EvalTrace trace
    ) {
        String source = expression == null ? "" : expression;
        String trimmed = source.trim();
        if (trace.picture == null) {
            trace.picture = trimmed;
        }
        if (trimmed.isEmpty()) {
            trace.picture = "";
            return evalPlain(processor, "", trace);
        }
        if (endsWithBooleanMarker(trimmed)) {
            String inner = trimmed.substring(0, trimmed.length() - 1).trim();
            EvalTrace innerTrace = new EvalTrace();
            try {
                Object value = evaluateValue(processor, inner, innerTrace);
                trace.picture = innerTrace.picture == null ? inner : innerTrace.picture;
                return asBooleanMarker(value, inner);
            } catch (RuntimeException ex) {
                trace.picture = innerTrace.picture == null ? inner : innerTrace.picture;
                throw ex;
            }
        }
        Ternary ternary = splitTernary(trimmed);
        if (ternary != null) {
            return evaluateTernary(processor, ternary, trace);
        }
        if (containsOperator(trimmed)) {
            return evaluateBoolean(processor, trimmed, trace);
        }
        int[] group = innermostTernaryGroup(trimmed);
        if (group != null) {
            return evaluateGroupedTernary(processor, trimmed, group, trace);
        }
        return evalPlain(processor, trimmed, trace);
    }

    private static Object evaluateTernary(
            MappingProcessor processor,
            Ternary ternary,
            EvalTrace trace
    ) {
        Sides sides = new Sides();
        String rewritten = sides.add(ternary.condition, true)
                + " ? " + sides.add(ternary.whenTrue, false)
                + " : " + sides.add(ternary.whenFalse, false);
        try {
            Object value = evalRewritten(rewritten, sides.environment(processor));
            trace.picture = sides.picture(rewritten);
            return value;
        } catch (RuntimeException ex) {
            trace.picture = sides.picture(rewritten);
            throw unwrapTokenFailure(ex);
        }
    }

    private static boolean evaluateBoolean(
            MappingProcessor processor,
            String expression,
            EvalTrace trace
    ) {
        Sides sides = new Sides();
        String rewritten = rewrite(expression, sides);
        try {
            Object value = evalRewritten(rewritten, sides.environment(processor));
            trace.picture = sides.picture(rewritten);
            return isTruthy(value);
        } catch (RuntimeException ex) {
            trace.picture = sides.picture(rewritten);
            throw unwrapTokenFailure(ex);
        }
    }

    private static Object evaluateGroupedTernary(
            MappingProcessor processor,
            String text,
            int[] group,
            EvalTrace trace
    ) {
        int open = group[0];
        int close = group[1];
        String interior = text.substring(open + 1, close - 1);
        if (isFunctionCall(text, open)) {
            List<String> args = splitArgs(interior);
            StringBuilder literals = new StringBuilder();
            StringBuilder pictures = new StringBuilder();
            RuntimeException failure = null;
            for (int index = 0; index < args.size(); index++) {
                if (index > 0) {
                    literals.append(", ");
                    pictures.append(", ");
                }
                String arg = args.get(index);
                if (failure != null) {
                    pictures.append(arg.trim());
                    continue;
                }
                EvalTrace argTrace = new EvalTrace();
                try {
                    Object value = evaluateValue(processor, arg, argTrace);
                    literals.append(toLiteral(value));
                    pictures.append(argTrace.picture == null ? arg.trim() : argTrace.picture);
                } catch (RuntimeException ex) {
                    pictures.append(argTrace.picture == null ? arg.trim() : argTrace.picture);
                    failure = ex;
                }
            }
            String lazyPicture = text.substring(0, open + 1) + pictures + text.substring(close - 1);
            trace.picture = lazyPicture;
            if (failure != null) {
                throw failure;
            }
            String evalText = text.substring(0, open + 1) + literals + text.substring(close - 1);
            return evalReduced(processor, evalText, trace, lazyPicture);
        }
        EvalTrace inner = new EvalTrace();
        try {
            Object value = evaluateValue(processor, interior, inner);
            String lazyPicture = text.substring(0, open)
                    + (inner.picture == null ? interior.trim() : inner.picture)
                    + text.substring(close);
            trace.picture = lazyPicture;
            String evalText = text.substring(0, open) + toLiteral(value) + text.substring(close);
            return evalReduced(processor, evalText, trace, lazyPicture);
        } catch (RuntimeException ex) {
            trace.picture = text.substring(0, open)
                    + (inner.picture == null ? interior.trim() : inner.picture)
                    + text.substring(close);
            throw ex;
        }
    }

    private static Object evalReduced(
            MappingProcessor processor,
            String evalText,
            EvalTrace trace,
            String lazyPicture
    ) {
        try {
            Object value;
            if (containsOperator(evalText) || hasTernary(evalText) || endsWithBooleanMarker(evalText.trim())) {
                EvalTrace nested = new EvalTrace();
                value = evaluateValue(processor, evalText, nested);
            } else {
                EvalTrace nested = new EvalTrace();
                value = evalPlain(processor, evalText, nested);
            }
            trace.picture = lazyPicture;
            return value;
        } catch (RuntimeException ex) {
            trace.picture = lazyPicture;
            throw ex;
        }
    }

    private static Object evalPlain(
            MappingProcessor processor,
            String text,
            EvalTrace trace
    ) {
        String pasted;
        try {
            pasted = processor.resolveWholeText(text);
        } catch (RuntimeException ex) {
            if (trace.picture == null) {
                trace.picture = text.trim();
            }
            throw ex;
        }
        String shown = pasted == null ? "" : pasted.trim();
        trace.picture = shown;
        if ((containsOperator(shown) || hasTernary(shown) || endsWithBooleanMarker(shown))
                && !shown.equals(text.trim())) {
            EvalTrace inner = new EvalTrace();
            try {
                Object value = evaluateValue(processor, pasted, inner);
                trace.picture = inner.picture == null ? shown : inner.picture;
                return value;
            } catch (RuntimeException ex) {
                trace.picture = inner.picture == null ? shown : inner.picture;
                throw ex;
            }
        }
        return eval(pasted == null ? "" : pasted, processor);
    }

    private static Object evalRewritten(String rewritten, HashMap<String, Object> environment) {
        try {
            return eval(rewritten, environment);
        } catch (RuntimeException ex) {
            throw unwrapTokenFailure(ex);
        }
    }

    private static RuntimeException unwrapTokenFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null
                && current.getMessage() != null
                && current.getMessage().contains(TOKEN_PREFIX)
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        if (current instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        return new RuntimeException(failure);
    }

    private static boolean asBooleanMarker(Object value, String source) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        try {
            return (boolean) value;
        } catch (ClassCastException ex) {
            throw new RuntimeException(
                    "Failed to evaluate expression to boolean: '" + source + "'",
                    ex);
        }
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String reference(String open, String source, String close) {
        return (open == null ? "" : open) + source + (close == null ? "" : close);
    }

    private static String picture(EvalTrace trace, String source) {
        if (trace.picture == null) {
            return source == null ? "" : source.trim();
        }
        return trace.picture;
    }

    private static String toLiteral(Object value) {
        if (value == null) {
            return "nil";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        String text = String.valueOf(value);
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static boolean endsWithBooleanMarker(String text) {
        return text != null && !text.isEmpty() && text.charAt(text.length() - 1) == '?';
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
            int skip = skipOpaque(text, index);
            if (skip >= 0) {
                index = skip - 1;
                continue;
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
                    if (containsOperator(body) || hasTernary(body)) {
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
                    if (containsOperator(body) || hasTernary(body)) {
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
                    if (containsOperator(inner) || hasTernary(inner)) {
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

    private static boolean pasteAndBoolean(MappingProcessor processor, String side, Sides sides, int index) {
        String pasted = processor.resolveWholeText(side);
        String shown = pasted == null ? "" : pasted.trim();
        sides.mark(index, shown);
        if (pasted == null || pasted.isBlank() || isWholeUnresolvedReference(pasted)) {
            return false;
        }
        if ((containsOperator(pasted) || hasTernary(pasted) || endsWithBooleanMarker(pasted))
                && !pasted.trim().equals(side.trim())) {
            EvalTrace inner = new EvalTrace();
            try {
                Object value = evaluateValue(processor, pasted, inner);
                sides.mark(index, inner.picture == null ? shown : inner.picture);
                return isTruthy(value);
            } catch (RuntimeException ex) {
                sides.mark(index, inner.picture == null ? shown : inner.picture);
                throw ex;
            }
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

    private static int skipOpaque(String text, int index) {
        if (startsWith(text, index, "<{")) {
            int end = matchingClose(text, index, "<{", "}>");
            if (end >= 0) {
                return end;
            }
        }
        if (startsWith(text, index, "~[~{")) {
            int end = matchingClose(text, index, "~[~{", "}~]~");
            if (end >= 0) {
                return end;
            }
        }
        if (startsWith(text, index, "~[~")) {
            int end = matchingClose(text, index, "~[~", "~]~");
            if (end >= 0) {
                return end;
            }
        }
        if (isMapOpen(text, index)) {
            int end = mapClose(text, index);
            if (end >= 0) {
                return end;
            }
        }
        return -1;
    }

    private static boolean isMapOpen(String text, int index) {
        if (text.charAt(index) != '<' || startsWith(text, index, "<{")) {
            return false;
        }
        if (index + 1 >= text.length()) {
            return false;
        }
        char next = text.charAt(index + 1);
        return !Character.isWhitespace(next) && next != '=';
    }

    private static int mapClose(String text, int open) {
        for (int index = open + 1; index < text.length(); index++) {
            if (text.charAt(index) == '>' && !Character.isWhitespace(text.charAt(index - 1))) {
                return index + 1;
            }
        }
        return -1;
    }

    private static boolean hasTernary(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (splitTernary(text.trim()) != null) {
            return true;
        }
        char quote = 0;
        boolean escaped = false;
        Deque<Integer> opens = new ArrayDeque<>();
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
            int skip = skipOpaque(text, index);
            if (skip >= 0) {
                index = skip - 1;
                continue;
            }
            if (current == '(') {
                opens.push(index);
                continue;
            }
            if (current == ')' && !opens.isEmpty()) {
                int open = opens.pop();
                if (hasTernary(text.substring(open + 1, index))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int[] innermostTernaryGroup(String text) {
        int[] best = null;
        char quote = 0;
        boolean escaped = false;
        Deque<Integer> opens = new ArrayDeque<>();
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
            int skip = skipOpaque(text, index);
            if (skip >= 0) {
                index = skip - 1;
                continue;
            }
            if (current == '(') {
                opens.push(index);
                continue;
            }
            if (current == ')' && !opens.isEmpty()) {
                int open = opens.pop();
                String interior = text.substring(open + 1, index);
                if (hasTernary(interior)) {
                    int span = index + 1 - open;
                    if (best == null || span < best[2]) {
                        best = new int[]{open, index + 1, span};
                    }
                }
            }
        }
        return best == null ? null : new int[]{best[0], best[1]};
    }

    private static Ternary splitTernary(String text) {
        int question = findTopLevelQuestion(text);
        if (question < 0) {
            return null;
        }
        int colon = findMatchingColon(text, question + 1, false);
        if (colon < 0) {
            colon = findMatchingColon(text, question + 1, true);
        }
        if (colon < 0) {
            return null;
        }
        String condition = text.substring(0, question);
        String whenTrue = text.substring(question + 1, colon);
        String whenFalse = text.substring(colon + 1);
        if (hasDepth0Comma(condition) || hasDepth0Comma(whenTrue) || hasDepth0Comma(whenFalse)) {
            return null;
        }
        return new Ternary(condition, whenTrue, whenFalse);
    }

    private static int findTopLevelQuestion(String text) {
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
            int skip = skipOpaque(text, index);
            if (skip >= 0) {
                index = skip - 1;
                continue;
            }
            if (current == '(') {
                paren++;
                continue;
            }
            if (current == ')' && paren > 0) {
                paren--;
                continue;
            }
            if (paren == 0 && current == '?' && !followedByColon(text, index) && !isTrailingMarker(text, index)) {
                return index;
            }
        }
        return -1;
    }

    private static int findMatchingColon(String text, int start, boolean ignoreParens) {
        int paren = 0;
        int nested = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
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
            int skip = skipOpaque(text, index);
            if (skip >= 0) {
                index = skip - 1;
                continue;
            }
            if (!ignoreParens && current == '(') {
                paren++;
                continue;
            }
            if (!ignoreParens && current == ')' && paren > 0) {
                paren--;
                continue;
            }
            if (!ignoreParens && paren != 0) {
                continue;
            }
            if (current == '?' && !followedByColon(text, index)) {
                nested++;
                continue;
            }
            if (current == ':' && (index == 0 || text.charAt(index - 1) != '?')) {
                if (nested > 0) {
                    nested--;
                } else {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean hasDepth0Comma(String text) {
        int paren = 0;
        int nested = 0;
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
            int skip = skipOpaque(text, index);
            if (skip >= 0) {
                index = skip - 1;
                continue;
            }
            if (current == '(') {
                paren++;
                continue;
            }
            if (current == ')' && paren > 0) {
                paren--;
                continue;
            }
            if (paren != 0) {
                continue;
            }
            if (current == '?' && !followedByColon(text, index)) {
                nested++;
                continue;
            }
            if (current == ':' && nested > 0 && (index == 0 || text.charAt(index - 1) != '?')) {
                nested--;
                continue;
            }
            if (nested == 0 && current == ',') {
                return true;
            }
        }
        return false;
    }

    private static boolean followedByColon(String text, int question) {
        for (int index = question + 1; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                continue;
            }
            return current == ':';
        }
        return false;
    }

    private static boolean isTrailingMarker(String text, int question) {
        for (int index = question + 1; index < text.length(); index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFunctionCall(String text, int openParen) {
        int index = openParen - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        if (index < 0) {
            return false;
        }
        char current = text.charAt(index);
        return Character.isLetterOrDigit(current) || current == '_' || current == '.';
    }

    private static List<String> splitArgs(String text) {
        List<String> args = new ArrayList<>();
        int start = 0;
        int paren = 0;
        int nested = 0;
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
            int skip = skipOpaque(text, index);
            if (skip >= 0) {
                index = skip - 1;
                continue;
            }
            if (current == '(') {
                paren++;
                continue;
            }
            if (current == ')' && paren > 0) {
                paren--;
                continue;
            }
            if (paren != 0) {
                continue;
            }
            if (current == '?' && !followedByColon(text, index)) {
                nested++;
                continue;
            }
            if (current == ':' && nested > 0 && (index == 0 || text.charAt(index - 1) != '?')) {
                nested--;
                continue;
            }
            if (nested == 0 && current == ',') {
                args.add(text.substring(start, index));
                start = index + 1;
            }
        }
        args.add(text.substring(start));
        return args;
    }

    private static final class EvalTrace {
        private String picture;
    }

    private record Ternary(String condition, String whenTrue, String whenFalse) {
    }

    private static final class Sides {
        private final List<String> texts = new ArrayList<>();
        private final List<Boolean> booleanContext = new ArrayList<>();
        private final List<Boolean> ran = new ArrayList<>();
        private final List<String> displays = new ArrayList<>();

        private String add(String side) {
            return add(side, true);
        }

        private String add(String side, boolean asBoolean) {
            texts.add(side == null ? "" : side.trim());
            booleanContext.add(asBoolean);
            ran.add(false);
            displays.add(null);
            return TOKEN_PREFIX + (texts.size() - 1);
        }

        private void mark(int index, String shown) {
            ran.set(index, true);
            displays.set(index, shown == null ? "" : shown);
        }

        private String picture(String rewritten) {
            String shown = rewritten;
            for (int index = texts.size() - 1; index >= 0; index--) {
                String display = ran.get(index) ? displays.get(index) : texts.get(index);
                if (display == null) {
                    display = "";
                }
                String original = texts.get(index);
                if (ran.get(index)
                        && original.startsWith("(")
                        && original.endsWith(")")
                        && unwrap(original, "(", ")") != null
                        && !display.startsWith("(")) {
                    display = "(" + display + ")";
                }
                shown = shown.replace(TOKEN_PREFIX + index, display);
            }
            return shown.trim();
        }

        private HashMap<String, Object> environment(MappingProcessor processor) {
            return new HashMap<>() {
                private final Set<String> reading = new HashSet<>();

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
                    if (!reading.add(name)) {
                        throw new IllegalStateException("Cyclic boolean side " + name);
                    }
                    try {
                        return read(processor, index);
                    } finally {
                        reading.remove(name);
                    }
                }
            };
        }

        private Object read(MappingProcessor processor, int index) {
            String side = texts.get(index) == null ? "" : texts.get(index).trim();
            boolean asBoolean = booleanContext.get(index);
            if (side.isEmpty()) {
                mark(index, "");
                return false;
            }
            String expression = unwrap(side, "<{", "}>");
            if (expression == null) {
                expression = unwrap(side, "~[~{", "}~]~");
            }
            if (expression != null) {
                try {
                    String pasted = processor.resolveWholeText(side);
                    String shown = pasted == null ? "" : pasted.trim();
                    mark(index, shown);
                    if (asBoolean) {
                        if (pasted == null || pasted.isBlank() || isWholeUnresolvedReference(pasted)) {
                            return false;
                        }
                        return isTruthy(pasted);
                    }
                    return pasted == null ? "" : pasted;
                } catch (RuntimeException ex) {
                    if (!ran.get(index)) {
                        mark(index, side);
                    }
                    throw ex;
                }
            }
            if (containsOperator(side) || hasTernary(side) || endsWithBooleanMarker(side)) {
                EvalTrace inner = new EvalTrace();
                try {
                    Object value = evaluateValue(processor, side, inner);
                    mark(index, inner.picture == null ? side : inner.picture);
                    return asBoolean ? isTruthy(value) : value;
                } catch (RuntimeException ex) {
                    mark(index, inner.picture == null ? side : inner.picture);
                    throw ex;
                }
            }
            if (asBoolean) {
                return pasteAndBoolean(processor, side, this, index);
            }
            String pasted = processor.resolveWholeText(side);
            String shown = pasted == null ? "" : pasted.trim();
            mark(index, shown);
            if ((containsOperator(shown) || hasTernary(shown) || endsWithBooleanMarker(shown))
                    && !shown.equals(side)) {
                EvalTrace inner = new EvalTrace();
                try {
                    Object value = evaluateValue(processor, pasted, inner);
                    mark(index, inner.picture == null ? shown : inner.picture);
                    return value;
                } catch (RuntimeException ex) {
                    mark(index, inner.picture == null ? shown : inner.picture);
                    throw ex;
                }
            }
            return eval(pasted == null ? "" : pasted, processor);
        }
    }
}
