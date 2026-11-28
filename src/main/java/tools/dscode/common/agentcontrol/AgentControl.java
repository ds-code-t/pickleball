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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One controller for observe, launch, windows, chat, highlight, overlay, HTML,
 * and capture. CLI, the control bridge, MCP, and the Workbench only forward this.
 */
public final class AgentControl {
    public record Result(int exit, Map<String, Object> body) {
    }

    /** Each ask without {@code --id} gets the next id. {@code q1} is not reused. */
    private static final AtomicInteger ASK_SEQ = new AtomicInteger();

    private AgentControl() {
    }

    /** Re-draws stored layers after the live page navigates. The host calls this off the CDP reader thread. */
    public static void replayAfterNavigation() {
        PageSurfaces.replayAfterNavigation();
    }

    public static void cleanup() {
        AgentDriverHooks.releaseUnowned();
        AgentDriverHooks.quit();
    }

    /** Pickleball surface events (chat, guide steps, cards) the page has pushed; page mutations are not counted. */
    public static long surfaceEvents() {
        return PageSurfaces.surfaceEvents();
    }

    /** Snapshots what is shown on the live page (panel position, dock, placements) so a navigate restores it. */
    public static void syncUiState(Path project, String runId) {
        UiState.sync(project, runId, null);
    }

    /** Puts the live Selenium document back on the top frame. A missing driver is a no-op. */
    public static void resetLiveDocument() {
        WebDriver web = PageSurfaces.liveDriver();
        if (web == null) return;
        try {
            tools.dscode.common.composite.ElementOperations.defaultContent(web);
        } catch (RuntimeException ignored) {
            // The CLI entered map is already cleared.
        }
    }

    /**
     * Enters an iframe or frame whose id, name, or title is {@code token}.
     * A miss, including a cross-site CDP id, leaves the driver where it was after the reset attempt.
     */
    public static void enterNamedFrame(String token) {
        if (token == null || token.isBlank()) return;
        WebDriver web = PageSurfaces.liveDriver();
        if (web == null) return;
        try {
            tools.dscode.common.composite.ElementOperations.defaultContent(web);
            String want = token.trim();
            for (org.openqa.selenium.WebElement frame : tools.dscode.common.composite.ElementOperations.findElements(
                    web, org.openqa.selenium.By.cssSelector("iframe, frame"))) {
                if (want.equals(attr(frame, "id")) || want.equals(attr(frame, "name")) || want.equals(attr(frame, "title"))) {
                    tools.dscode.common.composite.ElementOperations.enterFrame(web, frame);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // CDP enter still scopes later agent calls.
        }
    }

    private static String attr(org.openqa.selenium.WebElement element, String name) {
        try {
            String value = tools.dscode.common.composite.ElementOperations.attribute(element, name);
            return value == null ? "" : value.trim();
        } catch (RuntimeException ignored) {
            return "";
        }
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

    /** Sibling overlay or direct DOM highlight. Blank mutate is the sibling layer. */
    public static void drawHighlight(String mutate, String agent, String handle) {
        PageSurfaces.drawHighlight(mutate, agent, handle);
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
        String session = runId == null || runId.isBlank() ? "session" : runId;
        if (args != null && args.get("session") != null && !args.get("session").isBlank()) session = args.get("session");
        tools.dscode.common.agentfast.PushChannel.bind(project, session);
        Map<String, String> values = args == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(args);
        tools.dscode.common.agentfast.AgentCursor.absorb(op, values);
        String humanRefusal = HumanLock.check(project, runId, op, values, agentId);
        if (humanRefusal != null) return timed(refusal(humanRefusal), started, project, runId, op, agentId);
        String attached = attachedRefusal(op, runId, values);
        if (attached != null && !SpineSession.injectEnabled(project, values.get("session"))) {
            Result failed = fail(2, attached);
            failed.body().put("fix", (op == null ? "that verb" : op) + " serves the start host or the isolate worker");
            return timed(failed, started, project, runId, op, agentId);
        }
        if (attached != null) {
            try {
                SpineSession.attach(project, values.get("session"), values.get("target"));
            } catch (Exception failure) {
                Result failed = fail(1, failure.getMessage() == null ? "inject failed" : failure.getMessage());
                return timed(failed, started, project, runId, op, agentId);
            }
        }
        try {
            Optional<Result> routed = AgentSurfaceRouter.route(project, runId, op, values, agentId);
            if (routed.isPresent()) return timed(routed.get(), started, project, runId, op, agentId);
            if (values.get("scope") != null && !values.get("scope").isBlank()) UiState.scope(values.get("scope"));
            if (values.get("ttl") != null && !values.get("ttl").isBlank()) UiState.expires(values.get("ttl"), 0L);
            Result result = dispatch(project, runId, op, values, extraArgs, agentId);
            if (result.exit() == 0 && WAKE_OPS.contains(op)) wakeFields(result.body());
            if (tools.dscode.common.agentfast.StandardView.requested(values)) {
                result = new Result(result.exit(), tools.dscode.common.agentfast.StandardView.applyBody(result.body(), values));
            }
            if (result.exit() == 0 && UiState.TRACKED.contains(op)) UiState.sync(project, runId, values);
            if (result.exit() == 0 && "html-cleanup".equals(op)) UiState.clear(project, runId);
            return timed(result, started, project, runId, op, agentId);
        } catch (IllegalArgumentException failure) {
            return timed(fail(2, failure.getMessage() == null ? "usage" : failure.getMessage()), started, project, runId, op, agentId);
        } catch (Exception failure) {
            return timed(fail(1, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()), started, project, runId, op, agentId);
        }
    }

    private static Result timed(Result result, long started, Path project, String runId, String op, String agentId) {
        long elapsedMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        Map<String, Object> body = result.body();
        if (body != null && !body.containsKey("frame")) {
            String frame = liveFrame();
            if (!frame.isBlank()) body.put("frame", frame);
        }
        Map<String, Object> stamped = ReplyFields.stamp(body, elapsedMs);
        if (stamped.get("op") == null && op != null) stamped.put("op", op);
        return new Result(result.exit(), tools.dscode.common.agentfast.AgentEnvelope.apply(
                stamped, result.exit(), op, project, runId, agentId));
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
                case "chat-read" -> chatRead(project, runId, values);
                case "chat-poll" -> chatPoll(project, runId, values, agentId);
                case "chat-turn" -> chatTurn(project, runId, values, agentId);
                case "ui-verify" -> uiVerify(project, runId);
                case "panel-open" -> panelOpen(project, runId, values, extraArgs, agentId);
                case "panel-state" -> panelStateRead();
                case "panel-close" -> panelClose(project, runId, values, extraArgs, agentId);
                case "guide" -> guide(project, runId, values);
                case "guide-next" -> guideMove(project, runId, values, 1);
                case "guide-back" -> guideMove(project, runId, values, -1);
                case "guide-done" -> guideDone(project, runId, values);
                case "ask" -> ask(project, runId, values);
                case "ask-answer" -> askAnswer(project, runId, values);
                case "ask-and-wait" -> askAndWait(project, runId, values);
                case "answer-wait" -> answerWait(project, runId, values);
                case "wait-any" -> waitAny(project, runId, values);
                case "pin", "toast", "checklist", "status" -> surfaceCard(project, runId, op, values);
                case "ui", "ui-apply" -> UiBatch.apply(project, runId, values, agentId);
                case "ui-poll" -> UiBatch.poll(project, runId, values, agentId);
                case "ui-catalog" -> uiCatalog(project, runId, values, agentId);
                case "screenshot" -> screenshot(project, values);
                case "selftest" -> selftest(project, values);
                case "navigate" -> navigate(project, runId, values, extraArgs, agentId);
                case "wait" -> {
                    if (!"new-message".equals(values.get("condition") == null ? "" : values.get("condition").trim())) {
                        tools.dscode.common.agentfast.TargetVerb.Outcome outcome =
                                tools.dscode.common.agentfast.TargetVerb.run(project, runId, op, values);
                        if (outcome.exit() == 0) {
                            tools.dscode.common.recording.RecordControl.noteAgent(project, runId, op, values, outcome.body());
                        }
                        yield new Result(outcome.exit(), outcome.body());
                    }
                    yield waitNewMessage(project, runId, values);
                }
                case "snap", "find", "act", "form-fill", "wait-for", "watch", "explain", "capture", "attach", "eval", "frame", "upload" -> {
                    tools.dscode.common.agentfast.TargetVerb.Outcome outcome =
                            tools.dscode.common.agentfast.TargetVerb.run(project, runId, op, values);
                    if (outcome.exit() == 0) {
                        tools.dscode.common.recording.RecordControl.noteAgent(project, runId, op, values, outcome.body());
                    }
                    yield new Result(outcome.exit(), outcome.body());
                }
                case "ref" -> expandRef(values);
                case "inject" -> injectLive(project, values, agentId);
                case "input-block" -> inputBlockLive(project, values, agentId);
                case "lease" -> leaseLive(project, values, agentId);
                case "input" -> inputLive(project, runId, values);
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
                case "capture-steps" -> captureSteps(project, runId, values);
                case "record" -> record(project, runId, values);
                case "bidi" -> bidi(extraArgs);
                default -> fail(2, "unknown agent command: " + op);
            };
        }
    }

    private static Result injectLive(Path project, Map<String, String> values, String agentId) throws IOException {
        String mode = values.get("mode");
        if (!"on".equals(mode) && !"off".equals(mode)) {
            return refusal("error: inject needs on or off\nfix: inject on --session=<id>");
        }
        String agent = liveAgent(values, agentId);
        SpineSession.setInject(project, values.get("session"), "on".equals(mode), agent, values.get("mutate"));
        Map<String, Object> body = SpineSession.envelope(project, values.get("session"), agent, "inject");
        stampLiveFrame(body);
        return new Result(0, body);
    }

    private static Result inputBlockLive(Path project, Map<String, String> values, String agentId) throws IOException {
        boolean on = "true".equals(values.get("on"));
        boolean off = "true".equals(values.get("off"));
        if (on == off) {
            return refusal("error: input block needs --on or --off\nfix: input block --session=<id> --on");
        }
        String session = SpineSession.required(project, values.get("session"));
        if (on && !SpineSession.injectEnabled(project, session)) {
            return refusal("error: input block needs an injected session\nfix: inject on");
        }
        HumanLock.blockInput(project, session, on);
        Map<String, Object> body = ok(on ? "input block on" : "input block off");
        body.put("session", session);
        body.put("inputBlocked", on);
        body.put("agent", liveAgent(values, agentId));
        stampLiveFrame(body);
        return new Result(0, body);
    }

    private static Result leaseLive(Path project, Map<String, String> values, String agentId) throws IOException {
        String action = values.get("action");
        if (!"take".equals(action) && !"release".equals(action)) {
            return refusal("error: lease needs take or release\nfix: lease take --target=ui|browser|both --session=<id>");
        }
        String target = values.get("target");
        String holder = liveAgent(values, agentId);
        String session;
        try {
            session = SpineSession.required(project, values.get("session"));
            if ("take".equals(action)) {
                UiLease.take(project, session, target, holder);
                String use = target == null ? "" : target.trim();
                if ("browser".equals(use) || "both".equals(use)) {
                    tools.dscode.control.protocol.LeaseClock.armHold(project, "hold");
                }
            } else {
                UiLease.release(project, session, target);
            }
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage();
            if (message == null || !message.contains("fix:")) {
                message = "error: lease failed\nfix: lease take --target=ui|browser|both --session=<id>";
            }
            return refusal(message);
        }
        Map<String, Object> body = ok("lease " + action);
        body.put("session", session);
        body.put("target", target == null ? "" : target.trim());
        body.put("holder", holder);
        stampLiveFrame(body);
        return new Result(0, body);
    }

    /** Agent {@code input --text} types on the live page, inside the shield pass when one is up. */
    private static Result inputLive(Path project, String runId, Map<String, String> values) {
        String text = values.get("text");
        if (values.containsKey("actions") || values.containsKey("release")) {
            return refusal("error: input --actions and --release run in the protocol host\nfix: input --actions=<json>|@chain.json [--release] (no --session forward)");
        }
        if (text == null || text.isBlank()) {
            return refusal("error: input needs --text, --actions or --release\nfix: input --text=<keys> [--at=<short id>] --session=<id>, or input --actions=<json>");
        }
        if (values.containsKey("at")) {
            String session = values.get("session");
            boolean blocked = HumanLock.inputBlocked(project, session);
            if (blocked) HumanLock.passAgentInput(project, session);
            try {
                tools.dscode.common.agentfast.TargetVerb.Outcome outcome =
                        tools.dscode.common.agentfast.TargetVerb.insertTextAt(project, runId, values.get("at"), text);
                Map<String, Object> body = new LinkedHashMap<>(outcome.body());
                stampLiveFrame(body);
                return new Result(outcome.exit(), body);
            } finally {
                if (blocked) HumanLock.restoreInputBlock(project, session);
            }
        }
        WebDriver page = tools.dscode.common.agentfast.LiveBrowserHost.page();
        if (!(page instanceof tools.dscode.common.agentfast.LiveChromeDriver driver)) {
            return fail(1, "error: no live page\nfix: start --url= --browser=");
        }
        String session = values.get("session");
        boolean blocked = HumanLock.inputBlocked(project, session);
        if (blocked) HumanLock.passAgentInput(project, session);
        try {
            tools.dscode.common.composite.ElementOperations.script(driver, "if(document.body){document.body.setAttribute('tabindex','-1');document.body.focus();}");
            for (int index = 0; index < text.length(); index++) {
                String key = String.valueOf(text.charAt(index));
                Map<String, Object> down = new LinkedHashMap<>();
                down.put("type", "keyDown");
                down.put("key", key);
                down.put("text", key);
                driver.executeCdpCommand("Input.dispatchKeyEvent", down);
                Map<String, Object> up = new LinkedHashMap<>();
                up.put("type", "keyUp");
                up.put("key", key);
                driver.executeCdpCommand("Input.dispatchKeyEvent", up);
            }
        } finally {
            if (blocked) HumanLock.restoreInputBlock(project, session);
        }
        Map<String, Object> body = ok("input");
        body.put("text", text);
        stampLiveFrame(body);
        return new Result(0, body);
    }

    private static String liveAgent(Map<String, String> values, String agentId) {
        String named = values == null ? null : values.get("agent");
        if (named != null && !named.isBlank()) return named.trim();
        if (agentId != null && !agentId.isBlank()) return agentId.trim();
        return SpineSession.DEFAULT_AGENT;
    }

    /** A frame id from this process's browser. {@code live} means the page JVM handled the call. */
    private static void stampLiveFrame(Map<String, Object> body) {
        if (body == null) return;
        Object existing = body.get("frame");
        if (existing instanceof String text && !text.isBlank()) return;
        String frame = liveFrame();
        if ((frame == null || frame.isBlank()) && tools.dscode.common.agentfast.LiveBrowserHost.page() != null) frame = "live";
        if (frame != null && !frame.isBlank()) body.put("frame", frame);
    }

    private static Result expandRef(Map<String, String> args) {
        String id = args.get("id");
        if (id == null || id.isBlank()) id = args.get("ref");
        if (id == null || id.isBlank()) id = args.get("target");
        String full = tools.dscode.common.agentfast.SessionIds.expand(id);
        if (full == null) {
            Result failed = fail(1, "unknown ref");
            failed.body().put("fix", "pass --refind=chain");
            return failed;
        }
        Map<String, Object> body = ok("Expanded ref.");
        body.put("op", "ref");
        body.put("id", id);
        body.put("ref", full);
        body.put("value", full);
        return new Result(0, body);
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
                project, runId, args.get("channel"), args.get("source"), agentId, args.get("text"),
                "", args.get("ref"), args.get("action")
        );
        Map<String, Object> body = ok("Chat message stored for this run channel.");
        body.put("message", ChatLog.toMap(message));
        boolean delivered = "run".equals(message.channel()) && PageSurfaces.pushChat(panelRow(message));
        body.put("sentId", message.id());
        body.put("status", delivered ? "delivered" : "stored");
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

    /** panel-state reads the toolbar dock (position, collapsed, its buttons), the panel, the step editor, and recording. */
    @SuppressWarnings("unchecked")
    private static Result panelStateRead() throws Exception {
        if (!PageSurfaces.hasLivePage()) return fail(1, "no live browser");
        String reply = PageSurfaces.runtimeCall("dockState", null, false);
        Map<String, Object> body = ok("Toolbar and panel state.");
        body.put("op", "panel-state");
        if (reply == null || reply.isBlank() || "null".equals(reply.trim())) {
            body.put("dock", null);
            body.put("panel", null);
            body.put("recording", "off");
            return new Result(0, body);
        }
        body.putAll(AgentStore.JSON.readValue(reply, Map.class));
        return new Result(0, body);
    }

    /** navigate keeps the session and, when the panel was open, opens it again on the new page with its tab. */
    @SuppressWarnings("unchecked")
    private static Result navigate(Path project, String runId, Map<String, String> values, List<String> extraArgs, String agentId) throws Exception {
        String before = null;
        try {
            before = PageSurfaces.runtimeCall("panelState", null, false);
        } catch (Exception ignored) {
            // No page yet: nothing to keep.
        }
        tools.dscode.common.agentfast.TargetVerb.Outcome outcome =
                tools.dscode.common.agentfast.TargetVerb.run(project, runId, "navigate", values);
        if (outcome.exit() != 0) return new Result(outcome.exit(), outcome.body());
        tools.dscode.common.recording.RecordControl.noteAgent(project, runId, "navigate", values, outcome.body());
        Map<String, Object> body = outcome.body() instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        if (before != null && !before.isBlank() && !"null".equals(before.trim())) {
            Map<String, Object> state = AgentStore.JSON.readValue(before, Map.class);
            Map<String, String> open = new LinkedHashMap<>();
            Object tab = state.get("tab");
            if (tab != null && !String.valueOf(tab).isBlank()) open.put("tab", String.valueOf(tab));
            if (Boolean.TRUE.equals(state.get("min"))) open.put("minimized", "true");
            Result reopened = panelOpen(project, runId, open, extraArgs, agentId);
            body.put("panel", reopened.exit() == 0 ? "kept" : "pending");
        }
        return new Result(0, body);
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
        if (present(tab) && !tab.trim().matches("chat|steps|guide|record|recorder")) return fail(2, "panel-open --tab must be chat, guide (steps), or recorder (record)");
        if (present(tab)) spec.put("tab", tab.trim());
        String theme = args.get("theme");
        if (present(theme) && !theme.trim().matches("auto|light|dark")) return fail(2, "panel-open --theme must be auto, light, or dark");
        if (present(theme)) spec.put("theme", theme.trim());
        if (present(args.get("title"))) spec.put("title", args.get("title").trim());
        if (present(args.get("minimized"))) spec.put("min", Boolean.parseBoolean(args.get("minimized").trim()));
        if (present(args.get("collapsed"))) {
            // --collapsed=true (or panel) puts the panel on its toolbar button; toolbar folds the whole dock to its handle.
            String collapsed = args.get("collapsed").trim().toLowerCase(java.util.Locale.ROOT);
            switch (collapsed) {
                case "true", "panel" -> spec.put("min", true);
                case "toolbar" -> spec.put("dockCollapsed", true);
                case "false" -> { spec.put("min", false); spec.put("dockCollapsed", false); }
                default -> { return fail(2, "panel-open --collapsed must be true, panel, toolbar, or false"); }
            }
        }
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
        Map<?, ?> panelState = AgentStore.JSON.readValue(reply, Map.class);
        body.put("panel", panelState);
        // panel.tab is the pane id (steps, record); tab is the name panel-open --tab takes.
        body.put("tab", publicTab(String.valueOf(panelState.get("tab"))));
        return new Result(0, body);
    }

    static String publicTab(String pane) {
        return switch (pane == null ? "" : pane) {
            case "steps" -> "guide";
            case "record" -> "recorder";
            case "null", "" -> "chat";
            default -> pane;
        };
    }

    private static final java.util.Set<String> STEP_ACTIONS = java.util.Set.of("click", "input", "select", "check", "any");

    /**
     * A flag value, or the text of {@code @file}. Relative paths are from the project.
     * A missing file names that directory.
     */
    static String readAtFile(Path project, String value, String flag) {
        if (value == null) return "";
        String text = value.trim();
        if (!text.startsWith("@")) return value;
        Path base = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
        Path file = Path.of(text.substring(1).trim());
        if (!file.isAbsolute()) file = base.resolve(file).normalize();
        else file = file.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new IllegalArgumentException("error: " + flag + " file not found: " + file
                    + "\nfix: pass a path relative to the project (" + base + "), or an absolute path");
        }
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("error: cannot read " + file + ": " + failure.getMessage()
                    + "\nfix: pass a path relative to the project (" + base + "), or an absolute path");
        }
    }

    /** Reads {@code --steps} as a JSON array, or {@code @file} relative to the project. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> guideSteps(Path project, String raw) throws Exception {
        if (!present(raw)) throw new IllegalArgumentException("guide requires --steps=<json array> or --steps=@file.json");
        String text = raw.trim();
        if (text.startsWith("@")) {
            Path base = project == null ? Path.of("").toAbsolutePath().normalize() : project.toAbsolutePath().normalize();
            Path file = Path.of(text.substring(1));
            if (!file.isAbsolute()) file = base.resolve(file).normalize();
            else file = file.toAbsolutePath().normalize();
            if (!java.nio.file.Files.isRegularFile(file)) {
                throw new IllegalArgumentException("steps file not found: " + file + " (relative paths are from " + base + ")");
            }
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
            Object role = map.get("role");
            Object name = map.get("name");
            boolean hasTarget = t != null && !String.valueOf(t).isBlank();
            boolean hasRole = role != null && !String.valueOf(role).isBlank() && name != null && !String.valueOf(name).isBlank();
            if ((!hasTarget && !hasRole) || say == null || String.valueOf(say).isBlank()) {
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

    public static boolean livePage() {
        return PageSurfaces.hasLivePage();
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
        if ("feedback".equals(args.get("component"))) {
            Map<String, Object> report = SelftestUi.feedback(project);
            return new Result(Boolean.TRUE.equals(report.get("ok")) ? 0 : 1, report);
        }
        if ("pages".equals(args.get("component"))) {
            Map<String, Object> report = tools.dscode.common.agentfast.SelftestUi.pages(project);
            if (Boolean.TRUE.equals(report.get("skipped"))) return new Result(1, report);
            return new Result(Boolean.TRUE.equals(report.get("ok")) ? 0 : 1, report);
        }
        if ("true".equals(args.get("record"))) {
            Map<String, Object> report = RecordSelftest.run(project);
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
        for (Map<String, Object> step : steps) {
            Object t = step.get("t");
            if ((t == null || String.valueOf(t).isBlank()) && step.get("role") != null && step.get("name") != null) {
                String role = String.valueOf(step.get("role")).trim();
                String name = String.valueOf(step.get("name")).replace("\"", "").trim();
                Object frameHint = step.get("frame");
                String published = tools.dscode.common.agentfast.TargetVerb.shortIdFor(
                        role, name, frameHint == null ? "" : String.valueOf(frameHint));
                step.put("t", published != null ? published : "role:" + role + "[name=\"" + name + "\"]");
            }
            Object token = step.get("t");
            if (token != null) {
                String tokenText = String.valueOf(token);
                int[] box = tools.dscode.common.agentfast.TargetVerb.boxForHandle(tokenText);
                if (box == null || box.length < 4 || (box[2] <= 0 && box[3] <= 0)) {
                    box = PageSurfaces.viewportBox(tokenText);
                }
                if (box != null && box.length >= 4 && (box[2] > 0 || box[3] > 0)) {
                    step.put("box", Map.of("x", box[0], "y", box[1], "w", box[2], "h", box[3]));
                }
                String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(tokenText);
                if (placed != null && placed.contains(":") && !placed.startsWith("top:")) {
                    step.put("frame", placed.substring(0, placed.lastIndexOf(':')));
                }
            }
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", present(args.get("id")) ? args.get("id").trim() : "g1");
        spec.put("steps", steps);
        spec.put("scroll", "true".equalsIgnoreCase(String.valueOf(args.get("scroll"))));
        String reply = PageSurfaces.runtimeCall("guide", spec, true);
        if (reply == null || "null".equals(reply)) return fail(1, "guide did not start on the page");
        CompositeLog.info("guide", "start " + reply);
        Map<String, Object> parsed = AgentStore.JSON.readValue(reply, Map.class);
        if (truth(parsed.get("unresolved"))) {
            try {
                PageSurfaces.runtimeCall("guideClose", spec.get("id"), false);
            } catch (RuntimeException ignored) {
                // The empty card is already hidden. The refusal still stands.
            }
            String flag = String.valueOf(parsed.getOrDefault("flag", "step target was not found"));
            Result failed = fail(1, flag);
            failed.body().put("op", "guide");
            failed.body().putAll(parsed);
            return failed;
        }
        Map<String, Object> body = ok("Guide shown on the page; steps advance as the person acts.");
        body.put("op", "guide");
        body.putAll(parsed);
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
        if (present(args.get("type"))) return typedAsk(project, runId, args);
        if (!present(args.get("say"))) return fail(2, "ask requires --say=<question>");
        String say;
        try {
            say = readAtFile(project, args.get("say"), "say").trim();
        } catch (IllegalArgumentException missing) {
            return fail(2, missing.getMessage());
        }
        if (say.isBlank()) return fail(2, "ask requires --say=<question>");
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("id", present(args.get("id")) ? args.get("id").trim() : "q" + ASK_SEQ.incrementAndGet());
        spec.put("say", say);
        if (present(args.get("choices"))) {
            List<String> choices = choiceList(args.get("choices"));
            if (choices.size() > 6) return fail(2, "ask --choices holds at most 6 answers");
            if (!choices.isEmpty()) spec.put("choices", choices);
        }
        if (!PageSurfaces.hasLivePage()) return fail(1, "ask needs a live browser page; run launch first");
        if (present(args.get("handle"))) spec.put("t", expandHandle(args.get("handle")));
        if ("true".equalsIgnoreCase(String.valueOf(args.get("sensitive")))) spec.put("sensitive", true);
        if (present(args.get("min-show"))) {
            try {
                spec.put("minShow", timeoutMillis(Map.of("timeout", args.get("min-show")), 0L));
            } catch (NumberFormatException failure) {
                return fail(2, "ask --min-show is milliseconds");
            }
        }
        String reply = PageSurfaces.runtimeCall("ask", spec, true);
        if (reply == null || "null".equals(reply)) return fail(1, "ask did not show on the page");
        List<String> shownChoices = spec.get("choices") instanceof List<?> listed
                ? listed.stream().map(String::valueOf).toList()
                : List.of();
        AskCards.open(
                project,
                args.get("session"),
                String.valueOf(spec.get("id")),
                shownChoices.isEmpty() ? "text" : "choice",
                String.valueOf(spec.get("say")),
                shownChoices
        );
        PAGE_CARDS.put(String.valueOf(spec.get("id")), new PageCard(runId, args.get("session"), List.copyOf(shownChoices)));
        CompositeLog.info("ui", "ask " + reply);
        Map<String, Object> body = ok("Question shown on the page; the answer arrives as an answer event in overlay-poll.");
        body.put("op", "ask");
        body.putAll(AgentStore.JSON.readValue(reply, Map.class));
        return new Result(0, body);
    }

    /** Stores the listed choice. A value outside {@code --options} is exit 2. */
    private static Result askAnswer(Path project, String runId, Map<String, String> args) throws Exception {
        String session = present(args.get("session")) ? args.get("session") : runId;
        String id = args.get("id");
        if (present(id)) {
            Result page = answerPageCard(project, id.trim(), args.get("value"));
            if (page != null) return page;
        }
        AskCards.answer(project, session, id, args.get("value"));
        Map<String, Object> card = AskCards.answered(project, session);
        Map<String, Object> body = ok("Answer stored. ui-poll returns type and value.");
        body.put("op", "ask-answer");
        if (card != null) {
            body.put("id", card.get("id"));
            body.put("type", card.get("type"));
            body.put("value", card.get("value"));
            body.put("session", card.get("session"));
        }
        return new Result(0, body);
    }

    /**
     * HCR-9: a page card ({@code ask --say}) is answered like a click on it. The answer row goes to the run's
     * overlay events, where {@code ask-and-wait}, {@code answer-wait} and {@code ui-poll} read answers, and the
     * card closes on the page. Null when no page card with this id is known, so the stored-card error stays.
     */
    private static Result answerPageCard(Path project, String id, String value) throws Exception {
        String useValue = value == null ? "" : value.trim();
        PageCard shown = PAGE_CARDS.get(id);
        if (shown == null) return null;
        String runId = shown.runId();
        if (!shown.choices().isEmpty() && !shown.choices().contains(useValue) && !useValue.isBlank()) {
            return fail(2, "error: option is not listed\nfix: use one of " + String.join(", ", shown.choices()));
        }
        if (useValue.isBlank()) return fail(2, "error: missing answer\nfix: ask-answer --id=" + id + " --value=<one of the card's choices>");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "answer");
        row.put("id", id);
        row.put("choice", useValue);
        row.put("value", useValue);
        row.put("by", "agent");
        row.put("source", "ask-answer");
        row.put("at", AgentStore.now());
        AgentStore.appendCapped(AgentStore.root(project, runId).resolve("overlay-events.jsonl"), AgentStore.JSON.writeValueAsString(row));
        tools.dscode.common.agentfast.PushWake.signal();
        boolean closed = false;
        if (PageSurfaces.hasLivePage()) {
            try {
                PageSurfaces.runtimeCall("askClose", id, false);
                closed = true;
            } catch (RuntimeException failure) {
                CompositeLog.info("ui", "ask-answer close skipped " + failure.getMessage());
            }
        }
        PAGE_CARDS.remove(id);
        try {
            AskCards.answer(project, shown.session(), id, useValue);
        } catch (IllegalArgumentException | IOException ignored) {
            // The stored copy is a convenience for ui-poll; the answer event above is the answer.
        }
        Map<String, Object> body = ok("Page card answered.");
        body.put("op", "ask-answer");
        body.put("id", id);
        body.put("type", "page");
        body.put("value", useValue);
        body.put("closed", closed);
        return new Result(0, body);
    }

    /** A page card ({@code ask --say}) this process showed: the asking run id, its session, and its choices. */
    record PageCard(String runId, String session, List<String> choices) {
    }

    /** Page cards by card id, so any run id may answer the card the asker is waiting on. */
    static final Map<String, PageCard> PAGE_CARDS = new java.util.concurrent.ConcurrentHashMap<>();

    /** A card with no live page. {@code choice} answers must be one of {@code --options}. */
    private static Result typedAsk(Path project, String runId, Map<String, String> args) throws Exception {
        String type = args.get("type").trim().toLowerCase(java.util.Locale.ROOT);
        if (!type.equals("text") && !type.equals("choice") && !type.equals("confirm")) {
            return fail(2, "ask --type is text, choice, or confirm");
        }
        String promptRaw = present(args.get("prompt")) ? args.get("prompt") : present(args.get("say")) ? args.get("say") : "";
        String prompt;
        try {
            prompt = readAtFile(project, promptRaw, present(args.get("prompt")) ? "prompt" : "say").trim();
        } catch (IllegalArgumentException missing) {
            return fail(2, missing.getMessage());
        }
        if (prompt.isBlank()) return fail(2, "ask --type requires --prompt");
        List<String> options = choiceList(present(args.get("options")) ? args.get("options") : args.get("choices"));
        if ("choice".equals(type) && options.isEmpty()) return fail(2, "ask --type=choice requires --options");
        if ("confirm".equals(type) && options.isEmpty()) options = List.of("yes", "no");
        String id = present(args.get("id")) ? args.get("id").trim() : "";
        Map<String, Object> card = AskCards.open(project, args.get("session"), id, type, prompt, options);
        Map<String, Object> body = ok("Question card stored. ui-poll returns type and value after the answer.");
        body.put("op", "ask");
        body.put("session", card.get("session"));
        body.putAll(card);
        // HCR-9: with a live page the typed card is shown too (choice and confirm as buttons, text as a field).
        boolean shown = false;
        if (PageSurfaces.hasLivePage()) {
            String cardId = String.valueOf(card.get("id"));
            Map<String, Object> spec = new LinkedHashMap<>();
            spec.put("id", cardId);
            spec.put("say", prompt);
            if (!"text".equals(type)) spec.put("choices", options);
            try {
                String reply = PageSurfaces.runtimeCall("ask", spec, true);
                shown = reply != null && !"null".equals(reply);
            } catch (RuntimeException failure) {
                CompositeLog.info("ui", "typed ask not shown " + failure.getMessage());
            }
            if (shown) PAGE_CARDS.put(cardId, new PageCard(runId, args.get("session"), "text".equals(type) ? List.of() : List.copyOf(options)));
        }
        body.put("shown", shown);
        return new Result(0, body);
    }

    private static Result askAndWait(Path project, String runId, Map<String, String> args) throws Exception {
        if (asyncAsked(args)) {
            Result shown = ask(project, runId, args);
            if (shown.exit() != 0) return shown;
            String id = String.valueOf(shown.body().getOrDefault("id", ""));
            Map<String, Object> body = new LinkedHashMap<>(shown.body());
            body.put("op", "ask-and-wait");
            body.put("async", true);
            body.put("id", id);
            body.put("sentence", "Question shown. answer-wait --id=" + id + " reads the choice without holding the command lane.");
            tools.dscode.control.protocol.AgentFastLog.log(project, "wait", "async id=" + id);
            return new Result(0, body);
        }
        int before = inputCount(project, runId);
        long started = System.currentTimeMillis();
        Result shown = ask(project, runId, args);
        if (shown.exit() != 0) return shown;
        String id = String.valueOf(shown.body().getOrDefault("id", ""));
        List<String> choices = choiceList(args.get("choices"));
        long timeout;
        try {
            timeout = timeoutMillis(args, 3000L);
        } catch (NumberFormatException failure) {
            PageSurfaces.runtimeCall("askClose", id, false);
            return fail(2, "timeout is milliseconds");
        }
        String value = pollAnswer(project, runId, before, started, id, choices, timeout);
        if (value == null) {
            PageSurfaces.runtimeCall("askTimeout", id, false);
            Result failed = fail(1, "timeout");
            failed.body().put("op", "ask-and-wait");
            failed.body().put("id", id);
            return failed;
        }
        return answered("ask-and-wait", id, value);
    }

    /**
     * Polls an ask that {@code ask} or {@code ask-and-wait --async} already placed.
     * The same fields as a finished {@code ask-and-wait}: {@code id}, {@code value},
     * {@code choice}, {@code applied}.
     */
    private static Result answerWait(Path project, String runId, Map<String, String> args) throws Exception {
        if (!present(args.get("id"))) return fail(2, "answer-wait requires --id");
        String id = args.get("id").trim();
        List<String> choices = choiceList(args.get("choices"));
        long timeout;
        try {
            timeout = timeoutMillis(args, 3000L);
        } catch (NumberFormatException failure) {
            return fail(2, "timeout is milliseconds");
        }
        int before = inputCount(project, runId);
        long started = System.currentTimeMillis();
        tools.dscode.control.protocol.AgentFastLog.log(project, "wait", "answer-wait id=" + id);
        String value = pollAnswer(project, runId, before, started, id, choices, timeout);
        if (value == null) {
            PageSurfaces.runtimeCall("askTimeout", id, false);
            Result failed = fail(1, "timeout");
            failed.body().put("op", "answer-wait");
            failed.body().put("id", id);
            return failed;
        }
        return answered("answer-wait", id, value);
    }

    /**
     * Returns the first new answer, chat line, or click. Events already stored are ignored.
     * {@code fired} is {@code answer}, {@code chat}, or {@code watch}. A tie on {@code at} prefers
     * answer, then chat, then watch. The wait does not hold the command lane.
     */
    private static Result waitAny(Path project, String runId, Map<String, String> args) throws Exception {
        String answerId = present(args.get("answer")) ? args.get("answer").trim() : "";
        String chatMode = present(args.get("chat")) ? args.get("chat").trim().toLowerCase(java.util.Locale.ROOT) : "";
        String watch = present(args.get("watch")) ? args.get("watch").trim() : "";
        if (answerId.isEmpty() && chatMode.isEmpty() && watch.isEmpty()) {
            return fail(2, "wait-any needs --answer, --chat, or --watch");
        }
        if (!chatMode.isEmpty() && !chatMode.equals("human") && !chatMode.equals("agent") && !chatMode.equals("any")) {
            return fail(2, "wait-any --chat is human, agent, or any");
        }
        String watchId = "";
        if (!watch.isEmpty()) {
            int colon = watch.indexOf(':');
            String kind = colon < 0 ? "" : watch.substring(colon + 1).trim();
            watchId = colon <= 0 ? "" : watch.substring(0, colon).trim();
            if (watchId.isEmpty() || !"click".equals(kind)) {
                return fail(2, "wait-any --watch is <short id>:click");
            }
        }
        String watchWarning = tools.dscode.common.agentfast.SessionIds.foreignWarning(watchId, runId);
        long timeout;
        try {
            timeout = timeoutMillis(args, 30_000L);
        } catch (NumberFormatException failure) {
            return fail(2, "timeout is milliseconds");
        }
        int before = inputCount(project, runId);
        String chatAfter = "0";
        try {
            chatAfter = ChatLog.latestId(project, runId, "run");
        } catch (RuntimeException | IOException ignored) {
            chatAfter = "0";
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        long drainedAt = 0L;
        while (true) {
            long wakeSeen = tools.dscode.common.agentfast.PushWake.generation();
            // HCR-12: panel lines also wait in the page queue; read it here as chat-poll does (at most every 250 ms).
            if (!chatMode.isEmpty() && System.nanoTime() - drainedAt > 250_000_000L) {
                drainedAt = System.nanoTime();
                try {
                    PageSurfaces.drainPanelChat(project, runId);
                } catch (IOException | RuntimeException failure) {
                    CompositeLog.info("ui", "wait-any panel drain skipped " + failure.getMessage());
                }
            }
            Result hit = waitAnyHit(project, runId, before, chatAfter, answerId, chatMode, watchId);
            if (hit != null) return warned(hit, watchWarning);
            if (System.nanoTime() >= deadline) {
                hit = waitAnyHit(project, runId, before, chatAfter, answerId, chatMode, watchId);
                if (hit != null) return warned(hit, watchWarning);
                Result failed = fail(1, "timeout");
                failed.body().put("op", "wait-any");
                return warned(failed, watchWarning);
            }
            tools.dscode.common.agentfast.PushWake.await(wakeSeen, 50, deadline);
        }
    }

    private static Result warned(Result result, String warning) {
        if (warning != null && !warning.isEmpty() && result.body() != null) result.body().put("warning", warning);
        return result;
    }

    /** The earliest matching event in this pass, or null when nothing new matches. */
    private static Result waitAnyHit(
            Path project,
            String runId,
            int before,
            String chatAfter,
            String answerId,
            String chatMode,
            String watchId
    ) throws Exception {
        WaitPick pick = new WaitPick();
        if (!answerId.isEmpty()) {
            try {
                for (Map<String, Object> event : PageSurfaces.pollInput(project, runId, before)) {
                    String picked = acceptedChoice(event, answerId, null);
                    if (picked == null) continue;
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("fired", "answer");
                    fields.put("id", answerId);
                    fields.put("value", picked);
                    fields.put("choice", picked);
                    pick.offer(event.get("at"), 0, fields);
                }
            } catch (RuntimeException | IOException failure) {
                CompositeLog.info("ui", "wait-any answer poll skipped " + failure.getMessage());
            }
        }
        if (!chatMode.isEmpty()) {
            try {
                for (ChatLog.Message message : ChatLog.poll(project, runId, "run", chatAfter)) {
                    String source = message.source() == null ? "" : message.source();
                    if (!chatSourceMatches(chatMode, source)) continue;
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("fired", "chat");
                    fields.put("id", message.id());
                    fields.put("text", message.text());
                    fields.put("source", source);
                    pick.offer(message.at(), 1, fields);
                }
            } catch (RuntimeException | IOException failure) {
                CompositeLog.info("ui", "wait-any chat poll skipped " + failure.getMessage());
            }
        }
        if (!watchId.isEmpty()) {
            try {
                for (Map<String, Object> event : PageSurfaces.pollInput(project, runId, before)) {
                    String type = String.valueOf(event.get("type")).toLowerCase(java.util.Locale.ROOT);
                    if (!type.contains("click")) continue;
                    String id = event.get("id") == null ? "" : String.valueOf(event.get("id"));
                    String ref = event.get("ref") == null ? "" : String.valueOf(event.get("ref"));
                    String handle = event.get("handle") == null ? "" : String.valueOf(event.get("handle"));
                    // A page click carries the element's full ref, not the short id the agent was given.
                    String full = tools.dscode.common.agentfast.SessionIds.expand(watchId);
                    boolean byFull = full != null && !full.equals(watchId) && (full.equals(ref) || full.equals(handle) || full.equals(id));
                    if (!byFull && !watchId.equals(id) && !watchId.equals(ref) && !watchId.equals(handle)) continue;
                    Map<String, Object> fields = new LinkedHashMap<>();
                    fields.put("fired", "watch");
                    fields.put("id", watchId);
                    pick.offer(event.get("at"), 2, fields);
                }
            } catch (RuntimeException | IOException failure) {
                CompositeLog.info("ui", "wait-any watch poll skipped " + failure.getMessage());
            }
        }
        if (pick.fields == null) return null;
        String fired = String.valueOf(pick.fields.get("fired"));
        Map<String, Object> body = ok("wait-any fired " + fired);
        body.put("op", "wait-any");
        body.putAll(pick.fields);
        return new Result(0, body);
    }

    /** {@code any} is human or agent. A system line, including an overlay echo, is not a chat hit. */
    private static boolean chatSourceMatches(String mode, String source) {
        if (source == null || source.isBlank() || "system".equals(source)) return false;
        if ("any".equals(mode)) return "human".equals(source) || "agent".equals(source);
        return mode.equals(source);
    }

    /** Earliest {@code at} wins. An equal stamp prefers answer, then chat, then watch. */
    private static final class WaitPick {
        private String at = "\uffff";
        private int rank = 9;
        private Map<String, Object> fields;

        private void offer(Object stamp, int rank, Map<String, Object> fields) {
            String at = stamp == null || String.valueOf(stamp).isBlank() ? "\uffff" : String.valueOf(stamp);
            if (this.fields != null && (at.compareTo(this.at) > 0 || (at.equals(this.at) && rank >= this.rank))) return;
            this.at = at;
            this.rank = rank;
            this.fields = fields;
        }
    }

    private static boolean asyncAsked(Map<String, String> args) {
        if (args == null) return false;
        String value = args.get("async");
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }

    /** Milliseconds. A trailing {@code ms} or {@code s} is accepted. A bare number stays milliseconds. */
    private static long timeoutMillis(Map<String, String> args, long fallback) {
        if (!present(args.get("timeout"))) return fallback;
        String raw = args.get("timeout").trim().toLowerCase(java.util.Locale.ROOT);
        boolean seconds = raw.endsWith("s") && !raw.endsWith("ms");
        if (raw.endsWith("ms")) raw = raw.substring(0, raw.length() - 2).trim();
        else if (raw.endsWith("s")) raw = raw.substring(0, raw.length() - 1).trim();
        double parsed = Double.parseDouble(raw);
        long millis = seconds ? Math.round(parsed * 1000.0) : Math.round(parsed);
        return Math.max(0L, millis);
    }

    /** HCR-27: blocking replies that woke on a page push say so, like wait and ask-and-wait. */
    static final java.util.Set<String> WAKE_OPS = java.util.Set.of("chat-turn", "watch");

    static void wakeFields(Map<String, Object> body) {
        if (body == null) return;
        body.putIfAbsent("wake", tools.dscode.common.agentfast.PushWake.mode());
        long signalled = tools.dscode.common.agentfast.PushWake.lastSignalNanos();
        if (signalled > 0 && !body.containsKey("wakeMs")) {
            body.put("wakeMs", Math.max(0, (System.nanoTime() - signalled) / 1_000_000L));
        }
    }

    private static Result answered(String op, String id, String value) {
        Map<String, Object> body = ok("Answer received.");
        body.put("op", op);
        body.put("id", id);
        body.put("value", value);
        body.put("choice", value);
        body.put("applied", !value.isBlank());
        body.put("wake", tools.dscode.common.agentfast.PushWake.mode());
        long signalled = tools.dscode.common.agentfast.PushWake.lastSignalNanos();
        if (signalled > 0) body.put("wakeMs", Math.max(0, (System.nanoTime() - signalled) / 1_000_000L));
        return new Result(0, body);
    }

    /** The clicked label, or null when the deadline passes with no answer for this id. */
    private static String pollAnswer(
            Path project,
            String runId,
            int before,
            long started,
            String id,
            List<String> choices,
            long timeout
    ) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeout));
        while (true) {
            long wakeSeen = tools.dscode.common.agentfast.PushWake.generation();
            try {
                for (Map<String, Object> event : PageSurfaces.pollInput(project, runId, before)) {
                    String picked = acceptedChoice(event, id, choices);
                    if (picked != null) return picked;
                }
            } catch (RuntimeException | java.io.IOException failure) {
                CompositeLog.info("ui", "ask poll skipped " + failure.getMessage());
            }
            Map<String, String> stored = AnswerRecovery.find(project, runId, started, id, choices);
            if (stored != null && !stored.getOrDefault("value", "").isBlank()) return stored.get("value");
            if (System.nanoTime() >= deadline) {
                stored = AnswerRecovery.find(project, runId, started, id, choices);
                if (stored != null && !stored.getOrDefault("value", "").isBlank()) return stored.get("value");
                return null;
            }
            tools.dscode.common.agentfast.PushWake.await(wakeSeen, 50, deadline);
        }
    }

    /** The one clicked label, or null when this event is not that answer. A joined label list is not a choice. */
    static String acceptedChoice(Map<String, Object> event, String id, List<String> choices) {
        if (event == null || !"answer".equals(String.valueOf(event.get("type")))) return null;
        if (id != null && !id.isBlank() && !id.equals(String.valueOf(event.get("id")))) return null;
        Object raw = event.get("choice");
        if (raw == null || String.valueOf(raw).isBlank()) raw = event.get("value");
        String value = raw == null ? "" : String.valueOf(raw).replaceAll("\\s+", " ").trim();
        if (choices == null || choices.size() < 2) return value;
        for (String choice : choices) if (choice.equals(value)) return choice;
        if (value.equals(String.join(" ", choices))) return null;
        return null;
    }

    /**
     * Comma list or a JSON array. Spaces stay inside one label. A JSON label may contain a comma.
     * Blank and duplicate labels are dropped. More than six is the caller's error.
     */
    public static List<String> choiceList(String raw) {
        List<String> choices = new ArrayList<>();
        if (raw == null || raw.isBlank()) return choices;
        String text = raw.trim();
        if (text.startsWith("[")) {
            try {
                List<?> parsed = AgentStore.JSON.readValue(text, List.class);
                for (Object item : parsed) pushChoice(choices, item == null ? "" : String.valueOf(item));
                return choices;
            } catch (RuntimeException | java.io.IOException ignored) {
                // Not a JSON array. Fall through to the comma list.
            }
        }
        for (String part : text.split(",", -1)) pushChoice(choices, part);
        return choices;
    }

    private static void pushChoice(List<String> choices, String raw) {
        if (raw == null) return;
        String value = raw.trim();
        if (value.isEmpty() || choices.contains(value)) return;
        choices.add(value);
    }

    /** Events already stored before this ask. A later answer is the only one this call accepts. */
    private static int inputCount(Path project, String runId) {
        try {
            return PageSurfaces.pollInput(project, runId).size();
        } catch (RuntimeException | java.io.IOException ignored) {
            return 0;
        }
    }

    /** pin, toast, checklist, and the status banner. Toast leaves the page on its own. */
    private static Result surfaceCard(Path project, String runId, String op, Map<String, String> args) throws Exception {
        if ("checklist".equals(op) && present(args.get("check"))) return checkItem(args);
        String text;
        if ("checklist".equals(op)) {
            text = checklistHtml(args.get("items"));
            if (text == null) return fail(2, "checklist requires --items");
        } else {
            if (!present(args.get("text"))) return fail(2, op + " requires --text");
            text = "<div data-pkb-card=\"" + op + "\">" + HumanValue.html(args.get("text").trim()) + "</div>";
        }
        int top = nextCardTop();
        Map<String, Object> placed = PageSurfaces.place(
                explicitPage(project, args),
                "append",
                null,
                null,
                text,
                "label",
                24,
                top,
                null,
                AgentStore.root(project, runId)
        );
        placed.put("ok", true);
        placed.put("op", op);
        placed.put("kind", op);
        try {
            PageSurfaces.runtimeCall("parkCards", null, false);
        } catch (RuntimeException ignored) {
            // A page without the runtime keeps the inline position.
        }
        String placedId = String.valueOf(placed.getOrDefault("id", ""));
        for (Map<String, Object> box : liveBoxes()) {
            if (!placedId.equals(String.valueOf(box.get("id")))) continue;
            placed.put("x", box.get("x"));
            placed.put("y", box.get("y"));
            placed.put("width", box.get("w"));
            placed.put("height", box.get("h"));
        }
        stampOverlaps(placed);
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

    /**
     * The last {@code --last} messages (default 20) and the recent card trail, as the person sees the pane.
     * Read-only: the chat cursor does not move and nothing is marked read unless {@code --mark-read=true}.
     */
    private static Result chatRead(Path project, String runId, Map<String, String> args) throws Exception {
        int last = integer(args.get("last"), 20);
        if (last < 1) return fail(2, "chat-read --last is a whole number of at least 1");
        PageSurfaces.drainPanelChat(project, runId);
        List<ChatLog.Message> rows = ChatLog.history(project, runId, args.get("channel"), last);
        java.util.Set<String> read = new java.util.LinkedHashSet<>(ReadMarks.ids(project, runId));
        read.addAll(PageSurfaces.readIds());
        List<Map<String, Object>> messages = new ArrayList<>();
        List<String> marked = new ArrayList<>();
        for (ChatLog.Message message : rows) {
            Map<String, Object> row = ChatLog.toMap(message);
            boolean seen = read.contains(message.id()) || (!message.pageId().isBlank() && read.contains(message.pageId()));
            row.put("read", seen);
            messages.add(row);
            if (!"human".equals(message.source())) continue;
            marked.add(message.id());
            if (!message.pageId().isBlank()) marked.add(message.pageId());
        }
        boolean mark = "true".equalsIgnoreCase(String.valueOf(args.get("mark-read")));
        if (mark && !marked.isEmpty()) {
            ReadMarks.note(project, runId, marked);
            PageSurfaces.markRead(marked);
        }
        Map<String, Object> body = ok(mark ? "Chat read and marked read." : "Chat read; nothing was marked read.");
        body.put("op", "chat-read");
        body.put("count", messages.size());
        body.put("messages", messages);
        body.put("markedRead", mark ? marked.size() : 0);
        body.put("recent", recentCards());
        return new Result(0, body);
    }

    /** The page's card trail: the last few question cards and their state (open, answered, closed, timed-out). */
    static List<Object> recentCards() {
        try {
            String reply = PageSurfaces.runtimeCall("cardTrailRead", null, false);
            if (reply == null || "null".equals(reply)) return List.of();
            Object parsed = AgentStore.JSON.readValue(reply, Object.class);
            return parsed instanceof List<?> list ? new ArrayList<>(list) : List.of();
        } catch (Exception failure) {
            return List.of();
        }
    }

    private static Result chatPoll(Path project, String runId, Map<String, String> args, String agentId) throws Exception {
        String after = cursorAfter(project, runId, args);
        long waitMillis = 0;
        if (present(args.get("wait"))) {
            try {
                waitMillis = tools.dscode.control.protocol.LiveSessionAttach.declaredWaitMs("chat-poll", args);
            } catch (NumberFormatException failure) {
                return fail(2, "chat-poll --wait is seconds, up to 120");
            }
        }
        String from = args.get("from");
        boolean filter = present(from) && !"any".equalsIgnoreCase(from) && !"all".equalsIgnoreCase(from);
        long deadline = System.nanoTime() + waitMillis * 1_000_000L;
        int eventCursor = EventCursor.after(project, runId, agentId);
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, String>> agentEvents = List.of();
        int eventNext = eventCursor;
        String lastRawId = null;
        while (true) {
            long wakeSeen = tools.dscode.common.agentfast.PushWake.generation();
            messages.clear();
            lastRawId = null;
            PageSurfaces.drainPanelChat(project, runId);
            for (ChatLog.Message message : ChatLog.poll(project, runId, args.get("channel"), after)) {
                lastRawId = message.id();
                if (filter && (message.source() == null || !from.equalsIgnoreCase(message.source()))) continue;
                messages.add(ChatLog.toMap(message));
            }
            tools.dscode.control.protocol.AgentEvents.Slice slice =
                    tools.dscode.control.protocol.AgentEvents.after(project, runId, eventCursor);
            agentEvents = slice.events();
            eventNext = slice.next();
            if (!messages.isEmpty() || !agentEvents.isEmpty() || System.nanoTime() >= deadline) break;
            tools.dscode.common.agentfast.PushWake.await(wakeSeen, 200, deadline);
        }
        boolean explicit = present(args.get("after")) || present(args.get("id"));
        boolean peek = "true".equalsIgnoreCase(args.get("peek"));
        // peek skips the chat cursor. ui-poll still consumes host events once.
        boolean consumeEvents = !peek || "true".equalsIgnoreCase(args.get("consume-events"));
        if (!explicit && !peek) {
            String advanceTo = null;
            if (!messages.isEmpty()) {
                Object last = messages.get(messages.size() - 1).get("id");
                if (last != null) advanceTo = String.valueOf(last);
            }
            if (filter && lastRawId != null) advanceTo = lastRawId;
            if (advanceTo != null) ChatCursor.advance(project, runId, args.get("channel"), advanceTo);
        }
        if (consumeEvents) EventCursor.advance(project, runId, agentId, eventNext);
        java.util.List<String> read = new java.util.ArrayList<>();
        for (Map<String, Object> message : messages) {
            Object id = message.get("id");
            if (id != null) read.add(String.valueOf(id));
            Object pageId = message.get("pageId");
            if (pageId != null && !String.valueOf(pageId).isBlank()) read.add(String.valueOf(pageId));
        }
        ReadMarks.note(project, runId, read);
        PageSurfaces.markRead(read);
        Map<String, Object> body = ok("Chat messages after the given id.");
        body.put("count", messages.size());
        body.put("messages", messages);
        body.put("agentEvents", agentEvents);
        return new Result(0, body);
    }

    /**
     * Sends one agent line, then waits for a human message, an answer, or a click.
     * {@code --timeout} is seconds from 0 to 120. The default is 30.
     */
    private static Result chatTurn(Path project, String runId, Map<String, String> args, String agentId) throws Exception {
        if (!present(args.get("text"))) return fail(2, "chat-turn requires --text");
        String until = present(args.get("until")) ? args.get("until").trim().toLowerCase(java.util.Locale.ROOT) : "human";
        if (!until.equals("human") && !until.equals("answer") && !until.equals("click")) {
            return fail(2, "chat-turn --until is human, answer, or click");
        }
        long timeoutMs = 30_000L;
        if (present(args.get("timeout"))) {
            try {
                timeoutMs = secondsOrSuffixed(args.get("timeout"));
            } catch (NumberFormatException failure) {
                return fail(2, "chat-turn --timeout is seconds from 0 to 120");
            }
            if (timeoutMs < 0 || timeoutMs > 120_000L) return fail(2, "chat-turn --timeout is seconds from 0 to 120");
        }
        Map<String, String> send = new LinkedHashMap<>();
        send.put("text", args.get("text"));
        send.put("source", "agent");
        if (present(args.get("channel"))) send.put("channel", args.get("channel"));
        Result sent = chatSend(project, runId, send, agentId);
        if (sent.exit() != 0) return sent;
        // The agent line is delivered before anything is read back; only a human line after it is the reply.
        String sentId = String.valueOf(sent.body().getOrDefault("sentId", ""));
        String sentStatus = String.valueOf(sent.body().getOrDefault("status", "stored"));
        long sentNumber = longOr(sentId, Long.MAX_VALUE);
        List<Map<String, Object>> earlier = new ArrayList<>();
        int eventsAt = 0;
        try {
            eventsAt = PageSurfaces.pollInput(project, runId).size();
        } catch (RuntimeException | IOException ignored) {
            eventsAt = 0;
        }
        Map<String, String> poll = new LinkedHashMap<>();
        poll.put("from", "human");
        if (present(args.get("channel"))) poll.put("channel", args.get("channel"));
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long wakeSeen = tools.dscode.common.agentfast.PushWake.generation();
            if ("human".equals(until) || "answer".equals(until)) {
                java.util.Set<String> already = new java.util.LinkedHashSet<>(ReadMarks.ids(project, runId));
                already.addAll(PageSurfaces.readIds());
                Result polled = chatPoll(project, runId, poll, agentId);
                List<Map<String, Object>> fresh = new ArrayList<>();
                for (Map<String, Object> row : unreadChat(polled.body().get("messages"), already)) {
                    if (longOr(String.valueOf(row.get("id")), 0L) > sentNumber) {
                        fresh.add(row);
                    } else {
                        Map<String, Object> old = new LinkedHashMap<>(row);
                        old.put("earlier", true);
                        earlier.add(old);
                    }
                }
                if (!fresh.isEmpty()) {
                    Map<String, Object> body = ok("human".equals(until) ? "human replied" : "answer received");
                    body.put("op", "chat-turn");
                    body.put("until", until);
                    body.put("sentId", sentId);
                    body.put("status", sentStatus);
                    body.put("messages", fresh);
                    body.put("count", fresh.size());
                    if (!earlier.isEmpty()) body.put("earlier", earlier);
                    return new Result(0, body);
                }
            }
            if ("answer".equals(until) || "click".equals(until)) {
                List<Map<String, Object>> events;
                try {
                    events = PageSurfaces.pollInput(project, runId, eventsAt);
                } catch (RuntimeException | IOException ignored) {
                    events = List.of();
                }
                for (Map<String, Object> event : events) {
                    String kind = String.valueOf(event.getOrDefault("type",
                            event.getOrDefault("kind", event.getOrDefault("op", event.getOrDefault("name", "")))));
                    String token = kind.toLowerCase(java.util.Locale.ROOT);
                    boolean click = "click".equals(until) && token.contains("click");
                    boolean answer = "answer".equals(until)
                            && (token.contains("answer") || event.get("value") != null || event.get("choice") != null);
                    if (!click && !answer) continue;
                    Map<String, Object> body = ok(click ? "click received" : "answer received");
                    body.put("op", "chat-turn");
                    body.put("until", until);
                    body.put("events", List.of(event));
                    body.put("count", 1);
                    body.put("sentId", sentId);
                    body.put("status", sentStatus);
                    if (!earlier.isEmpty()) body.put("earlier", earlier);
                    return new Result(0, body);
                }
            }
            if (System.nanoTime() >= deadline) {
                Result failed = fail(1, "timeout");
                failed.body().put("op", "chat-turn");
                failed.body().put("until", until);
                failed.body().put("sentId", sentId);
                failed.body().put("status", sentStatus);
                if (!earlier.isEmpty()) failed.body().put("earlier", earlier);
                failed.body().put("fix", "chat-send --source=human, then chat-turn again");
                return failed;
            }
            tools.dscode.common.agentfast.PushWake.await(wakeSeen, 50L, deadline);
        }
    }

    /**
     * {@code wait --condition=new-message}: the first panel or chat message stored after the wait began from
     * {@code --from} (default human; any for every non-agent source). Wakes on the push signal the chat store
     * raises. Does not move the chat cursor; chat-read still lists the message.
     */
    private static Result waitNewMessage(Path project, String runId, Map<String, String> args) throws Exception {
        long timeoutMs;
        try {
            timeoutMs = tools.dscode.control.protocol.LiveSessionAttach.declaredWaitMs("wait", args);
        } catch (NumberFormatException failure) {
            return fail(2, "wait --timeout is milliseconds (a trailing s is seconds), up to 120000");
        }
        String channel = args.get("channel");
        String from = present(args.get("from")) ? args.get("from").trim() : "human";
        PageSurfaces.drainPanelChat(project, runId);
        String newest = "0";
        for (ChatLog.Message message : ChatLog.poll(project, runId, channel, "0")) newest = message.id();
        long began = System.nanoTime();
        long deadline = began + timeoutMs * 1_000_000L;
        while (true) {
            long wakeSeen = tools.dscode.common.agentfast.PushWake.generation();
            PageSurfaces.drainPanelChat(project, runId);
            List<Map<String, Object>> fresh = new ArrayList<>();
            for (ChatLog.Message message : ChatLog.poll(project, runId, channel, newest)) {
                if (!waitSource(from, message.source())) continue;
                fresh.add(ChatLog.toMap(message));
            }
            if (!fresh.isEmpty()) {
                Map<String, Object> body = ok("new message");
                body.put("op", "wait");
                body.put("condition", "new-message");
                body.put("wake", "push");
                body.put("message", fresh.get(0));
                body.put("messages", fresh);
                body.put("count", fresh.size());
                body.put("waitedMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began));
                return new Result(0, body);
            }
            if (System.nanoTime() >= deadline) {
                Result failed = fail(1, "timeout");
                failed.body().put("op", "wait");
                failed.body().put("condition", "new-message");
                failed.body().put("fix", "wait --condition=new-message again, or chat-read --last=5");
                return failed;
            }
            tools.dscode.common.agentfast.PushWake.await(wakeSeen, 200L, deadline);
        }
    }

    /**
     * {@code --from=agent} is agent messages only. {@code human} (the default) is that source and skips agent.
     * {@code any} and {@code all} keep every source, including agent.
     */
    static boolean waitSource(String from, String source) {
        String want = from == null || from.isBlank() ? "human" : from.trim();
        String have = source == null ? "" : source;
        if ("any".equalsIgnoreCase(want) || "all".equalsIgnoreCase(want)) return true;
        if ("agent".equalsIgnoreCase(want)) return "agent".equalsIgnoreCase(have);
        if ("agent".equalsIgnoreCase(have)) return false;
        return want.equalsIgnoreCase(have);
    }

    /** Seconds; a trailing {@code ms} or {@code s} is accepted. Returns milliseconds. */
    static long secondsOrSuffixed(String text) {
        String raw = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (raw.endsWith("ms")) return Math.round(Double.parseDouble(raw.substring(0, raw.length() - 2).trim()));
        if (raw.endsWith("s")) raw = raw.substring(0, raw.length() - 1).trim();
        return Math.round(Double.parseDouble(raw) * 1000.0);
    }

    private static long longOr(String text, long fallback) {
        try {
            return Long.parseLong(text == null ? "" : text.trim());
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }

    /** Drops chat rows whose id or page id was already Read before this poll. */
    private static List<Map<String, Object>> unreadChat(Object messages, java.util.Set<String> already) {
        List<Map<String, Object>> kept = new ArrayList<>();
        if (!(messages instanceof List<?> list) || already == null) return kept;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            String id = raw.get("id") == null ? "" : String.valueOf(raw.get("id"));
            String pageId = raw.get("pageId") == null ? "" : String.valueOf(raw.get("pageId"));
            if ((!id.isBlank() && already.contains(id)) || (!pageId.isBlank() && already.contains(pageId))) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) row.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            kept.add(row);
        }
        return kept;
    }

    /** Visible, rect, and overlaps for every placement on the live page. */
    private static Result uiVerify(Path project, String runId) {
        if (PageSurfaces.liveDriver() == null && tools.dscode.common.agentfast.LiveBrowserHost.page() == null) {
            Result failed = fail(1, "no live browser");
            failed.body().put("op", "ui-verify");
            failed.body().put("fix", "run start --url= --browser=");
            return failed;
        }
        List<Map<String, Object>> boxes = liveBoxes();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int index = 0; index < boxes.size(); index++) {
            Map<String, Object> box = boxes.get(index);
            double x = boxNumber(box.get("x"));
            double y = boxNumber(box.get("y"));
            double w = boxNumber(box.get("w"));
            double h = boxNumber(box.get("h"));
            List<String> overlaps = new ArrayList<>();
            if (w > 0 && h > 0) {
                for (int other = 0; other < boxes.size(); other++) {
                    if (other == index) continue;
                    Map<String, Object> box2 = boxes.get(other);
                    String id = String.valueOf(box2.get("id"));
                    if (id.isBlank() || "null".equals(id) || overlaps.contains(id)) continue;
                    double ox = boxNumber(box2.get("x"));
                    double oy = boxNumber(box2.get("y"));
                    double ow = boxNumber(box2.get("w"));
                    double oh = boxNumber(box2.get("h"));
                    if (ow <= 0 || oh <= 0) continue;
                    boolean apart = x + w <= ox || ox + ow <= x || y + h <= oy || oy + oh <= y;
                    if (!apart) overlaps.add(id);
                }
            }
            Map<String, Object> rect = new LinkedHashMap<>();
            rect.put("x", box.get("x"));
            rect.put("y", box.get("y"));
            rect.put("w", box.get("w"));
            rect.put("h", box.get("h"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", box.get("id"));
            row.put("visible", w > 0 && h > 0);
            row.put("rect", rect);
            row.put("overlaps", overlaps);
            lines.add(row);
        }
        Map<String, Object> body = ok(lines.isEmpty() ? "no placements" : "placements checked");
        body.put("op", "ui-verify");
        body.put("placed", lines.size());
        body.put("placements", lines);
        body.put("lines", lines);
        body.put("count", lines.size());
        return new Result(0, body);
    }

    private static Result highlight(Path project, String runId, Map<String, String> args) throws Exception {
        if (!present(args.get("handle")) && present(args.get("query"))) {
            String raw = present(args.get("target")) ? args.get("target").trim() : "browser:auto";
            String rejected = tools.dscode.common.agentfast.TargetVerb.unavailable(raw);
            if (rejected != null) {
                Result failed = fail(1, rejected);
                failed.body().put("applied", false);
                return failed;
            }
            String preset = present(args.get("preset")) || present(args.get("style")) ? args.get("preset") : "yellow";
            String css = PageSurfaces.presetCss(preset, args.get("style"));
            tools.dscode.common.agentfast.TargetVerb.Outcome painted =
                    tools.dscode.common.agentfast.TargetVerb.highlightQuery(raw, args.get("query").trim(), css, runId);
            return new Result(painted.exit(), painted.body());
        }
        if (!present(args.get("handle"))) return fail(2, "highlight requires --handle or --query");
        String handle = expandHandle(args.get("handle"));
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
                        tools.dscode.common.agentfast.TargetVerb.highlight(raw, handle, css, runId);
                return new Result(painted.exit(), painted.body());
            }
        }
        Map<String, Object> painted = PageSurfaces.highlight(
                explicitPage(project, args),
                handle,
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
        Map<String, Object> cleared = PageSurfaces.unhighlight(explicitPage(project, args), expandHandle(args.get("handle")));
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
                expandHandle(args.get("handle")),
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
        stampSeen(placed, probeId(placed, args.get("handle")), runId);
        leanPlacement(placed, args);
        if (present(at)) placed.put("at", at.trim());
        if (present(block) && Boolean.parseBoolean(block.trim())) placed.put("block", true);
        OverlayReceipts.record(project, present(args.get("session")) ? args.get("session") : runId, probeId(placed, args.get("handle")));
        return new Result(0, placed);
    }

    private static Result visual(Path project, String runId, String op, Map<String, String> args) throws Exception {
        String kind = VisualKinds.kind(op);
        if (present(args.get("block")) && !args.get("block").trim().matches("(?i)true|false")) {
            return fail(2, op + " --block must be true or false");
        }
        String text = args.get("text");
        String handle = expandHandle(args.get("handle"));
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
            String anchor = present(args.get("handle")) ? expandHandle(args.get("handle")) : expandHandle(args.get("from"));
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
        stampSeen(placed, probeId(placed, args.get("anchor") != null ? args.get("anchor") : args.get("handle")), runId);
        leanPlacement(placed, args);
        if ("arrow".equals(kind)) placed.put("direction", args.get("direction") == null ? "" : args.get("direction"));
        if ("step".equals(kind)) placed.put("number", Integer.parseInt(args.get("number").trim()));
        if ("magnifier".equals(kind)) placed.put("zoom", args.get("zoom"));
        if ("spotlight".equals(kind)) placed.put("dim", args.get("dim"));
        if (present(args.get("block")) && Boolean.parseBoolean(args.get("block").trim())) placed.put("block", true);
        return new Result(0, placed);
    }

    private static final java.util.Set<String> ATTACHED_VERBS = java.util.Set.of(
            "overlay", "chat-send", "panel-open",
            "pin", "toast", "checklist", "status",
            "label", "border", "arrow", "step-badge", "magnifier", "spotlight"
    );

    /**
     * Collaboration draws on the start host or the isolate worker.
     * An attached desktop, CDP URL, or other browser is unsupported.
     */
    public static String attachedRefusal(String op, String runId, Map<String, String> values) {
        if (op == null || !ATTACHED_VERBS.contains(op)) return null;
        String target = values == null ? null : values.get("target");
        if (target == null || target.isBlank() || target.startsWith("ref:")) return null;
        String text = target.trim();
        if ("auto".equals(text) || "page".equals(text) || "popup".equals(text) || "native".equals(text)) return null;
        if ("browser:auto".equals(text)) return null;
        if (runId != null && !runId.isBlank() && (text.equals("browser:" + runId) || text.startsWith("browser:" + runId + "/"))) {
            return null;
        }
        if (!text.startsWith("browser:") && !text.startsWith("desktop:") && !text.contains("://")) return null;
        return "unsupported on attached target";
    }

    /** The painted placement id, so a short anchor handle is not reported lost. */
    private static String probeId(Map<String, Object> placed, String fallback) {
        Object id = placed == null ? null : placed.get("id");
        if (id != null && !String.valueOf(id).isBlank()) return String.valueOf(id);
        return fallback;
    }

    /** Default placement replies keep id, kind, visible, rect, and overlaps. {@code --verbose} restores the rest. */
    public static void leanPlacement(Map<String, Object> placed, Map<String, String> args) {
        if (placed == null) return;
        if (!placed.containsKey("rect")) {
            Map<String, Object> rect = new java.util.LinkedHashMap<>();
            rect.put("x", placed.get("x"));
            rect.put("y", placed.get("y"));
            rect.put("width", placed.get("width"));
            rect.put("height", placed.get("height"));
            placed.put("rect", rect);
        }
        boolean verbose = args != null && truth(args.get("verbose"));
        if (!verbose) {
            placed.remove("sha256");
            placed.remove("summary");
            placed.remove("guidance");
        }
    }

    /** visible, hidden, and lost describe the target. overlaps names placements on the page now. */
    private static void stampSeen(Map<String, Object> placed, String handle, String runId) {
        WebDriver web = PageSurfaces.liveDriver();
        if (web instanceof org.openqa.selenium.JavascriptExecutor && present(handle)) {
            try {
                Object raw = tools.dscode.common.composite.ElementOperations.script(web, PageSurfaces.SEEN_SCRIPT, handle);
                if (raw instanceof Map<?, ?> map) {
                    placed.put("visible", Boolean.TRUE.equals(map.get("visible")));
                    placed.put("hidden", Boolean.TRUE.equals(map.get("hidden")));
                    placed.put("lost", Boolean.TRUE.equals(map.get("lost")));
                    Object reason = map.get("hiddenReason");
                    if (reason != null && !String.valueOf(reason).isBlank()) {
                        placed.put("hiddenReason", String.valueOf(reason));
                    }
                }
            } catch (RuntimeException ignored) {
                placed.put("visible", false);
                placed.put("hidden", false);
                placed.put("lost", true);
            }
        }
        stampOverlaps(placed);
    }

    /** Boxes of the placements on the page, including open shadow roots such as pkb-layer. */
    private static List<Map<String, Object>> liveBoxes() {
        WebDriver web = PageSurfaces.liveDriver();
        if (!(web instanceof org.openqa.selenium.JavascriptExecutor)) return List.of();
        try {
            Object raw = tools.dscode.common.composite.ElementOperations.script(web, PageSurfaces.LIVE_BOXES);
            if (!(raw instanceof List<?> rows)) return List.of();
            List<Map<String, Object>> boxes = new ArrayList<>();
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> box = new LinkedHashMap<>();
                    map.forEach((key, value) -> box.put(String.valueOf(key), value));
                    boxes.add(box);
                }
            }
            return boxes;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** Names every other placement whose box intersects this one. A removed placement is already gone. */
    private static void stampOverlaps(Map<String, Object> placed) {
        String id = String.valueOf(placed.getOrDefault("id", ""));
        if (id.isBlank() || "null".equals(id)) return;
        List<Map<String, Object>> boxes = liveBoxes();
        Map<String, Object> self = null;
        for (Map<String, Object> box : boxes) {
            if (id.equals(String.valueOf(box.get("id")))) {
                self = box;
                break;
            }
        }
        if (self == null) return;
        double x = boxNumber(self.get("x"));
        double y = boxNumber(self.get("y"));
        double w = boxNumber(self.get("w"));
        double h = boxNumber(self.get("h"));
        List<String> overlaps = new ArrayList<>();
        if (w > 0 && h > 0) {
            for (Map<String, Object> box : boxes) {
                String other = String.valueOf(box.get("id"));
                if (other.isBlank() || other.equals(id) || overlaps.contains(other)) continue;
                double ox = boxNumber(box.get("x"));
                double oy = boxNumber(box.get("y"));
                double ow = boxNumber(box.get("w"));
                double oh = boxNumber(box.get("h"));
                if (ow <= 0 || oh <= 0) continue;
                boolean apart = x + w <= ox || ox + ow <= x || y + h <= oy || oy + oh <= y;
                if (!apart) overlaps.add(other);
            }
        }
        placed.put("overlaps", overlaps);
    }

    /** Default cards stack under the cards already on the page. Explicit overlay coordinates are left alone. */
    private static int nextCardTop() {
        double bottom = 16;
        boolean found = false;
        for (Map<String, Object> box : liveBoxes()) {
            if (!truth(box.get("card"))) continue;
            double edge = boxNumber(box.get("y")) + boxNumber(box.get("h"));
            if (!found || edge > bottom) bottom = edge;
            found = true;
        }
        if (!found) return 24;
        return Math.max(24, (int) Math.ceil(bottom + 8));
    }

    private static Result checkItem(Map<String, String> args) {
        if (!present(args.get("id"))) return fail(2, "checklist --check requires --id");
        int index;
        try {
            index = Integer.parseInt(args.get("check").trim());
        } catch (NumberFormatException failure) {
            return fail(2, "checklist --check must be a whole number");
        }
        if (index < 1) return fail(2, "checklist --check must be a whole number");
        WebDriver web = PageSurfaces.liveDriver();
        if (!(web instanceof org.openqa.selenium.JavascriptExecutor)) return fail(1, "not found");
        Object raw;
        try {
            raw = tools.dscode.common.composite.ElementOperations.script(web, PageSurfaces.CHECK_ITEM, args.get("id").trim(), index);
        } catch (RuntimeException failure) {
            return fail(1, "not found");
        }
        if (!(raw instanceof Map<?, ?> map) || !truth(map.get("found"))) return fail(1, "not found");
        Map<String, Object> body = ok("Checklist item updated.");
        body.put("op", "checklist");
        body.put("id", args.get("id").trim());
        body.put("applied", false);
        Object rows = map.get("rows");
        body.put("items", rows == null ? List.of() : rows);
        if (rows instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> item)) continue;
                if (!sameIndex(index, item.get("n"))) continue;
                body.put("applied", truth(item.get("checked")));
                body.put("checked", index);
                body.put("text", item.get("text"));
            }
        }
        return new Result(truth(body.get("applied")) ? 0 : 1, body);
    }

    private static boolean truth(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static boolean sameIndex(int index, Object value) {
        if (value instanceof Number number) return number.doubleValue() == index;
        if (value == null) return false;
        try {
            return Integer.parseInt(String.valueOf(value).trim()) == index;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private static String checklistHtml(String items) {
        if (!present(items)) return null;
        StringBuilder html = new StringBuilder();
        html.append("<div data-pkb-card=\"checklist\" data-pkb-checklist=\"\">");
        int count = 0;
        for (String item : items.split("\\|")) {
            if (item.isBlank()) continue;
            count++;
            html.append("<label data-pkb-check=\"").append(count).append("\" data-pkb-checked=\"false\">")
                    .append("<input type=\"checkbox\" data-pkb-check-input=\"").append(count).append("\">")
                    .append("<span>").append(HumanValue.html(item.trim())).append("</span></label>");
        }
        html.append("</div>");
        return count == 0 ? null : html.toString();
    }

    private static double boxNumber(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value == null) return 0;
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            return 0;
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
            List<Map<String, Object>> events = new ArrayList<>(PageSurfaces.pollInput(project, runId));
            acceptOverlayInput(project, runId, args, events);
            mergeReceipts(project, args, events);
            markConsumed(events);
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
        List<Map<String, Object>> all = new ArrayList<>(PageSurfaces.pollInput(project, runId));
        acceptOverlayInput(project, runId, args, all);
        mergeReceipts(project, args, all);
        List<Map<String, Object>> events = after <= 0 ? all
                : after >= all.size() ? new ArrayList<>() : new ArrayList<>(all.subList(after, all.size()));
        markConsumed(events);
        Map<String, Object> body = ok("Overlay events after the cursor.");
        body.put("count", events.size());
        body.put("events", events);
        body.put("next", all.size());
        return new Result(0, body);
    }

    /** Overlay rows gain {@code read_at}. Null until {@link OverlayReceipts#ack} runs. */
    private static void mergeReceipts(Path project, Map<String, String> args, List<Map<String, Object>> events) {
        OverlayReceipts.merge(project, args == null ? null : args.get("session"), events);
    }

    /**
     * A click, read, or ack from the page sets {@code read_at}. An answer event stores the card.
     * A choice that is not on the card stays on the event and does not fail the poll.
     */
    private static void acceptOverlayInput(
            Path project,
            String runId,
            Map<String, String> args,
            List<Map<String, Object>> events
    ) throws IOException {
        if (events == null || events.isEmpty()) return;
        String session = args == null ? null : args.get("session");
        if (session == null || session.isBlank()) session = runId;
        for (Map<String, Object> event : events) {
            if (event == null) continue;
            Object id = event.get("id");
            if (id == null || String.valueOf(id).isBlank()) continue;
            String type = String.valueOf(event.get("type"));
            if ("click".equals(type) || "read".equals(type) || "ack".equals(type)) {
                OverlayReceipts.ack(project, session, String.valueOf(id));
            } else if ("answer".equals(type)) {
                Object choice = event.get("choice");
                if (choice == null) choice = event.get("value");
                if (choice == null || String.valueOf(choice).isBlank()) continue;
                try {
                    AskCards.answer(project, session, String.valueOf(id), String.valueOf(choice));
                } catch (IllegalArgumentException ignored) {
                    // Missing card, or a choice outside --options.
                }
            }
        }
    }

    private static void markConsumed(List<Map<String, Object>> events) {
        List<String> read = new ArrayList<>();
        if (events != null) {
            for (Map<String, Object> event : events) {
                Object id = event.get("id");
                if (id != null) read.add(String.valueOf(id));
                Object eid = event.get("eid");
                if (eid != null) read.add(String.valueOf(eid));
            }
        }
        PageSurfaces.markRead(read);
    }

    private static Result html(Path project, String runId, Map<String, String> args, String op) throws Exception {
        if (!present(args.get("text"))) return fail(2, "html " + op + " requires --text");
        String text;
        try {
            text = readAtFile(project, args.get("text"), "text");
        } catch (IllegalArgumentException missing) {
            return fail(2, missing.getMessage());
        }
        Map<String, Object> placed = PageSurfaces.place(
                explicitPage(project, args),
                op,
                expandHandle(args.get("handle")),
                args.get("where"),
                text,
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
        String mapped = PageSurfaces.placementFor(id);
        if (mapped != null) id = mapped;
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

    private static Result captureSteps(Path project, String runId, Map<String, String> args) throws Exception {
        Map<String, Object> written = StepCapture.write(project, runId, args);
        written.put("ok", true);
        return new Result(0, written);
    }

    /** Runs {@code bidi} on this JVM's Selenium driver. The CLI posts here; this method does not post again. */
    private static Result bidi(List<String> extraArgs) {
        List<String> tokens = new ArrayList<>();
        tokens.add("bidi");
        if (extraArgs != null) {
            for (String token : extraArgs) {
                if (token != null) tokens.add(token);
            }
        }
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int code = tools.dscode.browser.SeleniumBidiCommands.execute(
                tokens.toArray(String[]::new),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8)
        );
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", code == 0);
        body.put("op", "bidi");
        body.put("stdout", outBytes.toString(StandardCharsets.UTF_8));
        body.put("stderr", errBytes.toString(StandardCharsets.UTF_8));
        if (code != 0) {
            String error = errBytes.toString(StandardCharsets.UTF_8).trim();
            body.put("error", error.isBlank() ? "bidi command failed" : error);
        }
        return new Result(code, body);
    }

    private static Result record(Path project, String runId, Map<String, String> args) {
        tools.dscode.common.recording.RecordControl.Outcome outcome =
                tools.dscode.common.recording.RecordControl.handle(project, runId, args);
        return new Result(outcome.exit(), outcome.body());
    }

    /** Shows or hides the in-page record bar. A missing page is ignored. */
    public static void armRecording(Map<String, Object> spec) {
        PageSurfaces.armRecording(spec);
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

    /** An error/fix refusal: {@code error} and {@code fix} fields, exit 2. */
    static Result refusal(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        String error = text;
        String fix = "";
        int split = text.indexOf("\nfix: ");
        if (split >= 0) {
            error = text.substring(0, split);
            fix = text.substring(split + "\nfix: ".length());
        }
        body.put("error", error.startsWith("error: ") ? error.substring("error: ".length()) : error);
        if (!fix.isBlank()) body.put("fix", fix);
        return new Result(2, body);
    }

    private static Result fail(int exit, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        return new Result(exit, body);
    }

    /** A short id or ref becomes the element id or {@code frame:backend}, never the accessible name. */
    private static String expandHandle(String handle) {
        if (!present(handle)) return handle;
        String placed = tools.dscode.common.agentfast.TargetVerb.placementHandle(handle);
        if (placed != null && !placed.isBlank()) {
            bindPlacement(placed);
            return placed;
        }
        String selector = tools.dscode.common.agentfast.TargetHandles.selector(handle);
        String lookup = selector;
        if (lookup == null || lookup.isBlank()) {
            String token = handle.trim();
            if (token.startsWith("text:") || token.startsWith("role:") || token.startsWith("x:") || token.startsWith("#")) {
                lookup = token;
            } else return handle;
        }
        try {
            String found = UiBatch.resolve(lookup);
            if (found != null && !found.isBlank()) return found;
        } catch (Exception ignored) {
            // The original handle is still tried by the page.
        }
        if (lookup.startsWith("#") && lookup.length() > 1) return lookup.substring(1);
        return lookup;
    }

    /**
     * The browser that published this placement owns the frame.
     * A later border or label then enters that frame instead of reporting an unknown handle.
     */
    private static void bindPlacement(String placed) {
        WebDriver page = tools.dscode.common.agentfast.LiveBrowserHost.page();
        if (page == null) {
            java.util.List<PageSurfaces.Managed> managed = PageSurfaces.managed();
            if (!managed.isEmpty()) page = managed.get(managed.size() - 1).driver();
        }
        if (page == null) return;
        String tab = "";
        try {
            String current = page.getWindowHandle();
            if (current != null) tab = current;
        } catch (RuntimeException ignored) {
            tab = "";
        }
        PageSurfaces.bindHandle(page, tab, placed);
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
