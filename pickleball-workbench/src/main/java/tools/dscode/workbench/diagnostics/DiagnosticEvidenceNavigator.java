package tools.dscode.workbench.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only navigator over Pickleball's retained diagnostic artifacts.
 *
 * <p>Layer order matches the repository evidence protocol: run-catalog,
 * run-index/clusters, scenario summary, events.jsonl, comparison/fingerprint
 * metadata, PNG, then raw trace only if needed. Workbench does not create a
 * competing diagnostic store or synthesize retained-run data.</p>
 */
public final class DiagnosticEvidenceNavigator {
    public enum Layer {
        CATALOG,
        RUN_INDEX,
        CLUSTERS,
        SUMMARY,
        EVENTS,
        COMPARISON,
        SCREENSHOT,
        TRACE
    }

    public record CatalogRun(String runId, Path runRoot, JsonNode raw) {
        public String displayLabel() {
            StringBuilder label = new StringBuilder(runId == null ? "" : runId);
            String outcome = DiagnosticEvidenceNavigator.text(raw, "outcome");
            if (!outcome.isBlank()) {
                label.append(" · ").append(outcome);
            }
            String purpose = DiagnosticEvidenceNavigator.text(raw, "purpose", "runPurpose");
            if (!purpose.isBlank()) {
                label.append(" · ").append(purpose);
            }
            return label.toString();
        }

        public String outcome() {
            return DiagnosticEvidenceNavigator.text(raw, "outcome");
        }

        public String completion() {
            return DiagnosticEvidenceNavigator.text(raw, "completion");
        }

        public boolean inProgress() {
            String completion = completion();
            return "IN_PROGRESS".equalsIgnoreCase(completion)
                    || "RUNNING".equalsIgnoreCase(outcome());
        }
    }

    public record ScreenshotFrame(
            Path file,
            String stepText,
            String capturedAt,
            String scenarioId
    ) { }

    public static final String KIND_SCENARIO = "scenario";
    public static final String KIND_COMPONENT = "component";
    public static final String KIND_SERVICE_CALL = "service-call";
    public static final String KIND_STEP = "step";
    public static final String KIND_DATA = "data";
    public static final String KIND_JAVA = "java";

    public record ReplayDefinition(
            String className,
            String method,
            String sourcePath,
            String origin
    ) {
        public ReplayDefinition {
            className = className == null ? "" : className;
            method = method == null ? "" : method;
            sourcePath = sourcePath == null ? "" : sourcePath;
            origin = origin == null ? "" : origin;
        }

        public static ReplayDefinition empty() {
            return new ReplayDefinition("", "", "", "");
        }
    }

    public record ReplayBeat(
            String type,
            String stepText,
            String timestamp,
            String level,
            String text,
            String status,
            Path screenshot,
            String scenarioId,
            List<String> logLines,
            long eventSeq,
            int nestingLevel,
            String nestedInvocationId,
            String sourcePath,
            long sourceLine,
            ReplayDefinition definition,
            String kind,
            String nodeId,
            String parentNodeId
    ) {
        public ReplayBeat {
            type = type == null ? "" : type;
            stepText = stepText == null ? "" : stepText;
            timestamp = timestamp == null ? "" : timestamp;
            level = level == null ? "" : level;
            text = text == null ? "" : text;
            status = status == null ? "" : status;
            scenarioId = scenarioId == null ? "" : scenarioId;
            logLines = List.copyOf(logLines == null ? List.of() : logLines);
            eventSeq = Math.max(0, eventSeq);
            nestingLevel = Math.max(0, nestingLevel);
            nestedInvocationId = nestedInvocationId == null ? "" : nestedInvocationId;
            sourcePath = sourcePath == null ? "" : sourcePath;
            sourceLine = Math.max(0, sourceLine);
            definition = definition == null ? ReplayDefinition.empty() : definition;
            kind = kind == null || kind.isBlank() ? KIND_STEP : kind;
            nodeId = nodeId == null ? "" : nodeId;
            parentNodeId = parentNodeId == null ? "" : parentNodeId;
        }
    }

    public record ReplayNode(
            String nodeId,
            String kind,
            String parentNodeId,
            ReplayBeat beat,
            List<ReplayNode> children
    ) {
        public ReplayNode {
            nodeId = nodeId == null ? "" : nodeId;
            kind = kind == null || kind.isBlank() ? KIND_STEP : kind;
            parentNodeId = parentNodeId == null ? "" : parentNodeId;
            children = List.copyOf(children == null ? List.of() : children);
        }
    }

    public record ReplayModel(List<ReplayBeat> beats, List<ReplayNode> roots) {
        public ReplayModel {
            beats = List.copyOf(beats == null ? List.of() : beats);
            roots = List.copyOf(roots == null ? List.of() : roots);
        }
    }

    public record Timeline(
            Path runRoot,
            JsonNode runIndex,
            JsonNode clusters,
            List<ScreenshotFrame> frames
    ) {
        public Timeline {
            frames = List.copyOf(frames == null ? List.of() : frames);
        }
    }

    public record LayerView(Layer layer, Path path, boolean present, String excerpt) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path projectRoot;
    private final Path diagnosticRoot;

    public DiagnosticEvidenceNavigator(Path projectRoot) {
        this(projectRoot, defaultDiagnosticRoot(projectRoot));
    }

    public DiagnosticEvidenceNavigator(Path projectRoot, Path diagnosticRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.diagnosticRoot = diagnosticRoot == null
                ? defaultDiagnosticRoot(this.projectRoot)
                : diagnosticRoot.toAbsolutePath().normalize();
    }

    public static Path defaultDiagnosticRoot(Path projectRoot) {
        return projectRoot.toAbsolutePath().normalize().resolve("reports").resolve("diagnostic-runs");
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path diagnosticRoot() {
        return diagnosticRoot;
    }

    public boolean available() {
        return Files.isRegularFile(diagnosticRoot.resolve("run-catalog.json"));
    }

    public List<CatalogRun> catalogRuns() {
        Path catalog = diagnosticRoot.resolve("run-catalog.json");
        if (!Files.isRegularFile(catalog)) return List.of();
        JsonNode root = readJson(catalog);
        List<CatalogRun> runs = new ArrayList<>();
        for (JsonNode node : catalogItems(root)) {
            String runId = text(node, "runId", "id", "run");
            if (runId.isBlank()) continue;
            Path runRoot = diagnosticRoot.resolve(runId);
            runs.add(new CatalogRun(runId, runRoot, node));
        }
        return List.copyOf(runs);
    }

    public JsonNode catalogDocument() {
        Path catalog = diagnosticRoot.resolve("run-catalog.json");
        var result = JSON.createObjectNode();
        result.put("available", Files.isRegularFile(catalog));
        result.put("path", catalog.toString());
        if (Files.isRegularFile(catalog)) {
            result.set("catalog", readJson(catalog));
        }
        return result;
    }

    public JsonNode runDocument(String runId) {
        Path runRoot = resolveContained(diagnosticRoot, runId, "runId");
        Path index = runRoot.resolve("run-index.json");
        Path clusters = runRoot.resolve("clusters.json");
        var result = JSON.createObjectNode();
        result.put("runId", runId);
        result.put("runRoot", runRoot.toString());
        result.put("indexPresent", Files.isRegularFile(index));
        result.put("clustersPresent", Files.isRegularFile(clusters));
        if (Files.isRegularFile(index)) {
            result.set("runIndex", readJson(index));
        }
        if (Files.isRegularFile(clusters)) {
            result.set("clusters", readJson(clusters));
        }
        return result;
    }

    public JsonNode scenarioSummaryDocument(String runId, String scenarioId) {
        Path runRoot = resolveContained(diagnosticRoot, runId, "runId");
        Path scenarioDir = resolveContained(runRoot.resolve("scenarios"), scenarioId, "scenarioId");
        Path summary = scenarioDir.resolve("summary.json");
        if (!Files.isRegularFile(summary)) {
            throw new IllegalArgumentException("No summary.json for scenario " + scenarioId + " in run " + runId);
        }
        var result = JSON.createObjectNode();
        result.put("runId", runId);
        result.put("scenarioId", scenarioId);
        result.put("path", summary.toString());
        result.set("summary", readJson(summary));
        return result;
    }

    private static Path resolveContained(Path root, String name, String label) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank.");
        }
        if (name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException(label + " must be a simple directory name.");
        }
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(label + " is outside the diagnostic store.");
        }
        return resolved;
    }

    public List<ReplayBeat> replay(Path runRoot) {
        return replayModel(runRoot).beats();
    }

    public ReplayModel replayModel(Path runRoot) {
        Path root = runRoot.toAbsolutePath().normalize();
        Path scenarios = root.resolve("scenarios");
        List<ReplayBeat> beats = new ArrayList<>();
        List<ReplayNode> roots = new ArrayList<>();
        if (!Files.isDirectory(scenarios)) return new ReplayModel(List.of(), List.of());
        try (var directories = Files.list(scenarios)) {
            directories.filter(Files::isDirectory).sorted().forEach(scenarioDir -> {
                ReplayModel model = replayScenario(scenarioDir);
                beats.addAll(model.beats());
                roots.addAll(model.roots());
            });
        } catch (IOException ignored) {
            return new ReplayModel(beats, roots);
        }
        return new ReplayModel(beats, roots);
    }

    public Map<String, Object> beatMap(ReplayBeat beat) {
        Map<String, Object> item = new LinkedHashMap<>();
        if (beat == null) return item;
        item.put("type", beat.type());
        item.put("stepText", beat.stepText());
        item.put("text", beat.text());
        item.put("timestamp", beat.timestamp());
        item.put("status", beat.status());
        item.put("level", beat.level());
        item.put("scenarioId", beat.scenarioId());
        item.put("logLines", beat.logLines());
        item.put("eventSeq", beat.eventSeq());
        item.put("nestingLevel", beat.nestingLevel());
        item.put("nestedInvocationId", beat.nestedInvocationId());
        item.put("sourcePath", beat.sourcePath());
        item.put("sourceLine", beat.sourceLine());
        item.put("kind", beat.kind());
        item.put("nodeId", beat.nodeId());
        item.put("parentNodeId", beat.parentNodeId());
        ReplayDefinition definition = beat.definition();
        Map<String, Object> definitionMap = new LinkedHashMap<>();
        definitionMap.put("className", definition.className());
        definitionMap.put("method", definition.method());
        definitionMap.put("sourcePath", definition.sourcePath());
        definitionMap.put("origin", definition.origin());
        item.put("definition", definitionMap);
        return item;
    }

    public List<Map<String, Object>> treeMaps(List<ReplayNode> roots, List<ReplayBeat> beats) {
        Map<String, Integer> beatIndexByNodeId = new LinkedHashMap<>();
        if (beats != null) {
            for (int i = 0; i < beats.size(); i++) {
                String nodeId = beats.get(i).nodeId();
                if (!nodeId.isBlank()) beatIndexByNodeId.putIfAbsent(nodeId, i);
            }
        }
        List<Map<String, Object>> trees = new ArrayList<>();
        if (roots == null) return List.of();
        for (ReplayNode root : roots) {
            trees.add(treeMap(root, beatIndexByNodeId));
        }
        return List.copyOf(trees);
    }

    private Map<String, Object> treeMap(ReplayNode node, Map<String, Integer> beatIndexByNodeId) {
        Map<String, Object> json = new LinkedHashMap<>();
        ReplayBeat beat = node.beat();
        json.put("nodeId", node.nodeId());
        json.put("kind", node.kind());
        json.put("parentNodeId", node.parentNodeId());
        json.put("beatIndex", beatIndexByNodeId.getOrDefault(node.nodeId(), -1));
        json.put("type", beat == null ? "" : beat.type());
        json.put("stepText", beat == null ? "" : beat.stepText());
        json.put("status", beat == null ? "" : beat.status());
        json.put("eventSeq", beat == null ? 0L : beat.eventSeq());
        json.put("sourcePath", beat == null ? "" : beat.sourcePath());
        json.put("sourceLine", beat == null ? 0L : beat.sourceLine());
        json.put("failed", nodeFailed(node));
        json.put("children", node.children().stream().map(child -> treeMap(child, beatIndexByNodeId)).toList());
        return json;
    }

    private static boolean nodeFailed(ReplayNode node) {
        if (node == null) return false;
        String status = node.beat() == null ? "" : node.beat().status();
        String lower = status.toLowerCase(Locale.ROOT);
        if (lower.contains("fail") || lower.contains("error") || lower.contains("ambiguous")) return true;
        for (ReplayNode child : node.children()) {
            if (nodeFailed(child)) return true;
        }
        return false;
    }

    private ReplayModel replayScenario(Path scenarioDir) {
        String scenarioId = scenarioDir.getFileName().toString();
        Path events = scenarioDir.resolve("events.jsonl");
        List<Path> pngs = screenshotFiles(scenarioDir.resolve("screenshots"));
        int pngIndex = 0;
        List<DraftBeat> drafts = new ArrayList<>();
        DraftBeat current = null;
        long fallbackSeq = 1;
        if (Files.isRegularFile(events)) {
            try {
                for (String line : Files.readAllLines(events)) {
                    if (line.isBlank()) continue;
                    JsonNode node = JSON.readTree(line);
                    String type = text(node, "type");
                    boolean stepEvent = "step".equals(type)
                            || (type.isBlank() && !text(node, "stepText", "text", "step", "gherkin").isBlank());
                    boolean nestedStart = "nested_scenario_start".equals(type);
                    boolean nestedEnd = "nested_scenario_end".equals(type);
                    if (stepEvent || nestedStart || nestedEnd) {
                        if (current != null) {
                            drafts.add(current);
                            current = null;
                        }
                        long eventSeq = longField(node, "eventSeq");
                        if (eventSeq <= 0) eventSeq = fallbackSeq;
                        fallbackSeq = Math.max(fallbackSeq, eventSeq) + 1;
                        current = draftFromEvent(node, type, stepEvent, nestedStart, nestedEnd, eventSeq, scenarioId);
                    } else if ("screenshot".equals(type)) {
                        Path shot = pngIndex < pngs.size() ? pngs.get(pngIndex++) : null;
                        if (current != null && shot != null) current.screenshot = shot;
                    } else if ("log".equals(type)) {
                        String message = text(node, "text", "message");
                        String level = text(node, "level");
                        if (!message.isBlank() && current != null && isInfoPlus(level)) {
                            current.logs.add((level.isBlank() ? "INFO" : level) + "  " + message);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Gap in retained events is shown as an empty replay, not invented steps.
            }
        }
        if (current != null) drafts.add(current);
        if (drafts.isEmpty()) {
            for (ScreenshotFrame frame : framesForScenario(scenarioDir)) {
                DraftBeat draft = new DraftBeat();
                draft.type = "step";
                draft.kind = KIND_STEP;
                draft.stepText = frame.stepText();
                draft.text = frame.stepText();
                draft.timestamp = frame.capturedAt();
                draft.screenshot = frame.file();
                draft.scenarioId = scenarioId;
                draft.eventSeq = fallbackSeq++;
                draft.nodeId = nodeId(scenarioId, draft.eventSeq, drafts.size());
                drafts.add(draft);
            }
        }
        return buildTree(scenarioId, drafts);
    }

    private static DraftBeat draftFromEvent(
            JsonNode node,
            String type,
            boolean stepEvent,
            boolean nestedStart,
            boolean nestedEnd,
            long eventSeq,
            String scenarioId
    ) {
        DraftBeat draft = new DraftBeat();
        draft.eventSeq = eventSeq;
        draft.scenarioId = scenarioId;
        draft.timestamp = text(node, "timestamp");
        draft.status = text(node, "status", "outcome");
        draft.nestingLevel = (int) longField(node, "nestingLevel");
        draft.nestedInvocationId = text(node, "nestedInvocationId", "invocationId");
        JsonNode source = node.get("source");
        draft.sourcePath = text(source, "path");
        draft.sourceLine = longField(source, "line");
        JsonNode definition = node.get("definition");
        draft.definitionClass = text(definition, "class");
        draft.definitionMethod = text(definition, "method");
        draft.definitionSourcePath = text(definition, "sourcePath");
        draft.definitionOrigin = text(definition, "origin");
        if (nestedStart) {
            draft.type = "nested_scenario_start";
            JsonNode callee = node.get("callee");
            draft.stepText = nestedLabel(callee);
            draft.text = draft.stepText;
            draft.kind = nestedKind(callee);
            if (draft.sourcePath.isBlank()) {
                draft.sourcePath = featurePath(text(callee, "featureUri"));
            }
            if (draft.sourceLine <= 0) {
                draft.sourceLine = longField(callee, "scenarioLine");
            }
            if (draft.nestedInvocationId.isBlank()) {
                draft.nestedInvocationId = text(node, "invocationId");
            }
        } else if (nestedEnd) {
            draft.type = "nested_scenario_end";
            draft.kind = KIND_STEP;
            JsonNode callee = node.get("callee");
            draft.stepText = nestedLabel(callee);
            draft.text = draft.stepText;
        } else {
            draft.type = stepEvent && type.isBlank() ? "step" : (type.isBlank() ? "step" : type);
            draft.stepText = text(node, "text", "stepText", "step", "gherkin");
            draft.text = draft.stepText;
            draft.kind = stepKind(draft.stepText);
        }
        draft.nodeId = nodeId(scenarioId, eventSeq, 0);
        return draft;
    }

    private static ReplayModel buildTree(String scenarioId, List<DraftBeat> drafts) {
        DraftBeat scenario = new DraftBeat();
        scenario.type = "scenario";
        scenario.kind = KIND_SCENARIO;
        scenario.scenarioId = scenarioId;
        scenario.stepText = scenarioId;
        scenario.text = scenarioId;
        scenario.nodeId = "scenario:" + scenarioId;

        Deque<DraftBeat> stack = new ArrayDeque<>();
        stack.push(scenario);
        List<DraftBeat> pendingNested = new ArrayList<>();

        for (DraftBeat draft : drafts) {
            if ("nested_scenario_start".equals(draft.type)) {
                DraftBeat parent = stack.peek();
                parent.children.add(draft);
                draft.parent = parent;
                stack.push(draft);
            } else if ("nested_scenario_end".equals(draft.type)) {
                if (stack.size() > 1) {
                    DraftBeat finished = stack.pop();
                    finished.status = draft.status.isBlank() ? finished.status : draft.status;
                    pendingNested.add(finished);
                }
            } else {
                DraftBeat parent = stack.peek();
                if (!pendingNested.isEmpty() && stack.size() == 1) {
                    parent.children.removeAll(pendingNested);
                    for (DraftBeat nested : pendingNested) {
                        nested.parent = draft;
                        draft.children.add(nested);
                    }
                    pendingNested.clear();
                    if (isServiceCallText(draft.stepText) && draft.children.size() == 1
                            && KIND_COMPONENT.equals(draft.children.getFirst().kind)) {
                        draft.children.getFirst().kind = KIND_SERVICE_CALL;
                    }
                }
                parent.children.add(draft);
                draft.parent = parent;
            }
        }

        List<ReplayBeat> beats = new ArrayList<>();
        for (DraftBeat draft : drafts) {
            beats.add(draft.freeze());
        }
        return new ReplayModel(beats, List.of(scenario.freezeNode()));
    }

    private static String nodeId(String scenarioId, long eventSeq, int fallbackIndex) {
        if (eventSeq > 0) return scenarioId + ":" + eventSeq;
        return scenarioId + ":i" + fallbackIndex;
    }

    private static String nestedLabel(JsonNode callee) {
        String name = text(callee, "scenarioName", "name");
        if (!name.isBlank()) return name;
        String uri = text(callee, "featureUri", "uri");
        return uri.isBlank() ? "nested" : uri;
    }

    private static String nestedKind(JsonNode callee) {
        String uri = text(callee, "featureUri", "uri").replace('\\', '/').toLowerCase(Locale.ROOT);
        if (uri.contains("/calls/") || uri.endsWith(".yaml") || uri.endsWith(".yml") || uri.endsWith(".json")) {
            return KIND_SERVICE_CALL;
        }
        return KIND_COMPONENT;
    }

    private static String stepKind(String stepText) {
        String text = stepText == null ? "" : stepText;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("data:/") || lower.contains("data:")) return KIND_DATA;
        return KIND_STEP;
    }

    private static boolean isServiceCallText(String stepText) {
        return stepText != null && stepText.toUpperCase(Locale.ROOT).contains("SERVICE CALL");
    }

    private static boolean isInfoPlus(String level) {
        if (level == null || level.isBlank()) return true;
        String normalized = level.trim().toUpperCase(Locale.ROOT);
        return !"TRACE".equals(normalized) && !"DEBUG".equals(normalized);
    }

    private static String featurePath(String featureUri) {
        if (featureUri == null || featureUri.isBlank()) return "";
        String normalized = featureUri.replace('\\', '/');
        if (normalized.startsWith("classpath:")) normalized = normalized.substring("classpath:".length());
        int marker = normalized.indexOf("/src/test/resources/");
        if (marker >= 0) return normalized.substring(marker + "/src/test/resources/".length());
        marker = normalized.indexOf("/src/main/resources/");
        if (marker >= 0) return normalized.substring(marker + "/src/main/resources/".length());
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        return normalized;
    }

    private static long longField(JsonNode node, String field) {
        if (node == null || field == null || !node.has(field) || node.get(field).isNull()) return 0;
        JsonNode value = node.get(field);
        if (value.isNumber()) return value.longValue();
        try {
            String text = value.asText();
            if (text == null || text.isBlank()) return 0;
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static final class DraftBeat {
        String type = "";
        String stepText = "";
        String timestamp = "";
        String level = "";
        String text = "";
        String status = "";
        Path screenshot;
        String scenarioId = "";
        final List<String> logs = new ArrayList<>();
        long eventSeq;
        int nestingLevel;
        String nestedInvocationId = "";
        String sourcePath = "";
        long sourceLine;
        String definitionClass = "";
        String definitionMethod = "";
        String definitionSourcePath = "";
        String definitionOrigin = "";
        String kind = KIND_STEP;
        String nodeId = "";
        DraftBeat parent;
        final List<DraftBeat> children = new ArrayList<>();

        ReplayBeat freeze() {
            return new ReplayBeat(
                    type,
                    stepText,
                    timestamp,
                    level,
                    text,
                    status,
                    screenshot,
                    scenarioId,
                    logs,
                    eventSeq,
                    nestingLevel,
                    nestedInvocationId,
                    sourcePath,
                    sourceLine,
                    new ReplayDefinition(definitionClass, definitionMethod, definitionSourcePath, definitionOrigin),
                    kind,
                    nodeId,
                    parent == null ? "" : parent.nodeId
            );
        }

        ReplayNode freezeNode() {
            List<ReplayNode> frozenChildren = new ArrayList<>();
            for (DraftBeat child : children) {
                frozenChildren.add(child.freezeNode());
            }
            return new ReplayNode(nodeId, kind, parent == null ? "" : parent.nodeId, freeze(), frozenChildren);
        }
    }

    private static List<Path> screenshotFiles(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        List<Path> pngs = new ArrayList<>();
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .forEach(pngs::add);
        } catch (IOException ignored) {
            return List.of();
        }
        return pngs;
    }

    public Timeline timeline(Path runRoot) {
        Path root = runRoot.toAbsolutePath().normalize();
        JsonNode index = readJsonIfPresent(root.resolve("run-index.json"));
        JsonNode clusters = readJsonIfPresent(root.resolve("clusters.json"));
        List<ScreenshotFrame> frames = new ArrayList<>();
        Path scenarios = root.resolve("scenarios");
        if (Files.isDirectory(scenarios)) {
            try (var directories = Files.list(scenarios)) {
                directories.filter(Files::isDirectory).forEach(scenarioDir ->
                        frames.addAll(framesForScenario(scenarioDir)));
            } catch (IOException ignored) {
                // Missing scenario folders are a retention gap, not a Workbench store.
            }
        }
        frames.sort(Comparator
                .comparing((ScreenshotFrame frame) -> frame.capturedAt() == null ? "" : frame.capturedAt())
                .thenComparing(frame -> frame.file().getFileName().toString()));
        return new Timeline(root, index, clusters, frames);
    }

    public List<LayerView> layers(Path runRoot, String scenarioId) {
        Path root = runRoot.toAbsolutePath().normalize();
        Path scenarioDir = scenarioId == null || scenarioId.isBlank()
                ? null
                : root.resolve("scenarios").resolve(scenarioId);
        List<LayerView> layers = new ArrayList<>();
        layers.add(layer(Layer.CATALOG, diagnosticRoot.resolve("run-catalog.json"), 40));
        layers.add(layer(Layer.RUN_INDEX, root.resolve("run-index.json"), 40));
        layers.add(layer(Layer.CLUSTERS, root.resolve("clusters.json"), 40));
        if (scenarioDir != null) {
            layers.add(layer(Layer.SUMMARY, scenarioDir.resolve("summary.json"), 40));
            layers.add(layer(Layer.EVENTS, scenarioDir.resolve("events.jsonl"), 20));
            Path comparison = firstExisting(
                    scenarioDir.resolve("comparisonToPrevious.json"),
                    scenarioDir.resolve("comparison-to-previous.json")
            );
            layers.add(layer(Layer.COMPARISON, comparison, 20));
            Path screenshots = scenarioDir.resolve("screenshots");
            layers.add(new LayerView(
                    Layer.SCREENSHOT,
                    screenshots,
                    Files.isDirectory(screenshots),
                    Files.isDirectory(screenshots) ? "PNG evidence directory" : "No screenshot directory"
            ));
            Path trace = firstExisting(scenarioDir.resolve("trace.jsonl.gz"), scenarioDir.resolve("trace.jsonl"));
            layers.add(layer(Layer.TRACE, trace, 8));
        }
        return List.copyOf(layers);
    }

    public String readExcerpt(Path path, int maxLines) {
        if (path == null || !Files.isRegularFile(path)) return "";
        try {
            List<String> lines = Files.readAllLines(path);
            int limit = Math.max(1, maxLines);
            if (lines.size() <= limit) return String.join("\n", lines);
            return String.join("\n", lines.subList(0, limit)) + "\n...";
        } catch (IOException failure) {
            return "";
        }
    }

    private List<ScreenshotFrame> framesForScenario(Path scenarioDir) {
        Path screenshots = scenarioDir.resolve("screenshots");
        if (!Files.isDirectory(screenshots)) return List.of();
        JsonNode summary = readJsonIfPresent(scenarioDir.resolve("summary.json"));
        List<String> eventSteps = eventStepTexts(scenarioDir.resolve("events.jsonl"));
        List<Path> pngs = new ArrayList<>();
        try (var files = Files.list(screenshots)) {
            files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted()
                    .forEach(pngs::add);
        } catch (IOException ignored) {
            return List.of();
        }
        List<ScreenshotFrame> frames = new ArrayList<>();
        for (int i = 0; i < pngs.size(); i++) {
            Path png = pngs.get(i);
            String step = stepForScreenshot(png, summary, eventSteps, i);
            frames.add(new ScreenshotFrame(
                    png,
                    step,
                    fileTime(png),
                    scenarioDir.getFileName().toString()
            ));
        }
        return frames;
    }

    private static String stepForScreenshot(Path png, JsonNode summary, List<String> eventSteps, int index) {
        String named = screenshotStepFromSummary(summary, png.getFileName().toString());
        if (named != null && !named.isBlank()) return named;
        if (index < eventSteps.size()) return eventSteps.get(index);
        if (summary != null && summary.hasNonNull("lastStepText")) {
            return summary.get("lastStepText").asText();
        }
        return "";
    }

    private static String screenshotStepFromSummary(JsonNode summary, String fileName) {
        if (summary == null) return "";
        JsonNode screenshots = summary.get("screenshots");
        if (screenshots == null || !screenshots.isArray()) return "";
        for (JsonNode item : screenshots) {
            String name = text(item, "file", "path", "name");
            if (name.endsWith(fileName)) {
                return text(item, "stepText", "step", "gherkin", "phrase");
            }
        }
        return "";
    }

    private static List<String> eventStepTexts(Path events) {
        if (!Files.isRegularFile(events)) return List.of();
        List<String> steps = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(events)) {
                if (line.isBlank()) continue;
                JsonNode node = JSON.readTree(line);
                String step = text(node, "stepText", "step", "gherkin", "phraseText");
                if (!step.isBlank()) steps.add(step);
            }
        } catch (IOException ignored) {
            return List.of();
        }
        return steps;
    }

    private LayerView layer(Layer layer, Path path, int excerptLines) {
        boolean present = path != null && Files.exists(path);
        return new LayerView(layer, path, present, present ? readExcerpt(path, excerptLines) : "");
    }

    private static Path firstExisting(Path... paths) {
        for (Path path : paths) {
            if (path != null && Files.exists(path)) return path;
        }
        return paths.length == 0 ? null : paths[0];
    }

    private static List<JsonNode> catalogItems(JsonNode root) {
        List<JsonNode> items = new ArrayList<>();
        if (root == null) return items;
        if (root.isArray()) {
            root.forEach(items::add);
            return items;
        }
        for (String field : List.of("runs", "entries", "items")) {
            JsonNode value = root.get(field);
            if (value != null && value.isArray()) {
                value.forEach(items::add);
                return items;
            }
        }
        if (root.has("runId") || root.has("id")) items.add(root);
        return items;
    }

    private static JsonNode readJson(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read diagnostic JSON: " + file, failure);
        }
    }

    private static JsonNode readJsonIfPresent(Path file) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException ignored) {
            return null;
        }
    }

    private static String text(JsonNode node, String... fields) {
        if (node == null) return "";
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return "";
    }

    private static String fileTime(Path file) {
        try {
            return Files.getLastModifiedTime(file).toString();
        } catch (IOException ignored) {
            return "";
        }
    }
}
