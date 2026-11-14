package dev.domengine.cli;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flags each verb actually reads. A flag is listed only when the verb uses it.
 * {@code capabilities.verb_flags} is this map.
 */
public final class VerbFlags {
    private static final List<String> SELECTORS = List.of(
            "session", "session-dir", "css", "xpath", "text", "pipeline", "selector", "expr", "pw", "by",
            "within", "pierce", "strict"
    );

    public static final Map<String, List<String>> VERBS = verbs();

    private VerbFlags() {
    }

    public static List<String> of(String verb) {
        List<String> flags = VERBS.get(verb);
        return flags == null ? List.of() : flags;
    }

    public static String unrecognized(String verb, Map<String, String> flags) {
        if (flags == null) return null;
        Set<String> allowed = new LinkedHashSet<>(of(verb));
        for (String name : flags.keySet()) {
            if (name.startsWith("__")) continue;
            if (!allowed.contains(name)) return name;
        }
        return null;
    }

    private static Map<String, List<String>> verbs() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("query", join(SELECTORS, List.of(
                "file", "nesting", "nth", "every", "count-only", "geometry", "point", "viewport", "exposure", "explain",
                "filter", "limit", "wait-for", "timeout-ms", "poll-ms", "stability-ms", "all-frames", "frame", "shadow",
                "rank", "doc-version"
        )));
        List<String> identity = List.of("node-id", "refind", "frame", "shadow");
        List<String> click = join(SELECTORS, identity);
        map.put("click", click);
        map.put("dblclick", click);
        map.put("contextclick", click);
        map.put("submit", click);
        map.put("hover", join(SELECTORS, List.of("node-id", "refind", "frame", "shadow", "force-hover")));
        map.put("type", join(SELECTORS, List.of("append", "submit", "node-id", "refind", "frame", "shadow")));
        map.put("scroll", join(SELECTORS, List.of("dx", "dy", "node-id", "refind", "frame", "shadow")));
        map.put("key", join(SELECTORS, List.of("key", "node-id", "refind", "frame", "shadow")));
        map.put("eval", join(SELECTORS, List.of("source", "node-id", "node-ids", "refind", "frame", "shadow")));
        map.put("drag", List.of("session", "session-dir", "source", "target"));
        map.put("premap", List.of(
                "file", "screenshot", "out-dir",
                "box-max-area", "box-max-width", "box-max-height",
                "one-match-margin", "affinity-weights"
        ));
        map.put("snapshot", List.of(
                "session", "session-dir", "cdp", "i-accept-local-attach", "out-dir", "full-page",
                "box-max-area", "box-max-width", "box-max-height", "one-match-margin", "affinity-weights"
        ));
        return Map.copyOf(map);
    }

    private static List<String> join(List<String> left, List<String> right) {
        LinkedHashSet<String> both = new LinkedHashSet<>(left);
        both.addAll(right);
        return List.copyOf(both);
    }
}
