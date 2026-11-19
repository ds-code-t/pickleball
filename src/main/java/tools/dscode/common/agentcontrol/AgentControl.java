package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.agentfast.ReplyFields;
import tools.dscode.common.agentfast.ScreenshotReply;
import tools.dscode.common.agentfast.SelftestUi;
import tools.dscode.common.agentsurface.AgentSurfaceRouter;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.driver.DriverConstruction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One controller for observe, launch, windows, chat, highlight, overlay, HTML,
 * and capture. CLI, the control bridge, MCP, and the Workbench only forward this.
 */
public final class AgentControl {
    public record Result(int exit, Map<String, Object> body) {
    }

    private AgentControl() {
    }

    public static void cleanup() {
        AgentDriverHooks.releaseUnowned();
        AgentDriverHooks.quit();
    }

    /** Geometry already resolved for a ranked handle. Overlay placement looks this up. */
    public static void rememberGeometry(String handle, double x, double y) {
        PageSurfaces.rememberGeometry(handle, x, y);
    }

    /** Query-time owner. Not inferred again from global focus when the point is stored. */
    public static void rememberGeometry(String handle, double x, double y, WebDriver owner, String tab) {
        PageSurfaces.rememberGeometry(handle, x, y, owner, tab);
    }

    /** Resolved element box. Separate from the point stored by {@link #rememberGeometry}. */
    public static void rememberBounds(String handle, double x, double y, double width, double height) {
        PageSurfaces.rememberBounds(handle, x, y, width, height);
    }

    public static void rememberBounds(String handle, double x, double y, double width, double height, WebDriver owner, String tab) {
        PageSurfaces.rememberBounds(handle, x, y, width, height, owner, tab);
    }

    /** Point previously stored by {@link #rememberGeometry}, or null. */
    public static int[] rememberedPoint(String handle) {
        return PageSurfaces.rememberedPoint(handle);
    }

    public static void clearRememberedGeometry() {
        PageSurfaces.clearGeometry();
    }

    /** Binds browser and tab onto the handle itself, including hits with no visual geometry. */
    public static void bindHandle(String handle, WebDriver owner, String tab) {
        PageSurfaces.bindHandle(owner, tab, handle);
    }

    public static WebDriver queryOwner() {
        return PageSurfaces.ownerForQuery();
    }

    /** Page driver registered in this process. The worker's browser when the host is not this JVM. */
    public static WebDriver scenarioDriver() {
        return PageSurfaces.liveDriver();
    }

    public static String queryTab(WebDriver owner) {
        return PageSurfaces.focusedTabOf(owner);
    }

    /** Arms the session chat cursor at the current max id so later reads skip earlier lines. */
    public static void armChatCursor(Path project, String runId) throws IOException {
        ChatCursor.arm(project, runId, "run");
        ChatCursor.arm(project, runId, "overlay");
        EventCursor.arm(project, runId, null);
    }

    public static Result execute(
            Path project,
            String runId,
            String op,
            Map<String, String> args,
            List<String> extraArgs,
            String agentId
    ) {
        long started = System.nanoTime();
        Map<String, String> values = args == null ? Map.of() : args;
        try {
            Optional<Result> routed = AgentSurfaceRouter.route(project, runId, op, values, agentId);
            if (routed.isPresent()) return timed(routed.get(), started);
            if (values.get("scope") != null && !values.get("scope").isBlank()) UiState.scope(values.get("scope"));
            if (values.get("ttl") != null && !values.get("ttl").isBlank()) UiState.expires(values.get("ttl"), 0L);
            Result result = dispatch(project, runId, op, values, extraArgs, agentId);
            if (result.exit() == 0 && UiState.TRACKED.contains(op)) UiState.sync(project, runId, values);
            if (result.exit() == 0 && "html-cleanup".equals(op)) UiState.clear(project, runId);
            return timed(result, started);
        } catch (IllegalArgumentException failure) {
            return timed(fail(2, failure.getMessage() == null ? "usage" : failure.getMessage()), started);
        } catch (Exception failure) {
            return timed(fail(1, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()), started);
        }
    }

    private static Result timed(Result result, long started) {
        long elapsedMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        Map<String, Object> body = result.body();
        if (body != null && !body.containsKey("frame")) {
            String frame = liveFrame();
            if (!frame.isBlank()) body.put("frame", frame);
        }
        return new Result(result.exit(), ReplyFields.stamp(body, elapsedMs));
    }

    /** Main frame id when this process has a live browser. Empty otherwise. */
    private static String liveFrame() {
        try {
            dev.domengine.session.BrowserSession host = tools.dscode.common.agentfast.LiveBrowserHost.chrome();
            if (host == null || host.connection() == null || host.isClosed()) return "";
            return tools.dscode.common.agentfast.TargetVerb.mainFrame(host.connection());
        } catch (RuntimeException failure) {
            return "";
        }
    }

    static Result failure(int exit, String error) {
        return fail(exit, error);
    }

    static Result dispatch(Path project, String runId, String op, Map<String, String> values,
                                   List<String> extraArgs, String agentId) throws Exception {
        {
            return switch (op == null ? "" : op) {
                case "observe" -> observe(project, runId, values);
                case "launch" -> launch(project, runId, values, extraArgs);
                case "windows" -> windows(project, runId, values, "window");
                case "tabs" -> windows(project, runId, values, "tab");
                case "chat-send" -> chatSend(project, runId, values, agentId);
                case "chat-history" -> chatHistory(project, runId, values);
                case "chat-poll" -> chatPoll(project, runId, values, agentId);
                case "panel-open" -> panelOpen(project, runId, values, extraArgs, agentId);
                case "panel-close" -> panelClose(project, runId, values, extraArgs, agentId);
                case "guide" -> guide(project, runId, values);
                case "guide-next" -> guideMove(project, runId, values, 1);
                case "guide-back" -> guideMove(project, runId, values, -1);
                case "guide-done" -> guideDone(project, runId, values);
                case "ask" -> ask(project, runId, values);
                case "ask-and-wait" -> askAndWait(project, runId, values);
                case "pin", "toast", "checklist", "status" -> surfaceCard(project, runId, op, values);
                case "ui", "ui-apply" -> UiBatch.apply(project, runId, values, agentId);
                case "ui-poll" -> UiBatch.poll(project, runId, values, agentId);
                case "ui-catalog" -> uiCatalog(project, runId, values, agentId);
                case "screenshot" -> screenshot(project, values);
                case "selftest" -> selftest(project, values);
                case "snap", "find", "act", "form-fill", "wait", "wait-for", "watch", "explain", "capture", "attach", "eval", "frame" -> {
                    tools.dscode.common.agentfast.TargetVerb.Outcome outcome =
                            tools.dscode.common.agentfast.TargetVerb.run(project, runId, op, values);
                    yield new Result(outcome.exit(), outcome.body());
                }
                case "highlight" -> highlight(project, runId, values);
                case "unhighlight" -> unhighlight(project, runId, values);
                case "overlay" -> overlay(project, runId, values);
                case "overlay-poll" -> overlayPoll(project, runId, values);
                case "overlay-remove" -> remove(project, runId, values, "overlay");
                case "label", "border", "arrow", "step-badge", "magnifier", "spotlight" -> visual(project, runId, op, values);
                case "unlabel", "unborder", "unarrow", "unstep-badge", "unmagnifier", "unspotlight" ->
                        remove(project, runId, values, VisualKinds.kind(op));
                case "html-write" -> html(project, runId, values, "append");
                case "html-replace" -> html(project, runId, values, "replace");
                case "html-insert" -> html(project, runId, values, "insert");
                case "html-remove" -> remove(project, runId, values, "html");
                case "html-cleanup" -> cleanup(project, runId, values);
                case "capture-scenario" -> capture(project, runId, values);
                default -> fail(2, "unknown agent command: " + op);
            };
        }
    }

    private static Result observe(Path project, String runId, Map<String, String> args) throws Exception {
        boolean file = present(args.get("events"));
        List<ObserveEvents.Event> events;
        try {
            events = ObserveEvents.read(project, runId, args);
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? "observe failed" : failure.getMessage();
            CompositeLog.info("observe", "failure " + message);
            return fail(1, message);
        }
        String source = file ? "events-file" : "cdp-session";
        String sentence = switch (source) {
            case "events-file" -> "Recorded CDP Session events. The same shape the engine observe command returns.";
            case "cdp-session" -> events.isEmpty()
                    ? "Observe buffer is empty. The engine CDP Session reported no matching events."
                    : "Events already buffered on the engine CDP Session.";
            default -> "Observe buffer is empty. No engine CDP Session is attached.";
        };
        Map<String, Object> body = ok(sentence);
        body.put("source", source);
        body.put("count", events.size());
        body.put("events", events.stream().map(ObserveEvents::toMap).toList());
        return new Result(0, body);
    }

    private static Result launch(Path project, String runId, Map<String, String> args, List<String> extraArgs) throws Exception {
        String name = args.get("template");
        if (!present(name)) return fail(2, "launch requires --template");
        LaunchTemplates.Resolved resolved = LaunchTemplates.resolve(name, launchArgs(extraArgs, args));
        AgentLaunch.write(project, resolved);
        CompositeLog.info("launch", "template=" + resolved.template().name() + " args=" + resolved.args().size());
        Map<String, Object> body = ok(resolved.sentence());
        body.put("template", resolved.template().name());
        body.put("browser", resolved.template().browser());
        body.put("pkbBrowser", resolved.template().pkbBrowser());
        body.put("headless", resolved.template().headless());
        body.put("verbose", resolved.template().verbose());
        body.put("logs", resolved.template().logs());
        body.put("args", resolved.args());
        body.put("started", false);
        if (!truth(args.get("start"))) {
            body.put("handles", List.of());
            return new Result(0, body);
        }
        try {
            ObjectNode config = AgentStore.JSON.createObjectNode();
            config.put("browser", resolved.template().browser());
            if (project != null) config.put("project", project.toAbsolutePath().normalize().toString());
            ObjectNode driver = config.putObject("driver");
            driver.putObject("options");
            driver.putObject("service");
            AgentDriverHooks.template(resolved.template().name());
            RemoteWebDriver web = DriverConstruction.createLocalDriver(resolved.template().browser(), driver, config);
            body.put("started", true);
            body.put("sessionId", web.getSessionId() == null ? "" : web.getSessionId().toString());
            List<Map<String, Object>> handles = new ArrayList<>();
            for (WindowRegistry.Handle handle : WindowRegistry.list(project, runId)) {
                handles.add(WindowRegistry.toMap(handle));
            }
            body.put("handles", handles);
        } catch (Exception failure) {
            body.put("ok", false);
            body.put("started", false);
            body.put("error", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            return new Result(1, body);
        } finally {
            AgentDriverHooks.template(null);
        }
        return new Result(0, body);
    }

    static List<String> launchArgs(List<String> extraArgs, Map<String, String> args) {
        List<String> merged = new ArrayList<>();
        if (extraArgs != null) merged.addAll(extraArgs);
        merged.addAll(splitArgs(args == null ? null : args.get("arg")));
        merged.addAll(splitArgs(args == null ? null : args.get("extraArgs")));
        return merged;
    }

    private static List<String> splitArgs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[")) {
            try {
                List<String> parsed = new ArrayList<>();
                for (var node : AgentStore.JSON.readTree(trimmed)) {
                    String text = node.asText("");
                    if (!text.isBlank()) parsed.add(text);
                }
                if (!parsed.isEmpty()) return parsed;
            } catch (Exception ignored) {
                // Fall through to newline splitting.
            }
        }
        List<String> parsed = new ArrayList<>();
        for (String line : trimmed.split("\\R")) {
            if (!line.isBlank()) parsed.add(line.trim());
        }
        return parsed;
    }

    private static Result windows(Path project, String runId, Map<String, String> args, String kind) throws Exception {
        if (present(args.get("close"))) {
            WindowRegistry.Handle closed = WindowRegistry.close(project, runId, args.get("close"));
            Map<String, Object> body = ok("Closed " + closed.kind() + " " + closed.handle() + ".");
            body.put("handle", WindowRegistry.toMap(closed));
            return new Result(0, body);
        }
        if (present(args.get("switch"))) {
            WindowRegistry.Handle selected = WindowRegistry.switchTo(project, runId, args.get("switch"));
            Map<String, Object> body = ok("Switched to " + selected.handle() + ".");
            body.put("handle", WindowRegistry.toMap(selected));
            return new Result(0, body);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WindowRegistry.Handle handle : WindowRegistry.list(project, runId)) {
            if (kind.equals(handle.kind()) && handle.open()) rows.add(WindowRegistry.toMap(handle));
        }
        Map<String, Object> body = ok(kind + " handles from the existing window registry.");
        body.put("kind", kind);
        body.put("count", rows.size());
        body.put("handles", rows);
        return new Result(0, body);
    }

    private static Result chatSend(Path project, String runId, Map<String, String> args, String agentId) throws Exception {
        if (ChatCursor.after(project, runId, args.get("channel")) == null) {
            ChatCursor.arm(project, runId, args.get("channel"));
        }
        ChatLog.Message message = ChatLog.send(
                project, runId, args.get("channel"), args.get("source"), agentId, args.get("text")
        );
        Map<String, Object> body = ok("Chat message stored for this run channel.");
        body.put("message", ChatLog.toMap(message));
        if ("run".equals(message.channel())) PageSurfaces.pushChat(panelRow(message));
        // The popup draws ledger rows whose op is chat. A stored ChatLog line alone is not visible.
        if (!"off".equals(tools.dscode.common.agentsurface.PopupConfig.mode())) {
            tools.dscode.common.agentsurface.PopupLedger.append(
                    project, runId, "chat", "chat", "", "", message.text(), "", "");
        }
        return new Result(0, body);
    }

    private static Map<String, Object> panelRow(ChatLog.Message message) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "c" + message.id());
        row.put("source", message.source());
        row.put("agent", message.agent());
        row.put("text", message.text());
        row.put("at", message.at());
        return row;
    }

    private static Result panelOpen(Path project, String runId, Map<String, String> args, List<String> extraArgs, String agentId) throws Exception {
        String target = present(args.get("target")) ? args.get("target").trim().toLowerCase(java.util.Locale.ROOT) : "auto";
        if (!target.matches("auto|page|popup")) return fail(2, "panel-open --target must be auto, page, or popup");
        Map<String, Object> spec = new LinkedHashMap<>();
        for (String key : List.of("x", "y", "width", "height")) {
            if (!present(args.get(key))) continue;
            Integer value = integerOrNull(args.get(key));
            if (value == null) return fail(2, "panel-open --" + key + " must be a whole number of pixels");
            spec.put(key.equals("width") ? "w" : key.equals("height") ? "h" : key, value);
        }
        String tab = args.get("tab");
        if (present(tab) && !tab.trim().matches("chat|steps")) return fail(2, "panel-open --tab must be chat or steps");
        if (present(tab)) spec.put("tab", tab.trim());
        String theme = args.get("theme");
        if (present(theme) && !theme.trim().matches("auto|light|dark")) return fail(2, "panel-open --theme must be auto, light, or dark");
        if (present(theme)) spec.put("theme", theme.trim());
        if (present(args.get("title"))) spec.put("title", args.get("title").trim());
        if (present(args.get("minimized"))) spec.put("min", Boolean.parseBoolean(args.get("minimized").trim()));
        boolean live = PageSurfaces.hasLivePage();
        if (!live && ("page".equals(target) || "auto".equals(target))) {
            return fail(1, "no live browser");
        }
        if ("popup".equals(target)) {
            Result popup = execute(project, runId, "popup-open", args, extraArgs, agentId);
            if (popup.body() instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) map);
                body.put("op", "panel-open");
                body.putIfAbsent("target", "popup");
                return new Result(popup.exit(), body);
            }
            return popup;
        }
        PageSurfaces.drainPanelChat(project, runId);
        List<Map<String, Object>> history = new ArrayList<>();
        for (ChatLog.Message message : ChatLog.history(project, runId, null, 50)) history.add(panelRow(message));
        spec.put("history", history);
        String reply = PageSurfaces.runtimeCall("panelOpen", spec, true);
        if (reply == null || reply.isBlank() || "null".equals(reply)) return fail(1, "panel did not open on the page");
        CompositeLog.info("panel", "open " + reply);
        Map<String, Object> body = ok("Agent panel open on the page.");
        body.put("op", "panel-open");
        body.put("target", "page");
        body.put("panel", AgentStore.JSON.readValue(reply, Map.class));
        return new Result(0, body);
    }

    private static final java.util.Set<String> STEP_ACTIONS = java.util.Set.of("click", "input", "select", "check", "any");

    /** Reads {@code --steps} as a JSON array, or {@code @file} relative to the project. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> guideSteps(Path project, String raw) throws Exception {
        if (!present(raw)) throw new IllegalArgumentException("guide requires --steps=<json array> or --steps=@file.json");
        String text = raw.trim();
        if (text.startsWith("@")) {
            Path file = Path.of(text.substring(1));
            if (!file.isAbsolute() && project != null) file = project.resolve(file);
            text = java.nio.file.Files.readString(file);
        }
        Object parsed;
        try {
            parsed = AgentStore.JSON.readValue(text, Object.class);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("guide --steps is not valid JSON: " + failure.getMessage().lines().findFirst().orElse(""));
        }
        if (!(parsed instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException("guide --steps must be a non-empty JSON array");
        if (list.size() > 50) throw new IllegalArgumentException("guide --steps holds at most 50 steps");
        List<Map<String, Object>> steps = new ArrayList<>();
        int n = 0;
        for (Object item : list) {
            n++;
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("guide step " + n + " must be an object with t and say");
            Object t = map.get("t");
            Object say = map.get("say");
            if (t == null || String.valueOf(t).isBlank() || say == null || String.valueOf(say).isBlank()) {
                throw new IllegalArgumentException("guide step " + n + " needs t (target) and say (instruction)");
            }
            Object action = map.get("do");
            if (action != null && !STEP_ACTIONS.contains(String.valueOf(action))) {
                throw new IllegalArgumentException("guide step " + n + " do must be click, input, select, check, or any");
            }
            steps.add((Map<String, Object>) map);
        }
        return steps;
    }

    private static Result screenshot(Path project, Map<String, String> args) throws Exception {
        String out = args.get("out");
        if (!present(out)) return fail(2, "screenshot requires --out");
        WebDriver web = PageSurfaces.liveDriver();
        if (!(web instanceof TakesScreenshot shot)) return fail(1, "no live browser");
        byte[] png = shot.getScreenshotAs(OutputType.BYTES);
        Path file = Path.of(out);
        if (!file.isAbsolute() && project != null) file = project.resolve(out);
        Map<String, Object> body = ScreenshotReply.write(file, png);
        return new Result(0, body);
    }

    private static Result selftest(Path project, Map<String, String> args) throws Exception {
        if ("pages".equals(args.get("component"))) {
            Map<String, Object> report = tools.dscode.common.agentfast.SelftestUi.pages(project);
            if (Boolean.TRUE.equals(report.get("skipped"))) return new Result(1, report);
            return new Result(Boolean.TRUE.equals(report.get("ok")) ? 0 : 1, report);
        }
        boolean cpu = truth(args.get("cpu")) || truth(args.get("ui"));
        if (!cpu) return fail(2, "selftest ui --cpu");
        Map<String, Object> report = SelftestUi.cpu(project, args.get("component"));
        if (Boolean.TRUE.equals(report.get("skipped"))) return new Result(1, report);
        return new Result(Boolean.TRUE.equals(report.get("ok")) ? 0 : 1, report);
    }

    private static Result uiCatalog(Path project, String runId, Map<String, String> args, String agentId) throws Exception {
        String only = args.get("component");
        if (present(only) && UiCatalog.component(only.trim()) == null) {
            return fail(2, "ui-catalog --component must be one of " + String.join(", ", UiCatalog.components().keySet()));
        }
        int eventCursor = EventCursor.after(project, runId, agentId);
        tools.dscode.control.protocol.AgentEvents.Slice slice =
                tools.dscode.control.protocol.AgentEvents.after(project, runId, eventCursor);
        EventCursor.advance(project, runId, agentId, slice.next());
        Map<String, Object> body = ok("Page UI components for ui-apply: options as type=default; help, an example op, and how to undo it.");
        body.put("op", "ui-catalog");
        body.put("components", UiCatalog.describe(present(only) ? only.trim() : null));
        body.put("agentEvents", slice.events());
        return new Result(0, body);
    }

    private static Result guide(Path project, String runId, Map<String, String> args) throws Exception {
        List<Map<String, Object>> steps = guideSteps(project, args.get("steps"));
        if (!PageSurfaces.hasLivePage()) return fail(1, "guide needs a live browser page; run launch first");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", present(args.get("id")) ? args.get("id").trim() : "g1");
        spec.put("steps", steps);
        spec.put("scroll", "true".equalsIgnoreCase(String.valueOf(args.get("scroll"))));
        String reply = PageSurfaces.runtimeCall("guide", spec, true);
        if (reply == null || "null".equals(reply)) return fail(1, "guide did not start on the page");
        CompositeLog.info("guide", "start " + reply);
        Map<String, Object> body = ok("Guide shown on the page; steps advance as the person acts.");
        body.put("op", "guide");
        body.putAll(AgentStore.JSON.readValue(reply, Map.class));
        return new Result(0, body);
    }

    private static Result guideMove(Path project, String runId, Map<String, String> args, int delta) throws Exception {
        if (!PageSurfaces.hasLivePage()) return fail(1, "no live browser page holds a guide");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", present(args.get("id")) ? args.get("id").trim() : "g1");
        spec.put("delta", delta);
        String reply = PageSurfaces.runtimeCall("guideMove", spec, false);
        if (reply == null || "null".equals(reply)) return fail(1, "no guide " + spec.get("id") + " on the page");
        CompositeLog.info("guide", (delta > 0 ? "next " : "back ") + reply);
        Map<String, Object> body = ok(delta > 0 ? "Guide moved to the next step." : "Guide moved back one step.");
        body.put("op", delta > 0 ? "guide-next" : "guide-back");
        body.putAll(AgentStore.JSON.readValue(reply, Map.class));
        return new Result(0, body);
    }

    private static Result guideDone(Path project, String runId, Map<String, String> args) throws Exception {
        if (!PageSurfaces.hasLivePage()) return fail(1, "no live browser page holds a guide");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", present(args.get("id")) ? args.get("id").trim() : "g1");
        String reply = PageSurfaces.runtimeCall("guideDone", spec, false);
        if (reply == null || "null".equals(reply)) return fail(1, "no guide " + spec.get("id") + " on the page");
        CompositeLog.info("guide", "done " + reply);
        Map<String, Object> body = ok("Guide closed.");
        body.put("op", "guide-done");
        body.putAll(AgentStore.JSON.readValue(reply, Map.class));
        return new Result(0, body);
    }

    private static Result ask(Path project, String runId, Map<String, String> args) throws Exception {
        if (!present(args.get("say"))) return fail(2, "ask requires --say=<question>");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", present(args.get("id")) ? args.get("id").trim() : "q1");
        spec.put("say", args.get("say").trim());
        if (present(args.get("choices"))) {
            List<String> choices = new ArrayList<>();
            for (String choice : args.get("choices").split(",")) if (!choice.isBlank()) choices.add(choice.trim());
            if (choices.size() > 6) return fail(2, "ask --choices holds at most 6 answers");
            spec.put("choices", choices);
        }
        if (!PageSurfaces.hasLivePage()) return fail(1, "ask needs a live browser page; run launch first");
        if (present(args.get("handle"))) spec.put("t", args.get("handle").trim());
        if ("true".equalsIgnoreCase(String.valueOf(args.get("sensitive")))) spec.put("sensitive", true);
        String reply = PageSurfaces.runtimeCall("ask", spec, true);
        if (reply == null || "null".equals(reply)) return fail(1, "ask did not show on the page");
        CompositeLog.info("ui", "ask " + reply);
        Map<String, Object> body = ok("Question shown on the page; the answer arrives as an answer event in overlay-poll.");
        body.put("op", "ask");
        body.putAll(AgentStore.JSON.readValue(reply, Map.class));
        return new Result(0, body);
    }

    private static Result askAndWait(Path project, String runId, Map<String, String> args) throws Exception {
        Result shown = ask(project, runId, args);
        if (shown.exit() != 0) return shown;
        String id = String.valueOf(shown.body().getOrDefault("id", "q1"));
        long timeout = 3000L;
        if (present(args.get("timeout"))) {
            try {
                timeout = Long.parseLong(args.get("timeout").trim());
            } catch (NumberFormatException failure) {
                return fail(2, "timeout is milliseconds");
            }
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        Map<String, Object> answer = null;
        while (true) {
            for (Map<String, Object> event : PageSurfaces.pollInput(project, runId)) {
                if ("answer".equals(String.valueOf(event.get("type"))) && id.equals(String.valueOf(event.get("id")))) {
                    answer = event;
                }
            }
            if (answer != null) break;
            if (System.nanoTime() >= deadline) return fail(1, "timeout");
            Thread.sleep(50);
        }
        Map<String, Object> body = ok("Answer received.");
        body.put("op", "ask-and-wait");
        body.put("id", id);
        Object value = answer.get("value");
        body.put("value", value == null ? "" : value);
        body.put("applied", value != null && !String.valueOf(value).isBlank());
        return new Result(0, body);
    }

    /** pin, toast, checklist, and the status banner. Toast leaves the page on its own. */
    private static Result surfaceCard(Path project, String runId, String op, Map<String, String> args) throws Exception {
        String text;
        if ("checklist".equals(op)) {
            String items = args.get("items");
            if (!present(items)) return fail(2, "checklist requires --items");
            StringBuilder html = new StringBuilder();
            for (String item : items.split("\\|")) {
                if (item.isBlank()) continue;
                html.append(item.trim()).append(' ');
            }
            if (html.isEmpty()) return fail(2, "checklist requires --items");
            text = html.toString().trim();
        } else {
            text = args.get("text");
            if (!present(text)) return fail(2, op + " requires --text");
        }
        Map<String, Object> placed = PageSurfaces.place(
                explicitPage(project, args),
                "append",
                null,
                null,
                text,
                "label",
                24,
                24,
                null,
                AgentStore.root(project, runId)
        );
        placed.put("ok", true);
        placed.put("op", op);
        placed.put("kind", op);
        if ("toast".equals(op)) {
            placed.put("transient", true);
            String id = String.valueOf(placed.get("id"));
            Thread remover = new Thread(() -> {
                try {
                    Thread.sleep(1200);
                    PageSurfaces.remove(null, id);
                } catch (RuntimeException | java.io.IOException | InterruptedException ignored) {
                    // A closed page has already dropped the toast.
                }
            }, "pkb-toast-" + id);
            remover.setDaemon(true);
            remover.start();
        }
        return new Result(0, placed);
    }

    private static Result panelClose(Path project, String runId, Map<String, String> args, List<String> extraArgs, String agentId) throws Exception {
        String target = present(args.get("target")) ? args.get("target").trim().toLowerCase(java.util.Locale.ROOT) : "auto";
        if (!target.matches("auto|page|popup")) return fail(2, "panel-close --target must be auto, page, or popup");
        if ("popup".equals(target) || ("auto".equals(target) && !PageSurfaces.hasLivePage())) {
            Result popup = execute(project, runId, "popup-close", args, extraArgs, agentId);
            if (popup.body() instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = new LinkedHashMap<>((Map<String, Object>) map);
                body.put("op", "panel-close");
                return new Result(popup.exit(), body);
            }
            return popup;
        }
        PageSurfaces.drainPanelChat(project, runId);
        String reply = PageSurfaces.runtimeCall("panelClose", null, false);
        CompositeLog.info("panel", "close " + reply);
        Map<String, Object> body = ok("Agent panel closed on the page.");
        body.put("op", "panel-close");
        body.put("target", "page");
        body.put("closed", "true".equals(reply));
        return new Result(0, body);
    }

    private static Result chatHistory(Path project, String runId, Map<String, String> args) throws Exception {
        PageSurfaces.drainPanelChat(project, runId);
        int limit = integer(args.get("limit"), 100);
        String after = cursorAfter(project, runId, args);
        List<ChatLog.Message> rows = present(after)
                ? new ArrayList<>(ChatLog.poll(project, runId, args.get("channel"), after))
                : new ArrayList<>(ChatLog.history(project, runId, args.get("channel"), limit));
        if (present(after) && limit > 0 && rows.size() > limit) {
            rows = new ArrayList<>(rows.subList(rows.size() - limit, rows.size()));
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatLog.Message message : rows) {
            messages.add(ChatLog.toMap(message));
        }
        Map<String, Object> body = ok("Chat history for this run channel.");
        body.put("count", messages.size());
        body.put("messages", messages);
        return new Result(0, body);
    }

    private static Result chatPoll(Path project, String runId, Map<String, String> args, String agentId) throws Exception {
        String after = cursorAfter(project, runId, args);
        long waitMillis = 0;
        if (present(args.get("wait"))) {
            try {
                waitMillis = Math.min(25_000L, Math.max(0L, (long) (Double.parseDouble(args.get("wait").trim()) * 1000)));
            } catch (NumberFormatException failure) {
                return fail(2, "chat-poll --wait is seconds, up to 25");
            }
        }
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        int eventCursor = EventCursor.after(project, runId, agentId);
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, String>> agentEvents = List.of();
        int eventNext = eventCursor;
        while (true) {
            messages.clear();
            PageSurfaces.drainPanelChat(project, runId);
            for (ChatLog.Message message : ChatLog.poll(project, runId, args.get("channel"), after)) {
                messages.add(ChatLog.toMap(message));
            }
            tools.dscode.control.protocol.AgentEvents.Slice slice =
                    tools.dscode.control.protocol.AgentEvents.after(project, runId, eventCursor);
            agentEvents = slice.events();
            eventNext = slice.next();
            if (!messages.isEmpty() || !agentEvents.isEmpty() || System.nanoTime() >= deadline) break;
            Thread.sleep(200);
        }
        boolean explicit = present(args.get("after")) || present(args.get("id"));
        if (!explicit && !messages.isEmpty()) {
            Object last = messages.get(messages.size() - 1).get("id");
            if (last != null) ChatCursor.advance(project, runId, args.get("channel"), String.valueOf(last));
        }
        EventCursor.advance(project, runId, agentId, eventNext);
        Map<String, Object> body = ok("Chat messages after the given id.");
        body.put("count", messages.size());
        body.put("messages", messages);
        body.put("agentEvents", agentEvents);
        return new Result(0, body);
    }

    private static Result highlight(Path project, String runId, Map<String, String> args) throws Exception {
        if (!present(args.get("handle"))) return fail(2, "highlight requires --handle");
        String target = args.get("target");
        if (present(target)) {
            String raw = target.trim();
            if (raw.startsWith("browser:") || raw.startsWith("desktop:") || raw.startsWith("ref:")) {
                String rejected = tools.dscode.common.agentfast.TargetVerb.unavailable(raw);
                if (rejected != null) {
                    Result failed = fail(1, rejected);
                    failed.body().put("applied", false);
                    return failed;
                }
                String css = PageSurfaces.presetCss(args.get("preset"), args.get("style"));
                tools.dscode.common.agentfast.TargetVerb.Outcome painted =
                        tools.dscode.common.agentfast.TargetVerb.highlight(raw, args.get("handle"), css, runId);
                return new Result(painted.exit(), painted.body());
            }
        }
        Map<String, Object> painted = PageSurfaces.highlight(
                explicitPage(project, args),
                args.get("handle"),
                args.get("preset"),
                args.get("style"),
                integerOrNull(args.get("x")),
                integerOrNull(args.get("y")),
                integerOrNull(args.get("width")),
                integerOrNull(args.get("height"))
        );
        painted.put("ok", true);
        return new Result(0, painted);
    }

    private static Result unhighlight(Path project, String runId, Map<String, String> args) throws Exception {
        Map<String, Object> cleared = PageSurfaces.unhighlight(explicitPage(project, args), args.get("handle"));
        cleared.put("ok", true);
        return new Result(0, cleared);
    }

    private static Result overlay(Path project, String runId, Map<String, String> args) throws Exception {
        if (!present(args.get("text"))) return fail(2, "overlay requires --text");
        String at = args.get("at");
        if (present(at) && !at.trim().matches("auto|top|bottom|left|right|over")) {
            return fail(2, "overlay --at must be auto, top, bottom, left, right, or over");
        }
        String theme = args.get("theme");
        if (present(theme) && !theme.trim().matches("auto|light|dark")) {
            return fail(2, "overlay --theme must be auto, light, or dark");
        }
        String block = args.get("block");
        if (present(block) && !block.trim().matches("(?i)true|false")) {
            return fail(2, "overlay --block must be true or false");
        }
        Map<String, String> look = new LinkedHashMap<>();
        if (present(at)) look.put("at", at.trim());
        if (present(theme)) look.put("theme", theme.trim());
        if (present(block)) look.put("block", block.trim().toLowerCase());
        Map<String, Object> placed = PageSurfaces.place(
                explicitPage(project, args),
                "append",
                args.get("handle"),
                args.get("where"),
                args.get("text"),
                "overlay",
                integerOrNull(args.get("x")),
                integerOrNull(args.get("y")),
                args.get("geometry"),
                AgentStore.root(project, runId),
                integerOrNull(args.get("width")),
                integerOrNull(args.get("height")),
                look
        );
        placed.put("ok", true);
        stampSeen(placed, args.get("handle"), runId);
        if (present(at)) placed.put("at", at.trim());
        if (present(block) && Boolean.parseBoolean(block.trim())) placed.put("block", true);
        return new Result(0, placed);
    }

    private static Result visual(Path project, String runId, String op, Map<String, String> args) throws Exception {
        String kind = VisualKinds.kind(op);
        if (present(args.get("block")) && !args.get("block").trim().matches("(?i)true|false")) {
            return fail(2, op + " --block must be true or false");
        }
        String text = args.get("text");
        String handle = args.get("handle");
        Integer x = integerOrNull(args.get("x"));
        Integer y = integerOrNull(args.get("y"));
        Integer width = integerOrNull(args.get("width"));
        Integer height = integerOrNull(args.get("height"));
        String geometry = args.get("geometry");
        if ("label".equals(kind) && !present(text)) return fail(2, "label requires --text");
        if ("step".equals(kind)) text = VisualKinds.stepMarkup(args.get("number"), text);
        if ("magnifier".equals(kind)) {
            text = VisualKinds.magnifierMarkup(text, args.get("zoom"));
            args = new LinkedHashMap<>(args);
            args.put("zoom", VisualKinds.zoom(args.get("zoom")));
        }
        if ("spotlight".equals(kind)) {
            String dim = VisualKinds.dim(args.get("dim"));
            args = new LinkedHashMap<>(args);
            args.put("dim", dim);
            if (!present(text)) text = "";
        }
        if ("arrow".equals(kind)) {
            VisualKinds.Arrow arrow = VisualKinds.arrow(args);
            text = arrow.svg();
            x = arrow.x();
            y = arrow.y();
            width = arrow.width();
            height = arrow.height();
            args = new LinkedHashMap<>(args);
            String anchor = present(args.get("handle")) ? args.get("handle") : args.get("from");
            if (present(anchor)) args.put("anchor", anchor.trim());
            handle = null;
            geometry = arrow.resolved()
                    ? "{\"visual_viewport_relative\":{\"x\":" + arrow.x() + ",\"y\":" + arrow.y()
                    + ",\"width\":" + arrow.width() + ",\"height\":" + arrow.height() + "}}"
                    : null;
        }
        if ("border".equals(kind) && text == null) text = "";
        if (present(args.get("block"))) {
            args = new LinkedHashMap<>(args);
            args.put("block", args.get("block").trim().toLowerCase());
        }
        Map<String, Object> placed = PageSurfaces.place(
                explicitPage(project, args),
                "append",
                handle,
                args.get("where"),
                text,
                kind,
                x,
                y,
                geometry,
                AgentStore.root(project, runId),
                width,
                height,
                args
        );
        placed.put("ok", true);
        stampSeen(placed, args.get("anchor") != null ? args.get("anchor") : args.get("handle"), runId);
        if ("arrow".equals(kind)) placed.put("direction", args.get("direction") == null ? "" : args.get("direction"));
        if ("step".equals(kind)) placed.put("number", Integer.parseInt(args.get("number").trim()));
        if ("magnifier".equals(kind)) placed.put("zoom", args.get("zoom"));
        if ("spotlight".equals(kind)) placed.put("dim", args.get("dim"));
        if (present(args.get("block")) && Boolean.parseBoolean(args.get("block").trim())) placed.put("block", true);
        return new Result(0, placed);
    }

    private record Seen(String run, String id, int x, int y, int w, int h) {}

    private static final List<Seen> SEEN = java.util.Collections.synchronizedList(new ArrayList<>());

    /** visible, hidden, and lost describe the target. overlaps names other placements on this run. */
    private static void stampSeen(Map<String, Object> placed, String handle, String runId) {
        WebDriver web = PageSurfaces.liveDriver();
        if (web instanceof org.openqa.selenium.JavascriptExecutor js && present(handle)) {
            try {
                Object raw = js.executeScript(
                        "var h=arguments[0];"
                                + "var el=document.querySelector('[data-pkb-handle=\"'+CSS.escape(h)+'\"]');"
                                + "if(!el) el=document.getElementById(h);"
                                + "if(!el) return {lost:true,visible:false,hidden:true};"
                                + "var s=getComputedStyle(el);"
                                + "var shown=el.getClientRects().length>0 && s.display!=='none' && s.visibility!=='hidden' && !el.hidden;"
                                + "return {lost:false,visible:shown,hidden:!shown};",
                        handle
                );
                if (raw instanceof Map<?, ?> map) {
                    placed.put("visible", Boolean.TRUE.equals(map.get("visible")));
                    placed.put("hidden", Boolean.TRUE.equals(map.get("hidden")));
                    placed.put("lost", Boolean.TRUE.equals(map.get("lost")));
                }
            } catch (RuntimeException ignored) {
                placed.put("visible", false);
                placed.put("hidden", false);
                placed.put("lost", true);
            }
        }
        int x = seenNumber(placed.get("x"));
        int y = seenNumber(placed.get("y"));
        int w = seenNumber(placed.get("width"));
        int h = seenNumber(placed.get("height"));
        String id = String.valueOf(placed.getOrDefault("id", ""));
        List<String> overlaps = new ArrayList<>();
        if (w > 0 && h > 0 && !id.isBlank()) {
            String run = runId == null ? "" : runId;
            synchronized (SEEN) {
                if (SEEN.size() > 64) SEEN.subList(0, SEEN.size() - 64).clear();
                for (Seen seen : SEEN) {
                    if (!run.equals(seen.run) || seen.id.equals(id)) continue;
                    boolean apart = x + w <= seen.x || seen.x + seen.w <= x || y + h <= seen.y || seen.y + seen.h <= y;
                    if (!apart) overlaps.add(seen.id);
                }
                SEEN.add(new Seen(run, id, x, y, w, h));
            }
            placed.put("overlaps", overlaps);
        }
    }

    private static int seenNumber(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static Result overlayPoll(Path project, String runId, Map<String, String> args) throws Exception {
        if (!present(args.get("after"))) {
            List<Map<String, Object>> events = PageSurfaces.pollInput(project, runId);
            Map<String, Object> body = ok("Forwarded overlay clicks and form inputs.");
            body.put("count", events.size());
            body.put("events", events);
            return new Result(0, body);
        }
        int after;
        try {
            after = Integer.parseInt(args.get("after").trim());
        } catch (NumberFormatException failure) {
            return fail(2, "overlay-poll --after must be a whole number (the next value from the last poll)");
        }
        List<Map<String, Object>> all = PageSurfaces.pollInput(project, runId);
        List<Map<String, Object>> events = after <= 0 ? all
                : after >= all.size() ? List.of() : List.copyOf(all.subList(after, all.size()));
        Map<String, Object> body = ok("Overlay events after the cursor.");
        body.put("count", events.size());
        body.put("events", events);
        body.put("next", all.size());
        return new Result(0, body);
    }

    private static Result html(Path project, String runId, Map<String, String> args, String op) throws Exception {
        if (!present(args.get("text"))) return fail(2, "html " + op + " requires --text");
        Map<String, Object> placed = PageSurfaces.place(
                explicitPage(project, args),
                op,
                args.get("handle"),
                args.get("where"),
                args.get("text"),
                "html",
                integerOrNull(args.get("x")),
                integerOrNull(args.get("y")),
                args.get("geometry"),
                AgentStore.root(project, runId)
        );
        placed.put("ok", true);
        return new Result(0, placed);
    }

    private static Result remove(Path project, String runId, Map<String, String> args, String kind) throws Exception {
        String id = present(args.get("id")) ? args.get("id") : args.get("handle");
        Map<String, Object> removed = PageSurfaces.remove(explicitPage(project, args), id, AgentStore.root(project, runId), kind);
        removed.put("ok", true);
        removed.put("kind", kind);
        return new Result(0, removed);
    }

    private static Result cleanup(Path project, String runId, Map<String, String> args) throws Exception {
        Map<String, Object> cleaned = PageSurfaces.cleanup(explicitPage(project, args));
        cleaned.put("ok", true);
        return new Result(0, cleaned);
    }

    private static Result capture(Path project, String runId, Map<String, String> args) throws Exception {
        List<ObserveEvents.Event> events;
        try {
            events = ObserveEvents.read(project, runId, args);
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? "observe failed" : failure.getMessage();
            CompositeLog.info("capture", "failure " + message);
            return fail(1, message);
        }
        Map<String, Object> written = ScenarioCapture.write(project, runId, events, args.get("out"));
        written.put("ok", true);
        CompositeLog.info("capture", "filter url=" + text(args.get("url")) + " out=" + written.get("path"));
        return new Result(0, written);
    }

    private static Path explicitPage(Path project, Map<String, String> args) {
        String html = args.get("html");
        if (!present(html)) html = args.get("page");
        if (!present(html)) return null;
        Path path = Path.of(html);
        if (!path.isAbsolute() && project != null) path = project.resolve(html);
        return path;
    }

    private static Map<String, Object> ok(String sentence) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("sentence", sentence);
        return body;
    }

    private static Result fail(int exit, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        return new Result(exit, body);
    }

    private static String cursorAfter(Path project, String runId, Map<String, String> args) {
        if (present(args.get("after"))) return args.get("after");
        if (present(args.get("id"))) return args.get("id");
        return ChatCursor.after(project, runId, args.get("channel"));
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean truth(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
    }

    private static int integer(String value, int fallback) {
        if (!present(value)) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    private static Integer integerOrNull(String value) {
        if (!present(value)) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
