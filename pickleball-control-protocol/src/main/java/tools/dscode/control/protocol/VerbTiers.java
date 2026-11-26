package tools.dscode.control.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one tier registry for CLI verbs and MCP tools. {@code human} is perception through the human view (snap, find,
 * screenshot, capture, explain), input a person could give (act click/type/press/hover/select through the real
 * hit-test, wait), read-only chat-read, doctor, and help. Everything else is {@code control}: DOM and JS, injection,
 * ui-apply, ask, guide, highlight, CDP, full maps, form-fill, attach and upload, navigate, start and stop.
 * Help, the inventory, and MCP descriptions read this class; a gate test fails when a verb has no tier.
 */
public final class VerbTiers {
    public static final String HUMAN = "human";
    public static final String CONTROL = "control";

    private static final Set<String> HUMAN_VERBS = Set.of(
            "act", "capture", "chat-read", "doctor", "explain", "find", "help", "screenshot", "snap", "wait",
            "wait-for", "input", "aria-snap"
    );

    private static final Set<String> CONTROL_VERBS = Set.of(
            "answer-wait", "arrow", "ask", "ask-and-wait", "attach", "border", "bridge-health", "call", "wait-any",
            "capture-scenario", "capture-steps", "chat", "chat-history", "chat-poll", "chat-send", "chat-turn", "chat_wait",
            "cdp", "checklist", "close-window", "confirm", "controller-ping", "controller-start", "controller-status",
            "controller-stop", "diagnostic-run", "discover", "discover-hint", "entity-lookup", "eval", "events",
            "example", "execute-step", "execute-steps", "export-guidance", "find-element", "finish", "form-fill",
            "frame", "from-here", "gc-runs", "gc-versions", "guide", "guide-back", "guide-done", "guide-next",
            "highlight", "hint", "history", "history-query", "history-summary", "html-cleanup", "html-insert",
            "html-remove", "html-replace", "html-write", "inbox", "inject", "insert-step", "inspect-page", "isolate", "kill",
            "label", "launch", "live-check", "magnifier", "mcp", "navigate", "note", "observe", "open-run",
            "open-scenario", "open-window", "overlay", "overlay-poll", "overlay-remove", "panel-close", "panel-open",
            "panel-state", "pause", "pin", "plan", "play", "point", "popup-close", "popup-open", "popup-status", "post", "progress", "pw",
            "presence", "query", "record", "ref", "refresh", "relaunch-with-debug", "resolve-runvars", "save", "selftest", "session",
            "session-hold", "session-info", "session-list", "session-mode", "session-release", "session-start", "setup",
            "session-sync", "short-log", "show-run", "spine", "spotlight", "start", "status", "step-badge", "stop", "sync",
            "tabs", "toast", "ui", "ui-apply", "ui-catalog", "ui-poll", "ui-verify", "unarrow", "unborder",
            "unhighlight", "unlabel", "unmagnifier", "unspotlight", "unstep-badge", "update-step", "upload",
            "use-version", "validate", "watch", "windows", "worker-check", "worker-restart", "worker-start",
            "worker-stop", "workspace-edit", "workspace-seed", "workspace-show", "browser-events", "errors", "logpoint", "net", "net-body", "har", "net-route", "net-unroute", "net-block", "net-headers", "net-auth", "ws-route", "net-cache", "security", "csp", "emulate", "clock", "permissions", "context-new", "context-list", "context-close", "tab-new", "tab-activate", "page-history", "print", "storage", "storage-state", "downloads", "dialogs", "drop", "webauthn", "extension", "webmcp", "perf", "memory", "audits", "sw", "screencast", "share-browser", "optional-install", "optional-uninstall", "optional-list", "audit", "bridge"
    );

    /** act kinds a human-locked session accepts. */
    public static final Set<String> HUMAN_ACT_KINDS = Set.of("click", "type", "press", "hover", "select", "scroll");

    private VerbTiers() {
    }

    /** human, control, or null when the verb is not registered. */
    public static String tier(String verb) {
        if (verb == null) return null;
        String key = verb.trim();
        if (HUMAN_VERBS.contains(key)) return HUMAN;
        if (CONTROL_VERBS.contains(key)) return CONTROL;
        return null;
    }

    public static Set<String> verbs() {
        Set<String> all = new TreeSet<>(HUMAN_VERBS);
        all.addAll(CONTROL_VERBS);
        return Collections.unmodifiableSet(all);
    }

    public static Map<String, String> table() {
        Map<String, String> table = new LinkedHashMap<>();
        for (String verb : verbs()) table.put(verb, tier(verb));
        return table;
    }

    /** The tier of an MCP tool, from the verb it forwards; tools without a human verb are control. */
    public static String toolTier(String tool) {
        if (tool == null) return CONTROL;
        String verb = tool.startsWith("workbench_") ? tool.substring("workbench_".length()).replace('_', '-') : tool;
        if ("browser-screenshot".equals(verb)) return HUMAN;
        return HUMAN.equals(tier(verb)) ? HUMAN : CONTROL;
    }
}
