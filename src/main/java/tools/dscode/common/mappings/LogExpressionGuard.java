package tools.dscode.common.mappings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Log steps keep invalid expressions as the original literal.
 * Every other step still fails, and a nested step started from a log
 * line is not treated as log text.
 */
final class LogExpressionGuard {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> SUSPENDED = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Map<String, String>> LITERALS =
            ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Integer> NEXT_ID = ThreadLocal.withInitial(() -> 0);

    private static final String TOKEN_OPEN = "\uE010";
    private static final String TOKEN_CLOSE = "\uE011";

    private static final Pattern LOG_TEXT = Pattern.compile(
            "^(?:\\uFEFF|\\u200B|\\u00A0|\\u200C)*\\s*(?:TRACE|DEBUG|INFO|WARN|ERROR|FAIL):",
            Pattern.DOTALL);

    private LogExpressionGuard() {
    }

    static boolean enter(String input) {
        if (SUSPENDED.get() > 0 || input == null || !LOG_TEXT.matcher(input).find()) {
            return false;
        }
        DEPTH.set(DEPTH.get() + 1);
        return true;
    }

    static void exit(boolean entered) {
        if (!entered) {
            return;
        }
        DEPTH.set(Math.max(0, DEPTH.get() - 1));
        if (DEPTH.get() == 0) {
            LITERALS.get().clear();
            NEXT_ID.set(0);
        }
    }

    static String finish(String value, boolean entered) {
        if (entered && value != null) {
            value = restore(value);
        }
        exit(entered);
        return value;
    }

    static Object finish(Object value, boolean entered) {
        if (entered && value instanceof String text) {
            value = restore(text);
        }
        exit(entered);
        return value;
    }

    static boolean active() {
        return SUSPENDED.get() == 0 && DEPTH.get() > 0;
    }

    static void suspend() {
        SUSPENDED.set(SUSPENDED.get() + 1);
    }

    static void resume() {
        SUSPENDED.set(Math.max(0, SUSPENDED.get() - 1));
    }

    static String protect(String literal) {
        String token = TOKEN_OPEN + NEXT_ID.get() + TOKEN_CLOSE;
        NEXT_ID.set(NEXT_ID.get() + 1);
        LITERALS.get().put(token, literal == null ? "" : literal);
        return token;
    }

    private static String restore(String input) {
        if (input == null || !input.contains(TOKEN_OPEN)) {
            return input;
        }
        String output = input;
        for (Map.Entry<String, String> entry : LITERALS.get().entrySet()) {
            output = output.replace(entry.getKey(), entry.getValue());
        }
        return output;
    }
}
