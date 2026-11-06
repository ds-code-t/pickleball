package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeRuntime;
import tools.dscode.common.driver.DriverConstruction;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            return switch (op == null ? "" : op) {
                case "observe" -> observe(project, runId, values);
                case "launch" -> launch(project, runId, values, extraArgs);
                case "windows" -> windows(project, runId, values, "window");
                case "tabs" -> windows(project, runId, values, "tab");
                case "chat-send" -> chatSend(project, runId, values, agentId);
                case "chat-history" -> chatHistory(project, runId, values);
                case "chat-poll" -> chatPoll(project, runId, values);
                case "highlight" -> highlight(project, runId, values);
                case "unhighlight" -> unhighlight(project, runId, values);
                case "overlay" -> overlay(project, runId, values);
                case "overlay-poll" -> overlayPoll(project, runId);
                case "overlay-remove" -> remove(project, runId, values, "overlay");
                case "html-write" -> html(project, runId, values, "append");
                case "html-replace" -> html(project, runId, values, "replace");
                case "html-insert" -> html(project, runId, values, "insert");
                case "html-remove" -> remove(project, runId, values, "html");
                case "html-cleanup" -> cleanup(project, runId, values);
                case "capture-scenario" -> capture(project, runId, values);
                default -> fail(2, "unknown agent command: " + op);
            };
        } catch (IllegalArgumentException failure) {
            return fail(2, failure.getMessage() == null ? "usage" : failure.getMessage());
        } catch (Exception failure) {
            return fail(1, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
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
        return new Result(0, body);
    }

    private static Result chatHistory(Path project, String runId, Map<String, String> args) throws Exception {
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
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatLog.Message message : ChatLog.poll(project, runId, args.get("channel"), after)) {
            messages.add(ChatLog.toMap(message));
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
        Map<String, Object> placed = PageSurfaces.place(
                explicitPage(project, args),
                "append",
                args.get("handle"),
                args.get("where"),
                args.get("text"),
                "overlay",
                integerOrNull(args.get("x")),
                integerOrNull(args.get("y")),
                args.get("geometry")
        );
        placed.put("ok", true);
        return new Result(0, placed);
    }

    private static Result overlayPoll(Path project, String runId) throws Exception {
        List<Map<String, Object>> events = PageSurfaces.pollInput(project, runId);
        Map<String, Object> body = ok("Forwarded overlay clicks and form inputs.");
        body.put("count", events.size());
        body.put("events", events);
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
                args.get("geometry")
        );
        placed.put("ok", true);
        return new Result(0, placed);
    }

    private static Result remove(Path project, String runId, Map<String, String> args, String kind) throws Exception {
        String id = present(args.get("id")) ? args.get("id") : args.get("handle");
        Map<String, Object> removed = PageSurfaces.remove(explicitPage(project, args), id);
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
