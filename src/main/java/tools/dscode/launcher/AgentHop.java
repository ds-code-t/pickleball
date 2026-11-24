package tools.dscode.launcher;

import java.util.Set;

/**
 * Agent verbs run inside the long-lived controller. Discover, guidance, and
 * the session HTTP client stay in the short-lived launcher.
 */
final class AgentHop {
    static final String INSIDE = "pkb.controller.inside";
    private static final Set<String> HOP = Set.of(
            "observe", "launch", "windows", "tabs",
            "chat-send", "chat-history", "chat-poll", "chat_wait", "chat-turn", "chat-read",
            "highlight", "unhighlight",
            "overlay", "overlay-poll", "overlay-remove",
            "label", "unlabel", "border", "unborder", "arrow", "unarrow",
            "step-badge", "unstep-badge", "magnifier", "unmagnifier", "spotlight", "unspotlight",
            "html-write", "html-replace", "html-insert", "html-remove", "html-cleanup",
            "popup-open", "popup-status", "popup-close",
            "panel-open", "panel-close",
            "guide", "guide-next", "guide-back", "guide-done", "ask",
            "ui-apply", "ui-poll", "ui-catalog", "ui-verify",
            "capture-scenario", "capture-steps", "record", "screenshot", "selftest",
            "snap", "find", "act", "form-fill", "wait", "wait-for", "watch", "explain", "capture", "attach", "eval", "frame", "upload",
            "ask-and-wait", "answer-wait", "pin", "toast", "checklist", "ui", "ref",
            "start", "call", "execute-steps", "doctor"
    );

    private AgentHop() {
    }

    static boolean shouldHop(String command) {
        if (command == null || command.isBlank()) return false;
        if (Boolean.getBoolean(INSIDE)) return false;
        if ("1".equals(System.getenv("PKB_NO_AGENT_HOP"))) return false;
        return HOP.contains(command);
    }

    /** Bare {@code ui} stays the Swing controller. {@code ui --ops} hops with the other agent verbs.
     *  {@code live-stop} / {@code start --stop} stays in this process so it can stop the controller. */
    static boolean shouldHop(String command, String[] args) {
        if (!shouldHop(command)) return false;
        if ("start".equals(command) && stops(args)) return false;
        if (!"ui".equals(command)) return true;
        if (args == null) return false;
        for (String token : args) {
            if ("--ops".equals(token) || (token != null && token.startsWith("--ops="))) return true;
        }
        return false;
    }

    private static boolean stops(String[] args) {
        if (args == null || args.length == 0) return false;
        if ("live-stop".equals(args[0])) return true;
        for (String token : args) {
            if ("--stop".equals(token)) return true;
        }
        return false;
    }
}
