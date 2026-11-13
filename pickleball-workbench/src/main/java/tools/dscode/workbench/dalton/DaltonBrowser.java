package tools.dscode.workbench.dalton;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command lists and human labels for the Dalton tab.
 * Workbench never opens SQLite. {@link DaltonCliForwarder} runs these arguments.
 */
public final class DaltonBrowser {
    private static final ObjectMapper JSON = new ObjectMapper();

    private DaltonBrowser() {
    }

    public static List<String> files() {
        return List.of("files", "list", "--json");
    }

    public static List<String> features() {
        return List.of("features", "list", "--json");
    }

    public static List<String> scenarios(String feature) {
        List<String> args = new ArrayList<>();
        args.add("scenarios");
        args.add("list");
        if (feature != null && !feature.isBlank()) args.add("--feature=" + feature.trim());
        args.add("--json");
        return List.copyOf(args);
    }

    public static List<String> steps(String feature, String scenario) {
        List<String> args = new ArrayList<>();
        args.add("steps");
        args.add("list");
        if (feature != null && !feature.isBlank()) args.add("--feature=" + feature.trim());
        if (scenario != null && !scenario.isBlank()) args.add("--scenario=" + scenario.trim());
        args.add("--json");
        return List.copyOf(args);
    }

    public static List<String> entities() {
        return List.of("entity", "list");
    }

    public static List<String> entityGet(String type, String id) {
        return List.of("entity", "get", type, id);
    }

    public static List<String> history() {
        return List.of("history", "list");
    }

    public static List<String> historyShow(String id) {
        return List.of("history", "show", id);
    }

    public static List<String> conflicts() {
        return List.of("conflicts", "--json");
    }

    public static List<String> status() {
        return List.of("status");
    }

    public static List<String> hydrate(boolean force) {
        return force ? List.of("hydrate", "--force") : List.of("hydrate");
    }

    public static List<String> extract(boolean force) {
        return force ? List.of("extract", "--force") : List.of("extract");
    }

    public static List<String> run(boolean dryRun) {
        return dryRun ? List.of("run", "--dry-run") : List.of("run");
    }

    public static List<String> featureShow(String path) {
        return List.of("features", "show", path, "--json");
    }

    public static List<String> fileShow(String path) {
        return List.of("files", "show", path, "--json");
    }

    public static List<String> featurePut(String path, Path file) {
        return List.of("feature", "put", path, "--file=" + file.toAbsolutePath().normalize());
    }

    public static List<String> entityPut(String type, String id, String version, Path payloadFile) {
        List<String> args = new ArrayList<>();
        args.add("entity");
        args.add("put");
        args.add(type);
        args.add(id);
        if (version != null && !version.isBlank()) args.add("--version=" + version.trim());
        args.add("--payload-file=" + payloadFile.toAbsolutePath().normalize());
        return List.copyOf(args);
    }

    public static List<String> categoryList() {
        return List.of("category", "list", "--json");
    }

    public static List<String> categoryShow(String id) {
        return List.of("category", "show", "--id=" + id.trim(), "--json");
    }

    public static List<String> categoryPut(String id, Path file) {
        return List.of("category", "put", "--id=" + id.trim(), "--file=" + file.toAbsolutePath().normalize(), "--json");
    }

    public static List<String> categoryRemove(String id) {
        return List.of("category", "remove", "--id=" + id.trim(), "--confirm", "--json");
    }

    public static List<String> filePut(String path, Path file) {
        return List.of("file", "put", "--path=" + path.trim(), "--file=" + file.toAbsolutePath().normalize(), "--json");
    }

    public static List<String> fileDelete(String path) {
        return List.of("file", "delete", "--path=" + path.trim(), "--confirm", "--json");
    }

    public static List<String> glueList() {
        return List.of("glue", "list", "--json");
    }

    public static List<String> glueShow(String path) {
        return List.of("glue", "show", "--path=" + path.trim(), "--json");
    }

    public static List<String> gluePut(String path, Path file) {
        return List.of("glue", "put", "--path=" + path.trim(), "--file=" + file.toAbsolutePath().normalize(), "--json");
    }

    public static List<String> glueRemove(String path) {
        return List.of("glue", "remove", "--path=" + path.trim(), "--confirm", "--json");
    }

    public static List<String> scenarioAdd(String feature, String name, Path file) {
        return List.of("feature", "scenario-add", "--feature=" + feature.trim(), "--name=" + name.trim(),
                "--file=" + file.toAbsolutePath().normalize(), "--json");
    }

    public static List<String> scenarioModify(String feature, String scenario, Path file) {
        return List.of("feature", "scenario-modify", "--feature=" + feature.trim(), "--scenario=" + scenario.trim(),
                "--file=" + file.toAbsolutePath().normalize(), "--json");
    }

    public static List<String> scenarioDelete(String feature, String scenario) {
        return List.of("feature", "scenario-delete", "--feature=" + feature.trim(), "--scenario=" + scenario.trim(),
                "--confirm", "--json");
    }

    public static List<String> stepAdd(String feature, String scenario, Path file) {
        return List.of("feature", "step-add", "--feature=" + feature.trim(), "--scenario=" + scenario.trim(),
                "--file=" + file.toAbsolutePath().normalize(), "--json");
    }

    public static List<String> stepModify(String feature, String scenario, String step, Path file) {
        return List.of("feature", "step-modify", "--feature=" + feature.trim(), "--scenario=" + scenario.trim(),
                "--step=" + step.trim(), "--file=" + file.toAbsolutePath().normalize(), "--json");
    }

    public static List<String> stepDelete(String feature, String scenario, String step) {
        return List.of("feature", "step-delete", "--feature=" + feature.trim(), "--scenario=" + scenario.trim(),
                "--step=" + step.trim(), "--confirm", "--json");
    }

    /** Human lines for a CLI body. JSON becomes labels. Other text is kept. */
    public static List<String> labels(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) return List.of("none");
        if (!trimmed.startsWith("{")) return textLabels(trimmed);
        try {
            JsonNode root = JSON.readTree(trimmed);
            if (root.has("files") && root.get("files").isArray()) return arrayLabels(root.get("files"), DaltonBrowser::fileLabel, "Files");
            if (root.has("features") && root.get("features").isArray()) return arrayLabels(root.get("features"), DaltonBrowser::featureLabel, "Features");
            if (root.has("steps") && root.get("steps").isArray()) return arrayLabels(root.get("steps"), DaltonBrowser::stepLabel, "Steps");
            if (root.has("scenarios") && root.get("scenarios").isArray() && !root.has("text")) {
                return arrayLabels(root.get("scenarios"), DaltonBrowser::scenarioLabel, "Scenarios");
            }
            if (root.has("conflicts") && root.get("conflicts").isArray()) return arrayLabels(root.get("conflicts"), DaltonBrowser::conflictLabel, "Conflicts");
            if (root.has("items") && root.get("items").isArray()) return arrayLabels(root.get("items"), DaltonBrowser::itemLabel, "Items");
            if (root.has("path") && root.has("sourceType")) return List.of(fileLabel(root));
            if (root.has("path") && root.has("name")) return List.of(featureLabel(root));
            if (root.has("database")) return List.of(statusLabel(root));
            return List.of(trimmed);
        } catch (Exception failure) {
            return List.of(trimmed);
        }
    }

    /** Feature or file text for the editor. Binary files return an empty string. */
    public static String editableText(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.startsWith("{")) return body == null ? "" : body;
        try {
            JsonNode root = JSON.readTree(trimmed);
            if (root.has("text")) {
                return root.get("text").isNull() ? "" : root.get("text").asText();
            }
            return body;
        } catch (Exception failure) {
            return body == null ? "" : body;
        }
    }

    public static boolean binary(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.startsWith("{")) return false;
        try {
            JsonNode root = JSON.readTree(trimmed);
            return root.has("text") && root.get("text").isNull();
        } catch (Exception failure) {
            return false;
        }
    }

    private static List<String> arrayLabels(JsonNode array, java.util.function.Function<JsonNode, String> label, String name) {
        if (array == null || !array.isArray() || array.isEmpty()) return List.of(name + ": none");
        List<String> lines = new ArrayList<>();
        for (JsonNode node : array) lines.add(label.apply(node));
        return lines;
    }

    private static String fileLabel(JsonNode node) {
        return node.path("path").asText()
                + "  " + node.path("sourceType").asText()
                + "  edited=" + (node.path("edited").asBoolean() ? "yes" : "no")
                + "  bytes=" + node.path("bytes").asInt();
    }

    private static String featureLabel(JsonNode node) {
        int scenarios = node.has("scenarioCount") ? node.path("scenarioCount").asInt() : node.path("scenarios").asInt();
        return node.path("path").asText()
                + "  " + node.path("name").asText()
                + "  scenarios=" + scenarios;
    }

    private static String scenarioLabel(JsonNode node) {
        return node.path("path").asText()
                + "  " + node.path("ord").asInt()
                + "  " + node.path("keyword").asText()
                + "  " + node.path("name").asText();
    }

    private static String stepLabel(JsonNode node) {
        return node.path("path").asText()
                + "  " + node.path("scenario").asText()
                + "  " + node.path("ord").asInt()
                + "  " + node.path("keyword").asText()
                + "  " + node.path("text").asText();
    }

    private static String conflictLabel(JsonNode node) {
        String message = node.path("message").asText();
        if (!message.isBlank()) return message;
        return node.path("kind").asText() + " " + node.path("path").asText();
    }

    private static String itemLabel(JsonNode node) {
        String id = node.path("id").asText();
        String path = node.path("path").asText();
        if (id.isBlank()) return path;
        if (path.isBlank() || path.equals(id)) return id;
        return id + "  " + path;
    }

    private static String statusLabel(JsonNode node) {
        StringBuilder line = new StringBuilder();
        line.append("database ").append(node.path("database").asText());
        if (node.has("files")) line.append("  files=").append(node.path("files").asInt());
        if (node.has("scenarios")) line.append("  scenarios=").append(node.path("scenarios").asInt());
        if (node.has("conflicts")) line.append("  conflicts=").append(node.path("conflicts").asInt());
        if (node.has("skipped")) line.append("  skipped=").append(node.path("skipped").asInt());
        if (node.has("error")) line.append("  ").append(node.path("error").asText());
        return line.toString();
    }

    private static List<String> textLabels(String text) {
        String[] lines = text.split("\\R");
        List<String> labels = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(" ");
            if (parts.length == 3 && parts[2].chars().allMatch(Character::isDigit)) {
                labels.add("Entity " + parts[0] + " " + parts[1] + " version " + parts[2]);
            } else if ("entities".equals(parts[0]) && parts.length == 2) {
                labels.add("Entities: " + ("0".equals(parts[1]) ? "none" : parts[1]));
            } else if ("runs".equals(parts[0]) && parts.length == 2) {
                labels.add("Runs: " + ("0".equals(parts[1]) ? "none" : parts[1]));
            } else if (line.startsWith("run ")) {
                labels.add("Run " + line.substring(4));
            } else {
                labels.add(line);
            }
        }
        return labels.isEmpty() ? List.of("none") : labels;
    }
}
