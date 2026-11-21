package tools.dscode.common.agentcontrol;

import com.fasterxml.jackson.databind.JsonNode;
import tools.dscode.control.protocol.AgentEvents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns click and answer rows into the JSON array {@code execute-steps --file} runs.
 * An empty capture still writes the file and says so.
 */
public final class StepCapture {
    private StepCapture() {
    }

    public static Map<String, Object> write(Path project, String runId, Map<String, String> args) throws IOException {
        Map<String, String> flags = args == null ? Map.of() : args;
        List<Map<String, Object>> steps = new ArrayList<>();
        String file = flags.get("events");
        boolean fromFile = false;
        if (file != null && !file.isBlank()) {
            Path path = Path.of(file);
            if (!path.isAbsolute() && project != null) path = project.resolve(file);
            steps.addAll(stepsFrom(Files.readString(path, StandardCharsets.UTF_8)));
            fromFile = true;
        }
        int beforeLog = steps.size();
        steps.addAll(fromLog(project));
        steps = dedupe(steps);
        String body = AgentStore.JSON.writeValueAsString(steps);
        Path out = flags.get("out") == null || flags.get("out").isBlank()
                ? AgentStore.root(project, runId).resolve("captured-steps.json")
                : (Path.of(flags.get("out")).isAbsolute() || project == null
                ? Path.of(flags.get("out"))
                : project.resolve(flags.get("out")));
        AgentStore.ensure(out.getParent());
        Files.writeString(out, body, StandardCharsets.UTF_8);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", out.toAbsolutePath().normalize().toString());
        result.put("count", steps.size());
        result.put("steps", steps);
        result.put("source", source(fromFile, steps.size() > beforeLog || (!fromFile && !steps.isEmpty())));
        result.put("sentence", steps.isEmpty() ? "no steps captured" : "steps captured");
        return result;
    }

    static List<Map<String, Object>> stepsFrom(String text) throws IOException {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) return List.of();
        if (raw.startsWith("[") || raw.startsWith("{")) {
            try (com.fasterxml.jackson.core.JsonParser parser = AgentStore.JSON.createParser(raw)) {
                JsonNode node = AgentStore.JSON.readTree(parser);
                if (parser.nextToken() == null) return stepsNode(node);
            } catch (IOException ignored) {
                // More than one JSON value, or not JSON. Read it as lines.
            }
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            JsonNode node = AgentStore.JSON.readTree(trimmed);
            if (node.isObject() && (node.hasNonNull("op") || node.hasNonNull("verb"))) {
                steps.addAll(stepsArray(AgentStore.JSON.createArrayNode().add(node)));
                continue;
            }
            Map<String, Object> step = stepOf(stringMap(node));
            if (step != null) steps.add(step);
        }
        return steps;
    }

    private static List<Map<String, Object>> stepsNode(JsonNode node) {
        if (node.isArray() && looksLikeSteps(node)) return stepsArray(node);
        if (node.isObject() && node.has("events") && looksLikeSteps(node.get("events"))) {
            return stepsArray(node.get("events"));
        }
        if (node.isArray() || (node.isObject() && node.has("events"))) {
            JsonNode array = node.isArray() ? node : node.get("events");
            List<Map<String, Object>> steps = new ArrayList<>();
            for (JsonNode item : array) {
                Map<String, Object> step = stepOf(stringMap(item));
                if (step != null) steps.add(step);
            }
            return steps;
        }
        if (node.isObject() && (node.hasNonNull("op") || node.hasNonNull("verb"))) {
            return stepsArray(AgentStore.JSON.createArrayNode().add(node));
        }
        Map<String, Object> one = stepOf(stringMap(node));
        return one == null ? List.of() : List.of(one);
    }

    private static List<Map<String, Object>> fromLog(Path project) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, String> row : AgentEvents.lines(project)) {
            Map<String, Object> step = stepOf(row);
            if (step != null) steps.add(step);
        }
        return steps;
    }

    static Map<String, Object> stepOf(Map<String, String> row) {
        if (row == null || row.isEmpty()) return null;
        if (row.get("op") != null && !row.get("op").isBlank()) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("op", row.get("op"));
            step.put("args", Map.of());
            return step;
        }
        String type = first(row.get("type"), row.get("event"));
        if ("click".equalsIgnoreCase(type)) {
            Map<String, String> args = new LinkedHashMap<>();
            args.put("kind", "click");
            String ref = first(row.get("ref"), row.get("id"));
            String query = first(row.get("query"), row.get("text"), row.get("name"));
            if (ref != null) args.put("ref", ref);
            if (query != null) args.put("query", query);
            if (!args.containsKey("ref") && !args.containsKey("query")) return null;
            return step("act", args);
        }
        if ("answer".equalsIgnoreCase(type)) {
            if ("true".equalsIgnoreCase(row.get("sensitive"))) return null;
            String value = first(row.get("value"), row.get("text"));
            if (value == null) return null;
            String ref = first(row.get("ref"), row.get("id"));
            if (ref != null) {
                Map<String, String> args = new LinkedHashMap<>();
                args.put("kind", "type");
                args.put("ref", ref);
                args.put("value", value);
                return step("act", args);
            }
            Map<String, String> args = new LinkedHashMap<>();
            args.put("source", "human");
            args.put("text", value);
            return step("chat-send", args);
        }
        return null;
    }

    private static boolean looksLikeSteps(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) return false;
        JsonNode first = array.get(0);
        return first.isObject() && (first.hasNonNull("op") || first.hasNonNull("verb"));
    }

    private static List<Map<String, Object>> stepsArray(JsonNode array) {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (array == null || !array.isArray()) return steps;
        for (JsonNode item : array) {
            String op = item.path("op").asText(item.path("verb").asText(""));
            if (op.isBlank()) continue;
            Map<String, String> args = new LinkedHashMap<>();
            JsonNode values = item.get("args");
            if (values != null && values.isObject()) {
                values.fields().forEachRemaining(entry -> args.put(entry.getKey(), entry.getValue().asText("")));
            }
            steps.add(step(op, args));
        }
        return steps;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> row = new LinkedHashMap<>();
        if (node == null || !node.isObject()) return row;
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull() || value.isContainerNode()) return;
            row.put(entry.getKey(), value.asText(""));
        });
        return row;
    }

    private static Map<String, Object> step(String op, Map<String, String> args) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", op);
        step.put("args", args);
        return step;
    }

    private static List<Map<String, Object>> dedupe(List<Map<String, Object>> steps) {
        List<Map<String, Object>> kept = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            String key;
            try {
                key = AgentStore.JSON.writeValueAsString(step);
            } catch (IOException failure) {
                key = String.valueOf(step);
            }
            if (seen.contains(key)) continue;
            seen.add(key);
            kept.add(step);
        }
        return kept;
    }

    private static String source(boolean file, boolean log) {
        if (file && log) return "events-file+agent-events";
        if (file) return "events-file";
        if (log) return "agent-events";
        return "none";
    }

    private static String first(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
