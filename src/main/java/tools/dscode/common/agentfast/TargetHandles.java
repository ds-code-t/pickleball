package tools.dscode.common.agentfast;

/**
 * Turns a short id or {@code ref:} into the CSS selector {@code act} and {@code ui-apply} already use.
 * A value that is not one of those is left for the caller.
 */
public final class TargetHandles {
    private TargetHandles() {
    }

    /** CSS selector for a short id or {@code ref:}. Null when this token is not one, or it has no selector. */
    public static String selector(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String token = raw.trim();
        boolean ref = token.startsWith("ref:");
        boolean shortId = token.matches("s\\d+\\.h\\d+");
        if (!ref && !shortId) return null;
        return TargetVerb.anchorSelector(token);
    }
}
