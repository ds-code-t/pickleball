package tools.dscode.launcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Workbench-only summaries, failure detail, trace index, and per-scenario console.
 * Reads diagnostic files after a run. Does not rewrite events, traces, or a summary
 * the core reporter already wrote.
 */
public final class WorkbenchRunReports {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern FINAL_XPATH = Pattern.compile("(?im)^\\s*final xpath:\\s*(.+?)\\s*$");
    private static final Pattern RAW_COUNT = Pattern.compile("(?im)^\\s*raw match count:\\s*(\\d+)\\s*$");
    private static final Pattern VISIBLE_COUNT = Pattern.compile("(?im)^\\s*visible count:\\s*(\\d+)\\s*$");
    private static final Pattern REJECTION = Pattern.compile("(?im)^\\s*rejection:\\s*(.+?)\\s*$");
    private static final Pattern EXPECTED_ACTUAL = Pattern.compile(
            "(?is)expected:\\s*<([^>]*)>\\s*but was:\\s*<([^>]*)>"
    );
    private static final Pattern EXPECTED_LINE = Pattern.compile("(?im)^\\s*expected:\\s*(.+?)\\s*$");
    private static final Pattern ACTUAL_LINE = Pattern.compile("(?im)^\\s*actual:\\s*(.+?)\\s*$");

    private WorkbenchRunReports() {
    }

    /** Copies bytes to a buffer and to the original stream. Workbench run capture only. */
    public static final class Tee extends PrintStream {
        private final ByteArrayOutputStream copy;

        public Tee(PrintStream primary, ByteArrayOutputStream copy) {
            super(primary, true, StandardCharsets.UTF_8);
            this.copy = copy;
        }

        @Override
        public void write(int bite) {
            super.write(bite);
            copy.write(bite);
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            super.write(buffer, offset, length);
            copy.write(buffer, offset, length);
        }

        public String text() {
            return copy.toString(StandardCharsets.UTF_8);
        }
    }

    public static String compact(String status, int scenarios, int steps) {
        return "summary status=" + (status == null ? "" : status)
                + " scenarios=" + scenarios
                + " steps=" + steps;
    }

    /**
     * Writes per-scenario console files under the Workbench run folder and, when a
     * diagnostic scenario directory has events, a workbench summary and trace index.
     */
    public static int publish(
            Path project,
            String runId,
            List<WorkbenchFeatureIndex.Hit> hits,
            String console,
            String status,
            PrintStream out
    ) throws IOException {
        List<String> names = new ArrayList<>();
        if (hits != null) {
            for (WorkbenchFeatureIndex.Hit hit : hits) {
                if (hit != null && hit.scenarioName() != null && !hit.scenarioName().isBlank()) {
                    names.add(hit.scenarioName());
                }
            }
        }
        if (runId != null && !runId.isBlank()) {
            saveConsole(AgentCoordination.runDirectory(project, runId), names, console == null ? "" : console);
        }
        int steps = 0;
        int scenarios = 0;
        Path diagnostic = project.resolve("reports").resolve("diagnostic-runs");
        if (Files.isDirectory(diagnostic)) {
            try (var walk = Files.walk(diagnostic, 4)) {
                List<Path> dirs = walk.filter(path -> Files.isRegularFile(path.resolve("events.jsonl"))).toList();
                for (Path dir : dirs) {
                    writeScenario(dir);
                    scenarios++;
                    steps += stepCount(dir);
                }
            }
        }
        if (scenarios == 0) scenarios = names.size();
        if (out != null) out.println(compact(status, scenarios, steps));
        return steps;
    }

    public static List<Path> saveConsole(Path runFolder, List<String> scenarioNames, String console) throws IOException {
        Path dir = runFolder.resolve("console");
        Files.createDirectories(dir);
        String text = console == null ? "" : console;
        List<String> names = scenarioNames == null ? List.of() : scenarioNames;
        Map<String, StringBuilder> parts = new LinkedHashMap<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) parts.putIfAbsent(name, new StringBuilder());
        }
        String current = null;
        boolean marked = false;
        for (String line : text.split("\\R", -1)) {
            String next = scenarioOnLine(line, names);
            if (next != null) {
                current = next;
                marked = true;
            }
            if (current != null) {
                parts.get(current).append(line).append('\n');
            }
        }
        List<Path> written = new ArrayList<>();
        if (!marked) {
            if (names.size() == 1) {
                written.add(writeText(dir.resolve(safe(names.getFirst()) + ".txt"), text));
            } else {
                written.add(writeText(dir.resolve("unassigned.txt"), text));
            }
            return written;
        }
        for (Map.Entry<String, StringBuilder> entry : parts.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            written.add(writeText(dir.resolve(safe(entry.getKey()) + ".txt"), entry.getValue().toString()));
        }
        return written;
    }

    /** Post-run read of one scenario directory. Leaves core events, trace, and summary.json alone. */
    public static Path writeScenario(Path scenarioDir) throws IOException {
        List<Map<String, Object>> events = readJsonLines(scenarioDir.resolve("events.jsonl"));
        String scenario = "";
        String status = "";
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> failures = new ArrayList<>();
        for (Map<String, Object> event : events) {
            String type = text(event.get("type"));
            if ("scenario_start".equals(type)) {
                scenario = identityName(event.get("identity"));
            } else if ("scenario_end".equals(type) && status.isBlank()) {
                status = text(event.get("outcome"));
            } else if ("step".equals(type)) {
                steps.add(stepView(event));
            } else if ("failure".equals(type)) {
                failures.addAll(failureViews(event));
            }
        }
        if (status.isBlank()) {
            boolean failed = steps.stream().anyMatch(step -> "FAILED".equals(text(step.get("status"))));
            status = steps.isEmpty() ? "" : failed ? "FAILED" : "PASSED";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenario", scenario);
        body.put("status", status);
        body.put("steps", steps);
        body.put("failures", failures);
        Path workbench = scenarioDir.resolve("workbench");
        Files.createDirectories(workbench);
        Path summary = workbench.resolve("summary.json");
        JSON.writeValue(summary.toFile(), body);
        Path core = scenarioDir.resolve("summary.json");
        if (!Files.exists(core)) {
            JSON.writeValue(core.toFile(), body);
        }
        JSON.writeValue(workbench.resolve("trace-index.json").toFile(), traceIndex(scenarioDir.resolve("trace.jsonl")));
        return summary;
    }

    static Map<String, Object> parseFailureText(String message) {
        String text = message == null ? "" : message;
        Map<String, Object> view = new LinkedHashMap<>();
        Matcher xpath = FINAL_XPATH.matcher(text);
        view.put("finalXPath", xpath.find() ? xpath.group(1).trim() : "");
        view.put("rawMatchCount", groupInt(RAW_COUNT.matcher(text)));
        view.put("visibleCount", groupInt(VISIBLE_COUNT.matcher(text)));
        List<String> reasons = new ArrayList<>();
        Matcher rejection = REJECTION.matcher(text);
        while (rejection.find()) reasons.add(rejection.group(1).trim());
        view.put("rejectionReasons", reasons);
        String expected = "";
        String actual = "";
        Matcher pair = EXPECTED_ACTUAL.matcher(text);
        if (pair.find()) {
            expected = pair.group(1);
            actual = pair.group(2);
        } else {
            Matcher expectedLine = EXPECTED_LINE.matcher(text);
            Matcher actualLine = ACTUAL_LINE.matcher(text);
            if (expectedLine.find()) expected = stripAngle(expectedLine.group(1));
            if (actualLine.find()) actual = stripAngle(actualLine.group(1));
        }
        view.put("expected", expected);
        view.put("actual", actual);
        return view;
    }

    private static Map<String, Object> stepView(Map<String, Object> event) {
        String keyword = text(event.get("keyword"));
        String stepText = text(event.get("text"));
        String step = (keyword + " " + stepText).trim();
        if (step.isEmpty()) step = text(event.get("step"));
        String status = text(event.get("status"));
        Map<String, Object> parsed = parseFailureText(text(event.get("errorMessage")));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("step", step);
        view.put("status", status);
        view.put("assertionResult", assertionResult(status, parsed));
        view.put("durationMillis", longValue(event.get("durationMillis")));
        return view;
    }

    private static List<Map<String, Object>> failureViews(Map<String, Object> event) {
        List<Map<String, Object>> views = new ArrayList<>();
        Object raw = event.get("failures");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    views.add(failureView(map));
                }
            }
        }
        if (views.isEmpty() && event.get("message") != null) {
            views.add(failureView(event));
        }
        return views;
    }

    private static Map<String, Object> failureView(Map<?, ?> raw) {
        Map<String, Object> parsed = parseFailureText(firstText(raw, "message", "errorMessage"));
        putIfPresent(parsed, "finalXPath", raw.get("finalXPath"));
        putIfPresent(parsed, "rawMatchCount", raw.get("rawMatchCount"));
        putIfPresent(parsed, "visibleCount", raw.get("visibleCount"));
        Object reasons = raw.get("rejectionReasons");
        if (reasons instanceof List<?> list && !list.isEmpty()) {
            List<String> copy = new ArrayList<>();
            for (Object item : list) copy.add(String.valueOf(item));
            parsed.put("rejectionReasons", copy);
        }
        putIfPresent(parsed, "actual", raw.get("actual"));
        putIfPresent(parsed, "expected", raw.get("expected"));
        return parsed;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) return;
        if (value instanceof String text && text.isBlank()) return;
        target.put(key, value);
    }

    private static String assertionResult(String status, Map<String, Object> parsed) {
        String actual = text(parsed.get("actual"));
        String expected = text(parsed.get("expected"));
        if (!actual.isEmpty() || !expected.isEmpty()) {
            return "actual=" + actual + " expected=" + expected;
        }
        if ("PASSED".equalsIgnoreCase(status)) return "passed";
        if ("FAILED".equalsIgnoreCase(status)) return "failed";
        return status == null ? "" : status.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> traceIndex(Path trace) throws IOException {
        List<Map<String, Object>> steps = new ArrayList<>();
        if (!Files.isRegularFile(trace)) {
            return Map.of("steps", steps);
        }
        byte[] bytes = Files.readAllBytes(trace);
        int offset = 0;
        String current = null;
        int start = 0;
        while (offset <= bytes.length) {
            int next = offset;
            while (next < bytes.length && bytes[next] != '\n') next++;
            int lineEnd = next < bytes.length ? next + 1 : next;
            if (next > offset) {
                String line = new String(bytes, offset, next - offset, StandardCharsets.UTF_8);
                String step = stepName(line);
                if (step != null && !step.equals(current)) {
                    if (current != null) steps.add(span(current, start, offset));
                    current = step;
                    start = offset;
                }
            }
            if (lineEnd >= bytes.length) break;
            offset = lineEnd;
        }
        if (current != null) steps.add(span(current, start, bytes.length));
        return Map.of("steps", steps);
    }

    private static String stepName(String line) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = JSON.readValue(line, Map.class);
            String step = text(raw.get("step"));
            if (!step.isEmpty()) return step;
            String keyword = text(raw.get("keyword"));
            String text = text(raw.get("text"));
            String combined = (keyword + " " + text).trim();
            return combined.isEmpty() ? null : combined;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, Object> span(String step, int start, int end) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("step", step);
        map.put("startByte", start);
        map.put("endByte", end);
        return map;
    }

    private static int stepCount(Path scenarioDir) throws IOException {
        Path summary = scenarioDir.resolve("workbench").resolve("summary.json");
        if (!Files.isRegularFile(summary)) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(summary.toFile(), Map.class);
        Object steps = body.get("steps");
        return steps instanceof List<?> list ? list.size() : 0;
    }

    private static String scenarioOnLine(String line, List<String> names) {
        String trimmed = line == null ? "" : line.trim();
        String bare = trimmed.startsWith("Scenario:") ? trimmed.substring("Scenario:".length()).trim() : trimmed;
        for (String name : names) {
            if (name.equals(trimmed) || name.equals(bare)) return name;
        }
        return null;
    }

    private static Path writeText(Path file, String text) throws IOException {
        Files.writeString(file, text == null ? "" : text, StandardCharsets.UTF_8);
        return file;
    }

    private static String safe(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]+", "_");
        return cleaned.isBlank() ? "scenario" : cleaned;
    }

    private static List<Map<String, Object>> readJsonLines(Path file) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.isRegularFile(file)) return rows;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = JSON.readValue(line, Map.class);
                rows.add(raw);
            } catch (Exception ignored) {
                // A torn last line is not a core-format change.
            }
        }
        return rows;
    }

    private static String identityName(Object identity) {
        if (identity instanceof Map<?, ?> map) {
            Object name = map.get("scenarioName");
            if (name == null) name = map.get("name");
            return text(name);
        }
        return "";
    }

    private static String firstText(Map<?, ?> raw, String... keys) {
        for (String key : keys) {
            Object value = raw.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static Integer groupInt(Matcher matcher) {
        if (!matcher.find()) return null;
        return Integer.valueOf(matcher.group(1));
    }

    private static String stripAngle(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("<") && text.endsWith(">") && text.length() >= 2) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            return 0L;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
