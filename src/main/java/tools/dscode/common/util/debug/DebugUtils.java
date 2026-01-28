package tools.dscode.common.util.debug;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class DebugUtils {

    public static final List<String> prefixes = new ArrayList<>();
    public static final List<String> substrings = new ArrayList<>();


    public static List<String> debugFlags;

    public static boolean disableBaseElement = false;

    static {
//        prefixes.add("@@");
//        substrings.add("##MatchNode:");
        substrings.add("##XPath:");
//        substrings.add("##Glue");
//        substrings.add("##SpecificityScore");
//        substrings.add("##");
    }


    public static boolean parseDebugString(List<String> stepTags) {
        debugFlags =
                stepTags.stream()
                        .filter(Objects::nonNull)
                        .flatMap(s -> Arrays.stream(s.split("\\s+")))
                        .filter(str -> !str.isBlank())
                        .toList();
        if(debugFlags.isEmpty())
            return false;
        disableBaseElement = debugFlags.contains("noBase");
        debugFlags.forEach(flag ->{
            if(flag.startsWith("##"))
                substrings.add(flag);
        });
        return true;
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
