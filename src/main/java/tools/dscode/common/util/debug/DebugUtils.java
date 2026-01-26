package tools.dscode.common.util.debug;

import java.util.ArrayList;
import java.util.List;

public final class DebugUtils {

    public static final List<String> prefixes = new ArrayList<>();
    public static final List<String> substrings = new ArrayList<>();

    static {
//        prefixes.add("@@");
//        substrings.add("##MatchNode:");
        substrings.add("##XPath:");
        substrings.add("##Glue");
//        substrings.add("##");
    }

    private DebugUtils() {
        // utility class
    }

    public static void printDebug(String message) {
        if (matches(message)) {
            System.out.println(message);
        }
    }

    /** Returns true if the message matches any configured prefix or substring rule. */
    public static boolean matches(String message) {
        if (message == null) return false;

        String trimmed = message.strip();
        if (trimmed.isEmpty()) return false;

        for (String p : prefixes) {
            if (p != null && !p.isBlank() && trimmed.startsWith(p)) return true;
        }
        for (String s : substrings) {
            if (s != null && !s.isBlank() && trimmed.contains(s)) return true;
        }
        return false;
    }

    /**
     * Executes {@code logic} only when {@code message} matches the same rules as {@link #printDebug}.
     * Lambdas passed here can capture local variables and members from the call site.
     */
    public static void onMatch(String message, Runnable logic) {
        if (logic == null) return;
        if (matches(message)) {
            logic.run();
        }
    }

    /**
     * Variant that also passes the original message into the logic.
     * Useful if you want to avoid capturing the message externally.
     */
    public static void onMatch(String message, java.util.function.Consumer<String> logic) {
        if (logic == null) return;
        if (matches(message)) {
            logic.accept(message);
        }
    }
}
