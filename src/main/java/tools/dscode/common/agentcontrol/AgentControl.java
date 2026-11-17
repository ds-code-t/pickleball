package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.agentsurface.AgentSurfaceRouter;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.driver.DriverConstruction;

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

    public static String queryTab(WebDriver owner) {
        return PageSurfaces.focusedTabOf(owner);
    }

    public static Result execute(
            Path project,
            String runId,
            String op,
            Map<String, String> args,
            List<String> extraArgs,
            String agentId
    ) {
        Map<String, String> values = args == null ? Map.of() : args;
        try {
            Optional<Result> routed = AgentSurfaceRouter.route(project, runId, op, values, agentId);
            if (routed.isPresent()) return routed.get();
            if (values.get("scope") != null && !values.get("scope").isBlank()) UiState.scope(values.get("scope"));
            if (values.get("ttl") != null && !values.get("ttl").isBlank()) UiState.expires(values.get("ttl"), 0L);
            Result result = dispatch(project, runId, op, values, extraArgs, agentId);
            if (result.exit() == 0 && UiState.TRACKED.contains(op)) UiState.sync(project, runId, values);
            if (result.exit() == 0 && "html-cleanup".equals(op)) UiState.clear(project, runId);
            return result;
        } catch (IllegalArgumentException failure) {
            return fail(2, failure.getMessage() == null ? "usage" : failure.getMessage());
        } catch (Exception failure) {
            return fail(1, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
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
                case "chat-poll" -> chatPoll(project, runId, values);
                case "panel-open" -> panelOpen(project, runId, values, extraArgs, agentId);
                case "panel-close" -> panelClose(project, runId, values, extraArgs, agentId);
                case "guide" -> guide(project, runId, values);
                case "guide-next" -> guideMove(project, runId, values, 1);
                case "guide-back" -> guideMove(project, runId, values, -1);
                case "guide-done" -> guideDone(project, runId, values);
                case "ask" -> ask(project, runId, values);
                case "ui-apply" -> UiBatch.apply(project, runId, values, agentId);
                case "ui-poll" -> UiBatch.poll(project, runId, values, agentId);
                case "ui-catalog" -> uiCatalog(values);
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
        ChatLog.Message message = ChatLog.send(
                project, runId, args.get("channel"), args.get("source"), agentId, args.get("text")
        );
        Map<String, Object> body = ok("Chat message stored for this run channel.");
        body.put("message", ChatLog.toMap(message));
        if ("run".equals(message.channel())) PageSurfaces.pushChat(panelRow(message));
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
        if ("popup".equals(target) || ("auto".equals(target) && !PageSurfaces.hasLivePage())) {
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

    private static Result uiCatalog(Map<String, String> args) {
        String only = args.get("component");
        if (present(only) && UiCatalog.component(only.trim()) == null) {
            return fail(2, "ui-catalog --component must be one of " + String.join(", ", UiCatalog.components().keySet()));
        }
        Map<String, Object> body = ok("Page UI components for ui-apply: options as type=default; help, an example op, and how to undo it.");
        body.put("op", "ui-catalog");
        body.put("components", UiCatalog.describe(present(only) ? only.trim() : null));
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
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatLog.Message message : ChatLog.history(project, runId, args.get("channel"), limit)) {
            messages.add(ChatLog.toMap(message));
        }
        Map<String, Object> body = ok("Chat history for this run channel.");
        body.put("count", messages.size());
        body.put("messages", messages);
        return new Result(0, body);
    }

    private static Result chatPoll(Path project, String runId, Map<String, String> args) throws Exception {
        String after = present(args.get("after")) ? args.get("after") : args.get("id");
        long waitMillis = 0;
        if (present(args.get("wait"))) {
            try {
                waitMillis = Math.min(25_000L, Math.max(0L, (long) (Double.parseDouble(args.get("wait").trim()) * 1000)));
            } catch (NumberFormatException failure) {
                return fail(2, "chat-poll --wait is seconds, up to 25");
            }
        }
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        List<Map<String, Object>> messages = new ArrayList<>();
        while (true) {
            PageSurfaces.drainPanelChat(project, runId);
            for (ChatLog.Message message : ChatLog.poll(project, runId, args.get("channel"), after)) {
                messages.add(ChatLog.toMap(message));
            }
            if (!messages.isEmpty() || System.nanoTime() >= deadline) break;
            Thread.sleep(200);
        }
        Map<String, Object> body = ok("Chat messages after the given id.");
        body.put("count", messages.size());
        body.put("messages", messages);
        return new Result(0, body);
    }

    private static Result highlight(Path project, String runId, Map<String, String> args) throws Exception {
        if (!present(args.get("handle"))) return fail(2, "highlight requires --handle");
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
        if ("arrow".equals(kind)) placed.put("direction", args.get("direction") == null ? "" : args.get("direction"));
        if ("step".equals(kind)) placed.put("number", Integer.parseInt(args.get("number").trim()));
        if ("magnifier".equals(kind)) placed.put("zoom", args.get("zoom"));
        if ("spotlight".equals(kind)) placed.put("dim", args.get("dim"));
        if (present(args.get("block")) && Boolean.parseBoolean(args.get("block").trim())) placed.put("block", true);
        return new Result(0, placed);
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
