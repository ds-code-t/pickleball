package tools.dscode.workbench.diagnostics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

    public record CatalogRun(String runId, Path runRoot, JsonNode raw) { }

    public record ScreenshotFrame(
            Path file,
            String stepText,
            String capturedAt,
            String scenarioId
    ) { }

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
