package tools.dscode.common.agentfast;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared body for snap, find, act, wait, watch, explain, and capture. */
public final class TargetVerb {
    private static final Set<String> EVENT_CONDITIONS = Set.of(
            "mutation", "network", "load", "navigation", "mask"
    );

    private TargetVerb() {
    }

    public static Map<String, Object> run(Path project, String runId, String op, Map<String, String> args) {
        Map<String, String> values = args == null ? Map.of() : args;
        TargetSelector.Parsed target = TargetSelector.parse(values.get("target"));
        String chain = chain(op);
        String mode = blank(values.get("mode"));
        if (("wait".equals(op) || "watch".equals(op)) && mode.isEmpty()) {
            mode = EVENT_CONDITIONS.contains(blank(values.get("condition"))) ? "event" : "poll";
        }
        String used = switch (target.kind()) {
            case "desktop" -> "desktop";
            case "ref" -> "ref";
            default -> "none";
        };
        boolean applied = false;
        FallbackLog.note(project, runId, chain, applied ? "applied" : "no-live-" + target.kind());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("op", op == null ? "" : op);
        body.put("applied", applied);
        body.put("used", used);
        body.put("kind", target.kind());
        body.put("mode", mode);
        body.put("chain", chain);
        body.put("condition", blank(values.get("condition")));
        body.put("session", target.session());
        body.put("cdp", target.cdp());
        body.put("tab", target.tab());
        body.put("frame", target.frame());
        body.put("pid", target.pid());
        body.put("app", target.app());
        body.put("window", target.window());
        body.put("docVersion", target.docVersion());
        body.put("handle", target.handle());
        body.put("auto", target.auto());
        return body;
    }

    private static String chain(String op) {
        return switch (op == null ? "" : op) {
            case "act" -> "act";
            case "wait", "watch" -> "wait";
            case "snap", "capture" -> "snapshot";
            default -> "locate";
        };
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
