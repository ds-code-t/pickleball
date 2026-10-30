package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
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
    private static final Pattern SCENARIO_SUMMARY = Pattern.compile("(?m)^(\\d+)\\s+Scenarios?\\b");
    private static final Pattern STEP_SUMMARY = Pattern.compile("(?m)^(\\d+)\\s+Steps?\\b");

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
        return compact(status, scenarios, steps, null, null);
    }

    public static String compact(String status, int scenarios, int steps, String environment, String browser) {
        StringBuilder line = new StringBuilder();
        line.append("summary status=").append(status == null ? "" : status)
                .append(" scenarios=").append(scenarios)
                .append(" steps=").append(steps);
        if (environment != null && !environment.isBlank()) {
            line.append(" environment=").append(environment.trim());
        }
        if (browser != null && !browser.isBlank()) {
            line.append(" browser=").append(browser.trim());
        }
        return line.toString();
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
        return publish(project, runId, hits, console, status, null, null, out);
    }

    public static int publish(
            Path project,
            String runId,
            List<WorkbenchFeatureIndex.Hit> hits,
            String console,
            String status,
            String environment,
            String browser,
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
        boolean launchError = status != null && "LAUNCH_ERROR".equalsIgnoreCase(status.trim());
        List<Map<String, Object>> bodies = new ArrayList<>();
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Path dir : currentRunScenarioDirs(project, runId)) {
            Path written = writeScenario(dir);
            bodies.add(readJsonObject(written));
            traces.add(readJsonObject(dir.resolve("workbench").resolve("trace-index.json")));
            scenarios++;
            steps += stepCount(dir);
        }
        if (launchError) {
            scenarios = 0;
            steps = 0;
            bodies.clear();
            traces.clear();
        }
        if (!launchError && scenarios == 0) {
            int[] indexed = indexCounts(project, runId);
            if (indexed != null && (indexed[0] > 0 || indexed[1] > 0)) {
                scenarios = indexed[0];
                steps = indexed[1];
            }
        } else if (!launchError && steps == 0) {
            int[] indexed = indexCounts(project, runId);
            if (indexed != null && indexed[1] > 0) steps = indexed[1];
        }
        if (!launchError && scenarios == 0 && steps == 0) {
            int[] fromConsole = consoleCounts(console);
            if (fromConsole != null) {
                scenarios = fromConsole[0];
                steps = fromConsole[1];
            }
        } else if (!launchError && steps == 0) {
            int[] fromConsole = consoleCounts(console);
            if (fromConsole != null && fromConsole[1] > 0) steps = fromConsole[1];
        }
        if (!launchError && scenarios == 0) scenarios = names.size();
        if (runId != null && !runId.isBlank()) {
            try {
                writeRunSummary(
                        AgentCoordination.runDirectory(project, runId),
                        status,
                        bodies,
                        traces,
                        scenarios,
                        steps
                );
            } catch (IllegalArgumentException invalid) {
                // An unsafe run id already skipped the console write.
            }
        }
        if (out != null) out.println(compact(status, scenarios, steps, environment, browser));
        return steps;
    }

    /**
     * Scenario counts belong to this run. A private diagnostic folder wins when it
     * already has a scenario directory. Passed scenarios often lose {@code events.jsonl}
     * under retention {@code failed}; the core {@code summary.json} under {@code scenarios/}
     * is still that directory. The shared tree is only this run, never every prior run.
     */
    private static List<Path> currentRunScenarioDirs(Path project, String runId) throws IOException {
        if (project == null || runId == null || runId.isBlank()) return List.of();
        Path privateRoot;
        try {
            privateRoot = AgentCoordination.runDirectory(project, runId).resolve("diagnostic");
        } catch (IllegalArgumentException invalid) {
            return List.of();
        }
        List<Path> privateDirs = scenarioDirs(privateRoot);
        if (!privateDirs.isEmpty()) return privateDirs;
        return scenarioDirs(project.resolve("reports").resolve("diagnostic-runs").resolve(runId));
    }

    private static List<Path> scenarioDirs(Path root) throws IOException {
        if (root == null || !Files.isDirectory(root)) return List.of();
        try (var walk = Files.walk(root, 6)) {
            return walk.filter(WorkbenchRunReports::scenarioDirectory).toList();
        }
    }

    /** A scenario folder still holding events, or the core summary left after dense evidence was pruned. */
    private static boolean scenarioDirectory(Path path) {
        if (path == null || !Files.isDirectory(path)) return false;
        if (Files.isRegularFile(path.resolve("events.jsonl"))) return true;
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null || !"scenarios".equals(parent.getFileName().toString())) {
            return false;
        }
        return Files.isRegularFile(path.resolve("summary.json"))
                || Files.isRegularFile(path.resolve("trace.jsonl"))
                || Files.isRegularFile(path.resolve("trace.jsonl.gz"));
    }

    /** Full-scan runs often have no private scenario directory. The run index still has counts. */
    private static int[] indexCounts(Path project, String runId) {
        Path index = AgentCoordination.findRunIndex(project, runId);
        if (index == null) return null;
        try {
            JsonNode root = JSON.readTree(index.toFile());
            JsonNode counts = root.get("counts");
            if (counts == null || !counts.isObject() || !counts.has("total")) return null;
            int scenarios = counts.path("total").asInt(0);
            int stepCount = 0;
            JsonNode steps = root.get("steps");
            if (steps != null && steps.isObject()) {
                if (steps.has("executed")) stepCount = steps.path("executed").asInt(0);
                else if (steps.has("total")) stepCount = steps.path("total").asInt(0);
                else {
                    for (String key : List.of("passed", "failed", "skipped", "pending", "undefined")) {
                        stepCount += steps.path(key).asInt(0);
                    }
                }
            }
            return new int[]{scenarios, stepCount};
        } catch (IOException failure) {
            return null;
        }
    }

    private static int[] consoleCounts(String console) {
        if (console == null || console.isBlank()) return null;
        int scenarios = lastCount(SCENARIO_SUMMARY, console);
        int steps = lastCount(STEP_SUMMARY, console);
        if (scenarios < 0 && steps < 0) return null;
        return new int[]{Math.max(scenarios, 0), Math.max(steps, 0)};
    }

    private static int lastCount(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int value = -1;
        while (matcher.find()) value = Integer.parseInt(matcher.group(1));
        return value;
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
        if (containsMarker(text, names)) {
            return writeMarked(dir, parts, text);
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

    private static boolean containsMarker(String text, List<String> names) {
        for (String line : text.split("\\R", -1)) {
            if (WorkbenchConsoleTap.scenarioOnMarkedLine(line, names) != null) return true;
        }
        return false;
    }

    /** Marker lines are exact-name matches. The saved file does not keep the prefix. */
    private static List<Path> writeMarked(Path dir, Map<String, StringBuilder> parts, String text) throws IOException {
        String[] lines = text.split("\\R", -1);
        int limit = lines.length;
        if (limit > 0 && lines[limit - 1].isEmpty()) limit--;
        StringBuilder unassigned = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            String line = lines[i];
            String name = WorkbenchConsoleTap.scenarioOnMarkedLine(line, namesOf(parts));
            if (name != null) {
                parts.get(name).append(WorkbenchConsoleTap.payload(line)).append('\n');
            } else if (!line.isEmpty()) {
                unassigned.append(line).append('\n');
            }
        }
        List<Path> written = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> entry : parts.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            written.add(writeText(dir.resolve(safe(entry.getKey()) + ".txt"), entry.getValue().toString()));
        }
        if (!unassigned.isEmpty()) {
            written.add(writeText(dir.resolve("unassigned.txt"), unassigned.toString()));
        }
        return written;
    }

    private static List<String> namesOf(Map<String, StringBuilder> parts) {
        return List.copyOf(parts.keySet());
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
        boolean thin = scenario.isBlank() || steps.isEmpty();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scenario", scenario);
        body.put("status", status);
        body.put("steps", steps);
        body.put("failures", failures);
        if (thin) fillFromCoreSummary(scenarioDir, body);
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

    /**
     * Run-level files the Workbench docs name: {@code runs/<id>/workbench/summary.json}
     * and {@code trace-index.json}. Does not rewrite core scenario files.
     */
    private static void writeRunSummary(
            Path runFolder,
            String status,
            List<Map<String, Object>> bodies,
            List<Map<String, Object>> traces,
            int scenarioCount,
            int stepCount
    ) throws IOException {
        Path workbench = runFolder.resolve("workbench");
        Files.createDirectories(workbench);
        Map<String, Object> body = new LinkedHashMap<>();
        if (bodies.size() == 1) {
            body.putAll(bodies.getFirst());
            if (text(body.get("status")).isBlank() && status != null) body.put("status", status);
        } else {
            body.put("status", status == null ? "" : status);
            body.put("scenarios", bodies);
            List<Object> steps = new ArrayList<>();
            List<Object> failureRows = new ArrayList<>();
            for (Map<String, Object> one : bodies) {
                if (one.get("steps") instanceof List<?> list) steps.addAll(list);
                if (one.get("failures") instanceof List<?> list) failureRows.addAll(list);
            }
            body.put("steps", steps);
            body.put("failures", failureRows);
        }
        body.put("scenarioCount", scenarioCount);
        body.put("stepCount", stepCount);
        JSON.writeValue(workbench.resolve("summary.json").toFile(), body);
        List<Map<String, Object>> spans = new ArrayList<>();
        for (int index = 0; index < traces.size(); index++) {
            String scenario = index < bodies.size() ? text(bodies.get(index).get("scenario")) : "";
            Object rawSteps = traces.get(index).get("steps");
            if (!(rawSteps instanceof List<?> list)) continue;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> span = new LinkedHashMap<>();
                if (!scenario.isBlank()) span.put("scenario", scenario);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    span.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                spans.add(span);
            }
        }
        JSON.writeValue(workbench.resolve("trace-index.json").toFile(), Map.of("steps", spans));
    }

    private static void fillFromCoreSummary(Path scenarioDir, Map<String, Object> body) {
        Path core = scenarioDir.resolve("summary.json");
        if (!Files.isRegularFile(core)) return;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> coreBody = JSON.readValue(core.toFile(), Map.class);
            if (text(body.get("scenario")).isBlank()) {
                body.put("scenario", scenarioNameFrom(coreBody));
            }
            if (text(body.get("status")).isBlank()) {
                String outcome = firstText(coreBody, "outcome", "status");
                if (!outcome.isBlank()) body.put("status", outcome);
            }
            if (!body.containsKey("durationMillis") && coreBody.get("durationMillis") != null) {
                body.put("durationMillis", longValue(coreBody.get("durationMillis")));
            }
            Object coreSteps = coreBody.get("steps");
            if (body.get("steps") instanceof List<?> list && list.isEmpty() && coreSteps instanceof List<?> steps) {
                body.put("steps", steps);
            }
            if (body.get("failures") instanceof List<?> list && list.isEmpty()) {
                String message = firstText(coreBody, "failureMessage", "message");
                if (!message.isBlank()) body.put("failures", List.of(parseFailureText(message)));
            }
        } catch (IOException ignored) {
            // A core summary that cannot be read is left unchanged.
        }
    }

    private static String scenarioNameFrom(Map<String, Object> coreBody) {
        Object identity = coreBody.get("identity");
        if (identity instanceof Map<?, ?> map) {
            String name = firstText(map, "scenarioName", "name");
            if (!name.isBlank()) return name;
        }
        return firstText(coreBody, "scenario", "scenarioName", "name");
    }

    private static Map<String, Object> readJsonObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = JSON.readValue(file.toFile(), Map.class);
            return raw == null ? new LinkedHashMap<>() : raw;
        } catch (IOException ignored) {
            return new LinkedHashMap<>();
        }
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
        int listed = stepListSize(scenarioDir.resolve("workbench").resolve("summary.json"));
        if (listed > 0) return listed;
        return coreStepCount(scenarioDir.resolve("summary.json"));
    }

    /** A non-empty workbench step list wins. An empty list is not a count of zero steps. */
    private static int stepListSize(Path summary) throws IOException {
        if (!Files.isRegularFile(summary)) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(summary.toFile(), Map.class);
        Object steps = body.get("steps");
        return steps instanceof List<?> list ? list.size() : 0;
    }

    /**
     * Core {@code summary.json} keeps a count map after retention deletes {@code events.jsonl}.
     * Prefer {@code executed}, then {@code total}, then the sum of the outcome buckets.
     */
    private static int coreStepCount(Path summary) throws IOException {
        if (!Files.isRegularFile(summary)) return 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JSON.readValue(summary.toFile(), Map.class);
        Object steps = body.get("steps");
        if (steps instanceof List<?> list) return list.size();
        if (!(steps instanceof Map<?, ?> map)) return 0;
        if (map.containsKey("executed")) return intValue(map.get("executed"));
        if (map.containsKey("total")) return intValue(map.get("total"));
        int sum = 0;
        for (String key : List.of("passed", "failed", "skipped", "other")) {
            sum += intValue(map.get(key));
        }
        return sum;
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    private static String scenarioOnLine(String line, List<String> names) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty() || names == null || names.isEmpty()) return null;
        String bare = stripScenarioKeyword(trimmed);
        List<String> ordered = new ArrayList<>();
        for (String name : names) {
            if (name != null && !name.isBlank()) ordered.add(name);
        }
        ordered.sort((left, right) -> Integer.compare(right.length(), left.length()));
        for (String name : ordered) {
            if (name.equals(trimmed) || name.equals(bare)) return name;
            if (bare.startsWith(name) && locationTail(bare.substring(name.length()))) return name;
        }
        return null;
    }

    /** Cucumber prints {@code Scenario:} or {@code Scenario Outline:} before the name. */
    private static String stripScenarioKeyword(String trimmed) {
        if (trimmed.startsWith("Scenario Outline:")) {
            return trimmed.substring("Scenario Outline:".length()).trim();
        }
        if (trimmed.startsWith("Scenario:")) {
            return trimmed.substring("Scenario:".length()).trim();
        }
        return trimmed;
    }

    /** True when nothing follows the name, or Cucumber's {@code # uri:line} location does. */
    private static boolean locationTail(String rest) {
        if (rest == null) return false;
        String text = rest.stripLeading();
        return text.isEmpty() || text.startsWith("#");
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
