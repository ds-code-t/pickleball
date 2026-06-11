package tools.dscode.common.util.debug;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DebugUtils {

    public static final List<String> prefixes = new CopyOnWriteArrayList<>();
    public static final List<String> substrings = new CopyOnWriteArrayList<>();

    public static final List<String> debugFlags = new CopyOnWriteArrayList<>();

    public static volatile boolean disableBaseElement = false;

    static {
//        prefixes.add("@@");
//        substrings.add("##MatchNode:");
        substrings.add("##xpath:");
//        substrings.add("##Glue");
//        substrings.add("##SpecificityScore");
//        substrings.add("##");
    }

    public static boolean parseDebugString(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }

        List<String> flags = tags.stream()
                .filter(Objects::nonNull)
                .filter(t -> t.startsWith("DEBUG"))
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(str -> !str.isBlank())
                .map(String::toLowerCase)
                .toList();

        boolean startStep = flags.contains("debug");

        if (flags.isEmpty()) {
            return false;
        }

        debugFlags.addAll(flags);
        disableBaseElement = debugFlags.contains("nobase");

        flags.forEach(flag -> {
            if (flag.startsWith("##") && !substrings.contains(flag)) {
                substrings.add(flag);
            }
        });

        return startStep;
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

        String trimmed = message.strip().toLowerCase();
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
