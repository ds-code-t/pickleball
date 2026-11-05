package tools.dscode.common.composite;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which composite commands one pointer or form action becomes.
 * A listed verb is one command. A missing verb keeps the 2.4 mapping.
 * Offset drag stays click, scroll, click: the drag verb takes two locators and no dx/dy.
 */
public final class VerbPlan {
    /** What a 2.4.0 binary reports, and the default when capabilities cannot be read. */
    public static final List<String> ACTIONS_2_4_0 = List.of("click", "type", "scroll");

    private VerbPlan() {
    }

    public static List<String> submit(Set<String> actions) {
        return listed(actions, "submit") ? List.of("submit") : List.of("click");
    }

    public static List<String> doubleClick(Set<String> actions) {
        return listed(actions, "dblclick") ? List.of("dblclick") : List.of("click", "click");
    }

    public static List<String> contextClick(Set<String> actions) {
        return listed(actions, "contextclick") ? List.of("contextclick") : List.of("click");
    }

    /**
     * Element-to-element with a zero offset is one {@code drag} when that verb is listed.
     * Anything else is the old click, then scroll, then click (the last click only when a target exists).
     */
    public static List<String> drag(Set<String> actions, boolean hasTarget, int xOffset, int yOffset) {
        if (hasTarget && xOffset == 0 && yOffset == 0 && listed(actions, "drag")) return List.of("drag");
        if (hasTarget) return List.of("click", "scroll", "click");
        return List.of("click", "scroll");
    }

    public static boolean listed(Set<String> actions, String verb) {
        if (actions == null || verb == null) return false;
        return actions.contains(verb.toLowerCase(Locale.ROOT));
    }
}
