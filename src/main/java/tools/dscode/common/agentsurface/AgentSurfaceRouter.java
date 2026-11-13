package tools.dscode.common.agentsurface;

import tools.dscode.common.agentcontrol.AgentControl;
import tools.dscode.common.agentcontrol.PopupSurfaceBridge;
import tools.dscode.control.protocol.AgentSurfaceEnd;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Routes an existing agent op to the page, the popup, or the native highlight. */
public final class AgentSurfaceRouter {
    private static final Set<String> SURFACE = Set.of(
            "chat-send", "overlay", "overlay-poll", "overlay-remove",
            "html-write", "html-replace", "html-insert", "html-remove", "html-cleanup",
            "highlight", "unhighlight", "popup-open", "popup-status", "popup-close"
    );
    private static final CopyOnWriteArrayList<HostRef> OPENED = new CopyOnWriteArrayList<>();

    static {
        AgentSurfaceEnd.register(AgentSurfaceRouter::onScenarioEnd);
        Runtime.getRuntime().addShutdownHook(new Thread(AgentSurfaceRouter::onShutdown, "popup-shutdown"));
    }

    private AgentSurfaceRouter() {
    }

    public static Optional<AgentControl.Result> route(Path project, String runId, String op, Map<String, String> args, String agentId) {
        Map<String, String> values = args == null ? Map.of() : args;
        String name = op == null ? "" : op;
        if (!SURFACE.contains(name)) return Optional.empty();
        String mode = PopupConfig.mode();
        if ("off".equals(mode)) {
            if (name.startsWith("popup-")) return Optional.of(closed(name, "off"));
            return Optional.empty();
        }
        Decision decision = decide(project, runId, name, values, mode);
        PopupLog.log(project, runId, "route", runId, name + " target=" + decision.target + " reason=" + decision.reason);
        if ("page".equals(decision.target)) return Optional.empty();
        try {
            if ("native".equals(decision.target)) return Optional.of(nativeHighlight(project, runId, name, values));
            return Optional.of(popup(project, runId, name, values, agentId, decision));
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception failure) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("op", name);
            body.put("target", decision.target);
            body.put("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            return Optional.of(new AgentControl.Result(1, body));
        }
    }

    static void onScenarioEnd() {
        for (HostRef host : OPENED) {
            try {
                PopupLedger.append(host.project, host.runId, "cleanup", "", "", "", "", "", "");
            } catch (IOException ignored) {
                // Scenario cleanup must not block the next host.
            }
        }
    }

    static void onShutdown() {
        for (HostRef host : OPENED) {
            try {
                PopupLedger.append(host.project, host.runId, "close", "", "", "", "", "", "");
            } catch (IOException ignored) {
                // The JVM is leaving. A missed close is the same as a dead owner.
            }
        }
    }

    private static AgentControl.Result popup(Path project, String runId, String op, Map<String, String> args, String agentId, Decision decision) throws Exception {
        if ("popup-status".equals(op)) {
            Map<String, Object> status = PopupStatus.read(project, runId);
            status.put("mode", PopupConfig.mode());
            status.put("target", "popup");
            return new AgentControl.Result(0, status);
        }
        if ("popup-close".equals(op)) {
            PopupLedger.append(project, runId, "close", "", "", "", "", "", "");
            Map<String, Object> body = ok("Popup close recorded.");
            body.put("op", op);
            body.put("target", "popup");
            return new AgentControl.Result(0, body);
        }
        if ("popup-open".equals(op)) {
            Map<String, Object> opened = openHost(project, runId);
            opened.put("op", op);
            opened.put("target", "closed".equals(opened.get("state")) ? "none" : "popup");
            if ("closed".equals(opened.get("state"))) opened.put("fallback", "no-chromium");
            return new AgentControl.Result(0, opened);
        }
        if ("chat-send".equals(op)) {
            Map<String, Object> message = PopupSurfaceBridge.sendChat(project, runId, args.get("channel"), args.get("source"), agentId, args.get("text"));
            String text = args.get("text") == null ? "" : args.get("text");
            PopupLedger.append(project, runId, "chat", "chat", "", "", text, "", "");
            track(project, runId);
            maybeOpen(project, runId, decision);
            Map<String, Object> body = ok("Chat message stored for this run channel.");
            body.put("message", message);
            body.put("target", "popup");
            return new AgentControl.Result(0, body);
        }
        if ("overlay-poll".equals(op)) {
            List<Map<String, Object>> events = PopupSurfaceBridge.pollInput(project, runId);
            Map<String, Object> body = ok("Forwarded overlay clicks and form inputs.");
            body.put("count", events.size());
            body.put("events", events);
            body.put("target", "popup");
            return new AgentControl.Result(0, body);
        }
        String kind = op.startsWith("overlay") ? "overlay" : "html";
        String pane = pane(args, kind);
        String ledgerOp = switch (op) {
            case "html-replace" -> "replace";
            case "html-insert" -> "insert";
            case "html-remove", "overlay-remove" -> "remove";
            case "html-cleanup" -> "cleanup";
            default -> "append";
        };
        String raw = args.get("text");
        String labeled = "";
        boolean stripped = false;
        if (raw != null && !ledgerOp.equals("remove") && !ledgerOp.equals("cleanup")) {
            String neutral = PopupSurfaceBridge.neutralize(raw);
            stripped = !raw.equals(neutral);
            labeled = PopupSurfaceBridge.label(neutral, kind);
        }
        PopupLedger.Row row = PopupLedger.append(
                project, runId, ledgerOp, pane, text(args.get("handle"), args.get("id")), text(args.get("where")),
                labeled, "", PopupSurfaceBridge.sentence(kind)
        );
        track(project, runId);
        Map<String, Object> opened = maybeOpen(project, runId, decision);
        Map<String, Object> body = ok(PopupSurfaceBridge.sentence(kind));
        body.put("ok", true);
        body.put("id", Long.toString(row.seq()));
        body.put("handle", text(args.get("handle")));
        body.put("kind", kind);
        body.put("pane", pane);
        body.put("sha256", sha(labeled));
        body.put("summary", labeled.length() > 80 ? labeled.substring(0, 80) : labeled);
        body.put("guidance", raw == null ? "" : PopupSurfaceBridge.neutralize(raw));
        body.put("scriptStripped", stripped);
        body.put("sentence", PopupSurfaceBridge.sentence(kind));
        body.put("target", opened.get("fallback") == null ? "popup" : "none");
        if (opened.get("fallback") != null) body.put("fallback", opened.get("fallback"));
        return new AgentControl.Result(0, body);
    }

    private static AgentControl.Result nativeHighlight(Path project, String runId, String op, Map<String, String> args) throws Exception {
        if ("unhighlight".equals(op)) {
            String id = text(args.get("id"), args.get("handle"), "native");
            Map<String, Object> body = NativeHighlight.hide(id);
            body.put("ok", true);
            body.put("target", "native");
            body.put("op", op);
            return new AgentControl.Result(0, body);
        }
        Map<String, Object> body = NativeHighlight.show(project, runId, args);
        body.put("ok", true);
        body.put("target", "native");
        body.put("op", op);
        return new AgentControl.Result(0, body);
    }

    private static Map<String, Object> maybeOpen(Path project, String runId, Decision decision) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!decision.openHost) return body;
        Map<String, Object> opened = openHost(project, runId);
        if ("closed".equals(String.valueOf(opened.get("state")))) body.put("fallback", "no-chromium");
        return body;
    }

    private static Map<String, Object> openHost(Path project, String runId) throws Exception {
        track(project, runId);
        return PopupHost.open(project, runId);
    }

    private static void track(Path project, String runId) {
        if (project == null || runId == null) return;
        HostRef ref = new HostRef(project, runId);
        if (!OPENED.contains(ref)) OPENED.add(ref);
    }

    private static Decision decide(Path project, String runId, String op, Map<String, String> args, String mode) {
        if (op.startsWith("popup-")) return new Decision("popup", "popup-verb", true);
        String target = args.get("target");
        if (target == null || target.isBlank() || "auto".equalsIgnoreCase(target)) target = "auto";
        else target = target.trim().toLowerCase();
        if ("page".equals(target)) return new Decision("page", "explicit-page", false);
        if ("popup".equals(target)) return new Decision("popup", "explicit-popup", true);
        if ("native".equals(target)) return new Decision(highlightOp(op) ? "native" : "page", "explicit-native", false);
        boolean desktop = desktop(project, runId, args);
        boolean filePage = present(args.get("html")) || present(args.get("page"));
        if (filePage && "auto".equals(target)) return new Decision("page", "explicit-html-file", false);
        if (!desktop && "auto".equals(mode)) return new Decision("page", "browser-auto", false);
        if (!desktop && "always".equals(mode)) {
            if ("chat-send".equals(op)) return new Decision("popup", "always-chat", openFor(mode, args));
            if (highlightOp(op)) return new Decision("page", "always-highlight-stays-on-page", false);
            if (present(args.get("handle"))) return new Decision("page", "handle-anchored", false);
            return new Decision("popup", "always-unanchored", true);
        }
        if ("chat-send".equals(op) || "overlay-poll".equals(op) || op.startsWith("html-") || op.startsWith("overlay")) {
            return new Decision("popup", "desktop", openFor(mode, args));
        }
        if (highlightOp(op) && (bounds(args) || present(args.get("query")))) {
            return new Decision("native", "desktop-bounds", false);
        }
        return new Decision("page", "page-element", false);
    }

    private static boolean openFor(String mode, Map<String, String> args) {
        if ("always".equals(mode)) return true;
        if ("popup".equalsIgnoreCase(text(args.get("target")))) return true;
        String session = session(args);
        return "desktop".equalsIgnoreCase(session);
    }

    private static boolean desktop(Path project, String runId, Map<String, String> args) {
        String session = session(args);
        if ("desktop".equalsIgnoreCase(session)) return true;
        if ("browser".equalsIgnoreCase(session)) return false;
        return !PopupSurfaceBridge.hasBrowserPage(project, runId);
    }

    private static String session(Map<String, String> args) {
        String fromArgs = args.get("pkb_agent_session");
        if (present(fromArgs)) return fromArgs.trim();
        String property = System.getProperty("pkb_agent_session");
        if (present(property)) return property.trim();
        String env = System.getenv("PKB_AGENT_SESSION");
        return present(env) ? env.trim() : "";
    }

    private static boolean bounds(Map<String, String> args) {
        return present(args.get("x")) && present(args.get("y")) && present(args.get("width")) && present(args.get("height"));
    }

    private static boolean highlightOp(String op) {
        return "highlight".equals(op) || "unhighlight".equals(op);
    }

    private static String pane(Map<String, String> args, String kind) {
        if (present(args.get("pane"))) return args.get("pane").trim().toLowerCase();
        String raw = args.get("text");
        if (raw != null && raw.trim().toLowerCase().startsWith("<svg")) return "diagram";
        return "overlay".equals(kind) ? "instructions" : "instructions";
    }

    private static AgentControl.Result closed(String op, String reason) {
        Map<String, Object> body = ok("Popup is off.");
        body.put("op", op);
        body.put("target", "page");
        body.put("fallback", reason);
        body.put("opened", false);
        return new AgentControl.Result(0, body);
    }

    private static Map<String, Object> ok(String sentence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sentence", sentence);
        return body;
    }

    private static String sha(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            return "";
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (present(value)) return value;
        }
        return "";
    }

    private record Decision(String target, String reason, boolean openHost) {
    }

    private record HostRef(Path project, String runId) {
    }
}
