package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tools.dscode.common.agentfast.AgentEnvelope;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shell access to every MCP tool ({@code call}) and a file of verbs
 * ({@code execute-steps}). Both replies are the agent envelope. The controller
 * is the one already started by {@code controller-start}
 * ({@code .pickleball/agent-controller.json}).
 */
final class CompositeCommands {
    private static final ObjectMapper JSON = new ObjectMapper();

    private CompositeCommands() {
    }

    static int call(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String tool = toolName(parsed);
        if (tool == null || tool.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("call requires a tool name", "call"));
            return 2;
        }
        Map<String, String> args;
        try {
            args = readArgs(parsed);
        } catch (IllegalArgumentException failure) {
            err.print(WorkbenchCliHelp.usageError(failure.getMessage(), "call"));
            return 2;
        } catch (java.io.IOException failure) {
            err.println("error: " + failure.getMessage());
            err.println("fix: call <tool> --args-file=<json>");
            return 1;
        }
        if ("workbench_agent_command".equals(tool) || "agent_command".equals(tool)) {
            String op = args.remove("op");
            String nested = args.remove("args");
            if (nested != null && !nested.isBlank()) flatten(nested, args);
            if (op == null || op.isBlank()) {
                err.print(WorkbenchCliHelp.usageError("workbench_agent_command requires op", "call"));
                return 2;
            }
            tool = op;
        }
        String verb = verbOf(tool, args);
        if ("call".equals(verb)) {
            err.print(WorkbenchCliHelp.usageError("call cannot call itself", "call"));
            return 2;
        }
        if (!WorkbenchCommandLine.isKnownCommand(verb)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("op", "call");
            body.put("error", "unavailable");
            body.put("tool", tool);
            body.put("sentence", tool + " has no shell verb");
            body.put("fix", "call workbench_agent_command with op set to a help verb");
            printEnvelope(out, parsed.project(), "call", 1, body, "");
            err.println("error: unavailable");
            err.println("fix: help");
            return 1;
        }
        List<String> argv = new ArrayList<>();
        argv.add(verb);
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
            if ("positional".equals(entry.getKey())) {
                argv.add(entry.getValue());
                continue;
            }
            argv.add("--" + entry.getKey() + "=" + entry.getValue());
        }
        String runId = parsed.coordination().runId();
        if (runId != null && !runId.isBlank() && args.get("run-id") == null) argv.add("--run-id=" + runId);
        argv.add(parsed.project().toString());
        return WorkbenchAgentCommands.run(argv.toArray(String[]::new), out, err);
    }

    static int executeSteps(WorkbenchCommandLine.Parsed parsed, PrintStream out, PrintStream err) {
        String raw = flag(parsed, "--file");
        if (raw == null || raw.isBlank()) {
            err.print(WorkbenchCliHelp.usageError("execute-steps requires --file", "execute-steps"));
            return 2;
        }
        boolean stop = has(parsed, "--stop-on-fail");
        Path file = Path.of(raw);
        if (!file.isAbsolute()) file = parsed.project().resolve(raw);
        List<Step> steps;
        try {
            steps = steps(Files.readString(file));
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? "unreadable steps" : failure.getMessage();
            err.print(WorkbenchCliHelp.usageError(message, "execute-steps"));
            return 2;
        }
        String runId = parsed.coordination().runId();
        if (runId == null || runId.isBlank()) runId = ConsumerReadyCommands.sessionId(parsed.project());
        if (runId == null) runId = "";
        List<Map<String, Object>> items = new ArrayList<>();
        int exit = 0;
        int ran = 0;
        String frame = "";
        for (Step step : steps) {
            ran++;
            StepReply reply = runStep(parsed, runId, step);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("op", step.op);
            row.put("ok", reply.exit == 0);
            row.put("exit", reply.exit);
            if (reply.error != null && !reply.error.isBlank()) row.put("error", reply.error);
            if (reply.frame != null && !reply.frame.isBlank()) {
                row.put("frame", reply.frame);
                frame = reply.frame;
            }
            if (reply.text != null && !reply.text.isBlank()) row.put("text", reply.text);
            items.add(row);
            if (reply.exit != 0) {
                exit = reply.exit;
                if (stop) break;
            }
        }
        boolean stopped = stop && exit != 0;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", exit == 0);
        body.put("op", "execute-steps");
        body.put("ran", ran);
        body.put("count", steps.size());
        body.put("stopped", stopped);
        body.put("lines", items);
        if (!frame.isBlank()) body.put("frame", frame);
        body.put("sentence", exit == 0 ? "steps ok" : (stopped ? "steps stopped" : "steps failed"));
        if (exit != 0) {
            body.put("error", "step failed");
            body.put("fix", "fix the failed step and run execute-steps again");
        }
        printEnvelope(out, parsed.project(), "execute-steps", exit, body, runId);
        if (exit != 0) {
            err.println("error: step failed");
            err.println("fix: fix the failed step and run execute-steps again");
        }
        return exit;
    }

    private record Step(String op, Map<String, String> args) {
    }

    private record StepReply(int exit, String error, String frame, String text) {
    }

    /**
     * One step uses the same launcher path as typing the verb: page verbs post
     * to the live worker, and {@code doctor} stays in this process.
     */
    private static StepReply runStep(WorkbenchCommandLine.Parsed parsed, String runId, Step step) {
        Map<String, String> args = new LinkedHashMap<>();
        if (step.args != null) args.putAll(step.args);
        String verb = verbOf(step.op == null ? "" : step.op, args);
        List<String> argv = new ArrayList<>();
        argv.add(verb);
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) continue;
            if ("positional".equals(entry.getKey())) {
                argv.add(entry.getValue());
                continue;
            }
            argv.add("--" + entry.getKey() + "=" + entry.getValue());
        }
        if (runId != null && !runId.isBlank()) argv.add("--run-id=" + runId);
        argv.add(parsed.project().toString());
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                argv.toArray(String[]::new),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String printed = stdout.toString(StandardCharsets.UTF_8).trim();
        String error = "";
        String frame = "";
        String text = "";
        if (printed.startsWith("{")) {
            try {
                JsonNode node = JSON.readTree(printed);
                if (node.has("error") && !node.get("error").isNull() && node.get("error").isValueNode()) {
                    error = node.get("error").asText("");
                } else if (node.path("err").isObject()) {
                    error = node.path("err").path("code").asText("");
                }
                frame = node.path("state").path("frame").asText("");
                if (frame.isBlank()) frame = node.path("frame").asText("");
            } catch (java.io.IOException ignored) {
                text = printed;
            }
        } else if (!printed.isEmpty()) {
            text = printed;
            for (String line : printed.split("\\R")) {
                if (line.startsWith("frame=")) frame = line.substring("frame=".length()).trim();
            }
        }
        if (error.isBlank() && code != 0) {
            String errText = stderr.toString(StandardCharsets.UTF_8).trim();
            int newline = errText.indexOf('\n');
            String first = newline < 0 ? errText : errText.substring(0, newline).trim();
            if (first.startsWith("error:")) first = first.substring("error:".length()).trim();
            error = first;
        }
        return new StepReply(code, error, frame, text);
    }

    private static List<Step> steps(String text) throws java.io.IOException {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) throw new IllegalArgumentException("execute-steps file is empty");
        List<Step> steps = new ArrayList<>();
        if (raw.startsWith("[")) {
            JsonNode node = JSON.readTree(raw);
            if (!node.isArray()) throw new IllegalArgumentException("execute-steps file must be a JSON array");
            for (JsonNode item : node) {
                String op = item.path("op").asText(item.path("verb").asText(""));
                if (op.isBlank()) throw new IllegalArgumentException("each step needs op");
                Map<String, String> args = new LinkedHashMap<>();
                JsonNode values = item.get("args");
                if (values != null && values.isObject()) {
                    values.fields().forEachRemaining(entry -> args.put(entry.getKey(), entry.getValue().asText("")));
                }
                steps.add(new Step(op, args));
            }
            return steps;
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int space = trimmed.indexOf(' ');
            if (space < 0) steps.add(new Step(trimmed, Map.of()));
            else {
                Map<String, String> args = new LinkedHashMap<>();
                args.put("text", trimmed.substring(space + 1).trim());
                steps.add(new Step(trimmed.substring(0, space), args));
            }
        }
        if (steps.isEmpty()) throw new IllegalArgumentException("execute-steps file is empty");
        return steps;
    }

    private static String verbOf(String tool, Map<String, String> args) {
        String name = tool.trim();
        if ("click".equals(name)) {
            args.putIfAbsent("kind", "click");
            return "act";
        }
        if ("type".equals(name)) {
            args.putIfAbsent("kind", "type");
            return "act";
        }
        if ("snapshot".equals(name)) return "snap";
        if ("ui_poll".equals(name)) return "ui-poll";
        if ("ui_catalog".equals(name)) return "ui-catalog";
        if ("surfaces".equals(name)) return "ui-catalog";
        Map<String, String> mapped = Map.ofEntries(
                Map.entry("workbench_execute_step", "execute-step"),
                Map.entry("workbench_chat_wait", "chat_wait"),
                Map.entry("workbench_element_inspect", "inspect-page"),
                Map.entry("workbench_browser_screenshot", "screenshot"),
                Map.entry("workbench_browser_page", "snap"),
                Map.entry("workbench_start", "start"),
                Map.entry("workbench_history_summary", "history-summary"),
                Map.entry("workbench_history_query", "history-query"),
                Map.entry("workbench_entity_lookup", "entity-lookup"),
                Map.entry("workbench_open_run", "open-run"),
                Map.entry("workbench_bridge_health", "bridge-health"),
                Map.entry("workbench_diagnostic_run", "diagnostic-run"),
                Map.entry("workbench_events", "events"),
                Map.entry("workbench_sync", "sync"),
                Map.entry("workbench_worker_start", "worker-start"),
                Map.entry("workbench_worker_restart", "worker-restart"),
                Map.entry("workbench_worker_stop", "worker-stop"),
                Map.entry("workbench_request_save", "save"),
                Map.entry("agent_popup_open", "popup-open"),
                Map.entry("agent_popup_status", "popup-status"),
                Map.entry("agent_popup_close", "popup-close"),
                Map.entry("wb_runs", "query"),
                Map.entry("wb_get", "query"),
                Map.entry("wb_query", "query"),
                Map.entry("wb_search", "query"),
                Map.entry("wb_props", "query"),
                Map.entry("wb_errors", "query")
        );
        if ("wb_runs".equals(name)) args.put("positional", "runs");
        else if (name.startsWith("wb_")) args.put("positional", name.substring(3));
        String known = mapped.get(name);
        if (known != null) return known;
        if (name.startsWith("workbench_")) name = name.substring("workbench_".length()).replace('_', '-');
        else name = name.replace('_', '-');
        if ("chat-wait".equals(name)) return "chat_wait";
        return name;
    }

    private static void flatten(String json, Map<String, String> args) {
        try {
            JsonNode node = JSON.readTree(json);
            if (!node.isObject()) return;
            node.fields().forEachRemaining(entry -> args.put(entry.getKey(), entry.getValue().asText("")));
        } catch (java.io.IOException ignored) {
            // The caller reports a bad args file before this.
        }
    }

    private static Map<String, String> readArgs(WorkbenchCommandLine.Parsed parsed) throws java.io.IOException {
        Map<String, String> args = new LinkedHashMap<>();
        String file = flag(parsed, "--args-file");
        String encoded = flag(parsed, "--args-b64");
        String inline = flag(parsed, "--args");
        String json = null;
        if (file != null && !file.isBlank()) {
            Path path = Path.of(file);
            if (!path.isAbsolute()) path = parsed.project().resolve(file);
            json = Files.readString(path);
        } else if (encoded != null && !encoded.isBlank()) {
            json = new String(Base64.getDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
        } else if (inline != null && ("-".equals(inline) || inline.isBlank())) {
            json = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } else if (inline != null) {
            json = inline;
        }
        if (json != null && !json.isBlank()) {
            JsonNode node = JSON.readTree(json);
            if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    JsonNode value = entry.getValue();
                    if (value.isObject() || value.isArray()) {
                        try {
                            args.put(entry.getKey(), JSON.writeValueAsString(value));
                        } catch (java.io.IOException failure) {
                            args.put(entry.getKey(), value.toString());
                        }
                    } else args.put(entry.getKey(), value.asText(""));
                });
            } else throw new IllegalArgumentException("call args must be a JSON object");
        }
        copy(parsed, args, "text");
        copy(parsed, args, "url");
        copy(parsed, args, "browser");
        copy(parsed, args, "query");
        copy(parsed, args, "target");
        copy(parsed, args, "kind");
        copy(parsed, args, "ref");
        copy(parsed, args, "ops");
        copy(parsed, args, "out");
        copy(parsed, args, "source");
        copy(parsed, args, "channel");
        return args;
    }

    private static void copy(WorkbenchCommandLine.Parsed parsed, Map<String, String> args, String name) {
        String value = flag(parsed, "--" + name);
        if (value != null && !value.isBlank()) args.put(name, value);
    }

    private static String toolName(WorkbenchCommandLine.Parsed parsed) {
        String[] argv = parsed.forwarded();
        if (argv == null) return null;
        String project = parsed.project() == null ? "" : parsed.project().toString();
        for (int index = 1; index < argv.length; index++) {
            String token = argv[index];
            if (token == null || token.isBlank() || token.startsWith("-")) continue;
            if (token.equals(project) || token.equals(parsed.project().getFileName().toString())) continue;
            return token;
        }
        return null;
    }

    private static String flag(WorkbenchCommandLine.Parsed parsed, String name) {
        String[] args = parsed.forwarded();
        if (args == null) return null;
        for (int index = 0; index < args.length; index++) {
            String token = args[index];
            if (token == null) continue;
            if (token.startsWith(name + "=")) return token.substring(name.length() + 1);
            if (name.equals(token) && index + 1 < args.length) return args[index + 1];
        }
        return null;
    }

    private static boolean has(WorkbenchCommandLine.Parsed parsed, String name) {
        String[] args = parsed.forwarded();
        if (args == null) return false;
        for (String token : args) {
            if (token == null) continue;
            if (name.equals(token) || token.startsWith(name + "=")) return true;
        }
        return false;
    }

    private static void printEnvelope(PrintStream out, Path project, String op, int exit, Map<String, Object> body, String runId) {
        try {
            out.println(JSON.writeValueAsString(AgentEnvelope.apply(body, exit, op, project, runId)));
        } catch (java.io.IOException failure) {
            out.println("{\"v\":1,\"ok\":false,\"op\":\"" + op + "\"}");
        }
    }
}
