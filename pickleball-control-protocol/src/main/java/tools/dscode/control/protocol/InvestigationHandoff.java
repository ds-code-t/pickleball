package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Shared JDK-only writer for consumer-agent investigation handoffs.
 *
 * <p>This is not Control Bridge wire protocol. It lives in the protocol module so
 * Pickleball {@code DiagnosticCli} and Workbench MCP can emit identical reports
 * without giving Workbench a core Pickleball dependency.</p>
 *
 * <p>{@code investigation.json} is the source of truth. {@code report.html} is a
 * local render of that JSON plus at most two screenshots <em>linked</em> from the
 * existing diagnostic pack. The writer never copies diagnostic-run files and
 * never embeds PNG bytes.</p>
 */
public final class InvestigationHandoff {
    public static final int MAX_SCREENSHOTS = 2;
    public static final int SCHEMA_VERSION = 2;
    public static final String INVESTIGATIONS_DIRECTORY = "investigations";
    public static final String RELATIVE_ROOT = ".pickleball/" + INVESTIGATIONS_DIRECTORY;
    public static final String NOT_FIXED = "not fixed";
    public static final String OUTCOME_CAUSE_ONLY = "cause-only";
    public static final String OUTCOME_CAUSE_AND_FIX = "cause-and-fix";

    private static final Pattern INVESTIGATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final int MAX_PATH_CHARS = 1024;

    private InvestigationHandoff() {
    }

    public record Document(
            String investigationId,
            String createdAt,
            String scenarioName,
            String feature,
            String scenarioId,
            String outcome,
            String cause,
            String fix,
            String category,
            String failureSignature,
            Object failureSite,
            String runId,
            String runIndexPath,
            List<String> screenshots,
            String pickleballVersion,
            String bottomLine,
            String failedStep,
            String originatingCause,
            Object executionMap,
            Object gitSuspects,
            Object serviceCalls,
            Object environmentDelta,
            Object narrative,
            Object links
    ) {
        public Document {
            screenshots = List.copyOf(screenshots == null ? List.of() : screenshots);
            bottomLine = bottomLine == null ? "" : bottomLine;
            failedStep = failedStep == null ? "" : failedStep;
            originatingCause = originatingCause == null ? "" : originatingCause;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("schemaVersion", SCHEMA_VERSION);
            map.put("pkb_investigation_id", investigationId);
            map.put("createdAt", createdAt);
            Map<String, Object> scenario = new LinkedHashMap<>();
            putIfPresent(scenario, "name", scenarioName);
            putIfPresent(scenario, "feature", feature);
            putIfPresent(scenario, "scenarioId", scenarioId);
            if (!scenario.isEmpty()) map.put("scenario", scenario);
            map.put("outcome", outcome);
            map.put("cause", cause);
            map.put("fix", fix);
            putIfPresent(map, "category", category);
            putIfPresent(map, "failureSignature", failureSignature);
            if (failureSite != null) map.put("failureSite", failureSite);
            putIfPresent(map, "runId", runId);
            putIfPresent(map, "runIndexPath", runIndexPath);
            map.put("screenshots", screenshots);
            putIfPresent(map, "pickleballVersion", pickleballVersion);
            putIfPresent(map, "bottomLine", bottomLine);
            putIfPresent(map, "failedStep", failedStep);
            putIfPresent(map, "originatingCause", originatingCause);
            if (executionMap != null) map.put("executionMap", executionMap);
            if (gitSuspects != null) map.put("gitSuspects", gitSuspects);
            if (serviceCalls != null) map.put("serviceCalls", serviceCalls);
            if (environmentDelta != null) map.put("environmentDelta", environmentDelta);
            if (narrative != null) map.put("narrative", narrative);
            if (links != null) map.put("links", links);
            return map;
        }
    }

    public record EmitResult(String investigationId, String reportPath, Path jsonFile, Path htmlFile) {
        public Map<String, Object> sparseResult() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("reportPath", reportPath);
            return Map.copyOf(result);
        }
    }

    public static EmitResult emit(Path projectRoot, Map<String, ?> raw) throws IOException {
        Path root = requireProjectRoot(projectRoot);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Consumer project root not found: " + root);
        }
        Document document = normalize(raw, root);
        Path directory = PickleballLocalLayout.investigationsDirectory(root)
                .resolve(document.investigationId());
        Files.createDirectories(directory);

        Path jsonFile = directory.resolve("investigation.json");
        Path htmlFile = directory.resolve("report.html");
        Files.writeString(jsonFile, encodePretty(document.toMap()) + "\n", StandardCharsets.UTF_8);
        Files.writeString(htmlFile, renderHtml(document, root, directory), StandardCharsets.UTF_8);

        String reportPath = root.relativize(htmlFile).toString().replace('\\', '/');
        return new EmitResult(document.investigationId(), reportPath, jsonFile, htmlFile);
    }

    public static Document normalize(Map<String, ?> raw, Path projectRoot) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("Investigation JSON must be an object.");
        }
        Path root = requireProjectRoot(projectRoot);

        String investigationId = requireInvestigationId(firstText(raw, "pkb_investigation_id", "investigationId"));
        String createdAt = firstText(raw, "createdAt");
        if (createdAt.isBlank()) createdAt = Instant.now().toString();

        ScenarioIdentity scenario = scenarioIdentity(raw);
        String outcome = normalizeOutcome(firstText(raw, "outcome"));
        String cause = firstText(raw, "cause");
        String fix = firstText(raw, "fix");
        if (fix.isBlank()) fix = NOT_FIXED;

        String runId = firstText(raw, "runId", "diagnosticRunId");
        String runIndexPath = projectRelativePath(root, firstText(raw, "runIndexPath"));
        if (runIndexPath == null && !runId.isBlank()) {
            runIndexPath = projectRelativePath(root, "reports/diagnostic-runs/" + runId + "/run-index.json");
        }

        return new Document(
                investigationId,
                createdAt,
                scenario.name,
                scenario.feature,
                scenario.scenarioId,
                outcome,
                cause,
                fix,
                normalizeCategory(firstText(raw, "category")),
                firstText(raw, "failureSignature"),
                normalizeFailureSite(raw.get("failureSite")),
                runId,
                runIndexPath == null ? "" : runIndexPath,
                screenshotPaths(root, raw.get("screenshots")),
                firstText(raw, "pickleballVersion"),
                firstText(raw, "bottomLine"),
                firstText(raw, "failedStep"),
                firstText(raw, "originatingCause"),
                executionMapOrDerived(root, runId, raw.get("executionMap")),
                jsonValue(raw.get("gitSuspects")),
                jsonValue(raw.get("serviceCalls")),
                jsonValue(raw.get("environmentDelta")),
                jsonValue(raw.get("narrative")),
                jsonValue(raw.get("links"))
        );
    }

    public static String renderHtml(Document document, Path projectRoot) {
        Path root = requireProjectRoot(projectRoot);
        Path reportDir = PickleballLocalLayout.investigationsDirectory(root)
                .resolve(document.investigationId());
        return renderHtml(document, root, reportDir);
    }

    static String renderHtml(Document document, Path projectRoot, Path reportDir) {
        String title = document.scenarioName().isBlank()
                ? document.investigationId()
                : document.scenarioName();
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
        html.append("<title>").append(escape(title)).append("</title>\n");
        html.append("<style>\n");
        html.append("body{font-family:system-ui,sans-serif;max-width:48rem;margin:2rem auto;padding:0 1rem;line-height:1.45;color:#111}\n");
        html.append("h1{font-size:1.35rem;margin-bottom:.25rem}\n");
        html.append(".meta,.missing{color:#444}\n");
        html.append(".missing{color:#a40000}\n");
        html.append("section{margin:1.25rem 0}\n");
        html.append("img{max-width:100%;height:auto;border:1px solid #ccc}\n");
        html.append("pre{white-space:pre-wrap;overflow-wrap:anywhere}\n");
        html.append("ul.tree{list-style:none;padding-left:1.2rem}\n");
        html.append("ul.tree ul{padding-left:1.2rem}\n");
        html.append("</style>\n</head><body>\n");
        html.append("<h1>").append(escape(title)).append("</h1>\n");
        String bottom = document.bottomLine().isBlank() ? document.cause() : document.bottomLine();
        if (!bottom.isBlank()) {
            html.append("<p><strong>Bottom line:</strong> ").append(escape(bottom)).append("</p>\n");
        }
        html.append("<p class=\"meta\">Investigation ").append(escape(document.investigationId()));
        html.append(" · ").append(escape(document.outcome()));
        if (!document.createdAt().isBlank()) {
            html.append(" · ").append(escape(document.createdAt()));
        }
        html.append("</p>\n");

        html.append("<section><h2>Where</h2>\n");
        if (!document.feature().isBlank() || !document.scenarioName().isBlank()) {
            html.append("<p>").append(escape(document.feature()));
            if (!document.scenarioName().isBlank()) {
                html.append(" Scenario \"").append(escape(document.scenarioName())).append('"');
            }
            html.append("</p>\n");
        }
        appendExecutionMap(html, document);
        html.append("</section>\n");

        String cause = document.originatingCause().isBlank() ? document.cause() : document.originatingCause();
        section(html, "Cause", cause);
        if (!document.failedStep().isBlank()) {
            section(html, "Failed assertion", document.failedStep());
        }
        section(html, "Next", document.fix());
        appendObjectSection(html, "Git suspects", document.gitSuspects());
        appendObjectSection(html, "Service calls", document.serviceCalls());
        appendObjectSection(html, "Environment", document.environmentDelta());
        appendNarrative(html, document.narrative());

        html.append("<section><h2>Scenario</h2>\n<dl>\n");
        definition(html, "Name", document.scenarioName());
        definition(html, "Feature", document.feature());
        definition(html, "Scenario id", document.scenarioId());
        definition(html, "Category", document.category());
        definition(html, "Failure signature", document.failureSignature());
        if (document.failureSite() != null) {
            definition(html, "Failure site", failureSiteText(document.failureSite()));
        }
        html.append("</dl></section>\n");

        html.append("<section><h2>Diagnostic run</h2>\n");
        if (document.runId().isBlank() && document.runIndexPath().isBlank()) {
            html.append("<p class=\"meta\">No diagnostic run pointer.</p>\n");
        } else {
            html.append("<dl>\n");
            definition(html, "Run id", document.runId());
            if (!document.runIndexPath().isBlank()) {
                html.append("<dt>run-index</dt><dd>");
                html.append(pathMarkup(projectRoot, reportDir, document.runIndexPath(), false));
                html.append("</dd>\n");
            }
            html.append("</dl>\n");
        }
        html.append("</section>\n");

        html.append("<section><h2>Screenshots</h2>\n");
        if (document.screenshots().isEmpty()) {
            html.append("<p class=\"meta\">No screenshots selected.</p>\n");
        } else {
            int index = 1;
            for (String screenshot : document.screenshots()) {
                html.append(pathMarkup(projectRoot, reportDir, screenshot, true));
                if (index < document.screenshots().size()) html.append('\n');
                index++;
            }
        }
        html.append("</section>\n");

        if (!document.pickleballVersion().isBlank()) {
            html.append("<p class=\"meta\">Pickleball ").append(escape(document.pickleballVersion())).append("</p>\n");
        }
        html.append("</body></html>\n");
        return html.toString();
    }

    public static Path investigationsRoot(Path pickleballDirectory) {
        return pickleballDirectory.resolve(INVESTIGATIONS_DIRECTORY);
    }

    public static boolean isInvestigationsPath(Path pickleballDirectory, Path path) {
        if (pickleballDirectory == null || path == null) return false;
        Path investigations = investigationsRoot(pickleballDirectory).toAbsolutePath().normalize();
        Path resolved = path.toAbsolutePath().normalize();
        if (resolved.equals(investigations) || resolved.startsWith(investigations)) return true;
        Path pickleball = pickleballDirectory.toAbsolutePath().normalize();
        Path versions = pickleball.resolve(PickleballLocalLayout.VERSIONS_DIRECTORY);
        if (!resolved.startsWith(versions)) return false;
        return resolved.getFileName() != null
                && (INVESTIGATIONS_DIRECTORY.equals(resolved.getFileName().toString())
                || resolved.toString().replace('\\', '/').contains("/" + INVESTIGATIONS_DIRECTORY + "/"));
    }

    private static Path requireProjectRoot(Path projectRoot) {
        if (projectRoot == null) {
            throw new IllegalArgumentException("Consumer project root is required.");
        }
        return projectRoot.toAbsolutePath().normalize();
    }

    private static String requireInvestigationId(String value) {
        String id = value == null ? "" : value.trim();
        if (!INVESTIGATION_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "pkb_investigation_id must be a simple directory name "
                            + "[A-Za-z0-9][A-Za-z0-9._-]* up to 128 characters."
            );
        }
        return id;
    }

    private static ScenarioIdentity scenarioIdentity(Map<String, ?> raw) {
        String name = firstText(raw, "scenarioName");
        String feature = firstText(raw, "feature");
        String scenarioId = firstText(raw, "scenarioId");
        Object scenario = raw.get("scenario");
        if (scenario instanceof String text) {
            if (name.isBlank()) name = text.trim();
        } else if (scenario instanceof Map<?, ?> map) {
            if (name.isBlank()) name = firstText(map, "name", "scenarioName", "title");
            if (feature.isBlank()) feature = firstText(map, "feature", "uri", "featureUri");
            if (scenarioId.isBlank()) scenarioId = firstText(map, "scenarioId", "id");
        }
        return new ScenarioIdentity(name, feature, scenarioId);
    }

    private static String normalizeOutcome(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
        if (normalized.equals(OUTCOME_CAUSE_AND_FIX) || normalized.equals("causeandfix")) {
            return OUTCOME_CAUSE_AND_FIX;
        }
        return OUTCOME_CAUSE_ONLY;
    }

    private static String normalizeCategory(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "selector", "gherkin", "java", "data", "other" -> normalized;
            default -> value.trim();
        };
    }

    private static Object normalizeFailureSite(Object value) {
        if (value == null) return null;
        if (value instanceof String text) return text.trim();
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, child) -> {
                if (key instanceof String name && !name.isBlank() && isJsonValue(child)) {
                    copy.put(name, child);
                }
            });
            return copy.isEmpty() ? null : Map.copyOf(copy);
        }
        return String.valueOf(value);
    }

    private static List<String> screenshotPaths(Path projectRoot, Object value) {
        if (!(value instanceof List<?> entries)) return List.of();
        List<String> paths = new ArrayList<>();
        for (Object entry : entries) {
            if (paths.size() >= MAX_SCREENSHOTS) break;
            String raw = screenshotEntry(entry);
            if (raw == null) continue;
            String relative = projectRelativePath(projectRoot, raw);
            if (relative != null) paths.add(relative);
        }
        return List.copyOf(paths);
    }

    private static String screenshotEntry(Object entry) {
        if (entry instanceof String text) return text;
        if (entry instanceof Map<?, ?> map) {
            return firstText(map, "path", "file", "src");
        }
        return null;
    }

    private static String projectRelativePath(Path projectRoot, String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_PATH_CHARS) return null;
        if (trimmed.contains("\n") || trimmed.contains("\r") || trimmed.startsWith("data:")) return null;
        try {
            Path path = Path.of(trimmed);
            Path resolved = path.isAbsolute()
                    ? path.normalize()
                    : projectRoot.resolve(trimmed).normalize();
            if (!resolved.startsWith(projectRoot)) return null;
            String relative = projectRoot.relativize(resolved).toString().replace('\\', '/');
            if (relative.isEmpty() || relative.startsWith("../")) return null;
            return relative;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String pathMarkup(Path projectRoot, Path reportDir, String relative, boolean image) {
        Path target = projectRoot.resolve(relative).normalize();
        boolean present = Files.isRegularFile(target);
        if (image) {
            if (!present) {
                return "<p class=\"missing\">Screenshot missing: " + escape(relative) + "</p>\n";
            }
            return "<p><img src=\"" + escape(relativeHref(reportDir, target))
                    + "\" alt=\"" + escape(relative) + "\"></p>\n";
        }
        if (!present) {
            return "<span class=\"missing\">" + escape(relative) + " (missing)</span>";
        }
        return "<a href=\"" + escape(relativeHref(reportDir, target)) + "\">"
                + escape(relative) + "</a>";
    }

    private static String relativeHref(Path reportDir, Path target) {
        Path from = reportDir.toAbsolutePath().normalize();
        Path to = target.toAbsolutePath().normalize();
        return from.relativize(to).toString().replace('\\', '/');
    }

    private static void section(StringBuilder html, String heading, String body) {
        html.append("<section><h2>").append(escape(heading)).append("</h2>\n");
        html.append("<pre>").append(escape(body)).append("</pre>\n</section>\n");
    }

    private static void definition(StringBuilder html, String term, String value) {
        if (value == null || value.isBlank()) return;
        html.append("<dt>").append(escape(term)).append("</dt><dd>")
                .append(escape(value)).append("</dd>\n");
    }

    private static String failureSiteText(Object failureSite) {
        if (failureSite instanceof Map<?, ?> map) {
            StringBuilder text = new StringBuilder();
            map.forEach((key, value) -> {
                if (!text.isEmpty()) text.append('\n');
                text.append(key).append(": ").append(value);
            });
            return text.toString();
        }
        return String.valueOf(failureSite);
    }

    private static Object jsonValue(Object value) {
        if (value == null) return null;
        if (!isJsonValue(value)) return String.valueOf(value);
        return value;
    }

    private static Object executionMapOrDerived(Path root, String runId, Object provided) {
        if (provided instanceof List<?> list && !list.isEmpty()) return jsonValue(provided);
        if (provided != null && !(provided instanceof List<?>)) return jsonValue(provided);
        List<Map<String, Object>> derived = deriveExecutionMap(root, runId);
        return derived == null || derived.isEmpty() ? null : derived;
    }

    static List<Map<String, Object>> deriveExecutionMap(Path projectRoot, String runId) {
        if (projectRoot == null || runId == null || runId.isBlank() || runId.contains("..")
                || runId.contains("/") || runId.contains("\\")) {
            return List.of();
        }
        Path scenarios = projectRoot.resolve("reports").resolve("diagnostic-runs").resolve(runId).resolve("scenarios");
        if (!Files.isDirectory(scenarios)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        try (var directories = Files.list(scenarios)) {
            for (Path scenarioDir : directories.filter(Files::isDirectory).sorted().toList()) {
                Path events = scenarioDir.resolve("events.jsonl");
                if (!Files.isRegularFile(events)) continue;
                for (String line : Files.readAllLines(events, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    Map<String, Object> row = executionRowFromEvent(line);
                    if (row != null) rows.add(row);
                }
            }
        } catch (IOException ignored) {
            return rows;
        }
        return rows;
    }

    private static Map<String, Object> executionRowFromEvent(String line) {
        String type = jsonString(line, "type");
        if ("nested_scenario_end".equals(type) || "log".equals(type) || "screenshot".equals(type)
                || "failure".equals(type) || "scenario_end".equals(type)) {
            return null;
        }
        Map<String, Object> row = new LinkedHashMap<>();
        long seq = jsonLong(line, "eventSeq");
        if (seq > 0) row.put("eventSeq", seq);
        String path = jsonString(line, "path");
        if (path.isBlank()) path = nestedJsonString(line, "source", "path");
        if (!path.isBlank()) row.put("path", path);
        long sourceLine = jsonLong(line, "line");
        if (sourceLine <= 0) sourceLine = nestedJsonLong(line, "source", "line");
        if (sourceLine > 0) row.put("line", sourceLine);
        if ("nested_scenario_start".equals(type)) {
            String name = nestedJsonString(line, "callee", "scenarioName");
            if (name.isBlank()) name = jsonString(line, "scenarioName");
            row.put("kind", "component");
            row.put("text", name.isBlank() ? "nested" : name);
            row.put("indent", 1);
            return row;
        }
        String text = jsonString(line, "text");
        if (text.isBlank()) text = jsonString(line, "stepText");
        if (text.isBlank() && !"step".equals(type) && !type.isBlank()) return null;
        if (text.isBlank()) return null;
        row.put("kind", "step");
        row.put("text", text);
        long nesting = jsonLong(line, "nestingLevel");
        row.put("indent", Math.max(0, nesting));
        return row;
    }

    private static void appendExecutionMap(StringBuilder html, Document document) {
        Object executionMap = document.executionMap();
        if (!(executionMap instanceof List<?> items) || items.isEmpty()) return;
        html.append("<ul class=\"tree\">\n");
        for (Object item : items) {
            html.append("<li>");
            if (item instanceof Map<?, ?> map) {
                String indent = firstText(map, "indent", "depth");
                int depth = 0;
                try {
                    if (!indent.isBlank()) depth = Integer.parseInt(indent);
                } catch (NumberFormatException ignored) {
                }
                html.append("&nbsp;".repeat(Math.max(0, depth) * 2));
                String kind = firstText(map, "kind", "type");
                String text = firstText(map, "text", "stepText", "label", "name");
                if (!kind.isBlank()) html.append(escape(kind)).append(": ");
                String label = text.isBlank() ? String.valueOf(item) : text;
                String href = explorerHref(document.runId(), map);
                if (href.isBlank()) {
                    html.append(escape(label));
                } else {
                    html.append("<a href=\"").append(escape(href)).append("\">").append(escape(label)).append("</a>");
                }
            } else {
                html.append(escape(String.valueOf(item)));
            }
            html.append("</li>\n");
        }
        html.append("</ul>\n");
    }

    private static String explorerHref(String runId, Map<?, ?> map) {
        if (runId == null || runId.isBlank()) return "";
        StringBuilder href = new StringBuilder("wb://explorer?run=").append(urlQuery(runId));
        String seq = firstText(map, "eventSeq", "seq");
        if (!seq.isBlank()) href.append("&seq=").append(urlQuery(seq));
        String path = firstText(map, "path");
        if (!path.isBlank()) href.append("&path=").append(urlQuery(path));
        String line = firstText(map, "line");
        if (!line.isBlank()) href.append("&line=").append(urlQuery(line));
        String kind = firstText(map, "kind", "type");
        if (!kind.isBlank()) href.append("&kind=").append(urlQuery(kind));
        return href.toString();
    }

    private static String urlQuery(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                    || ch == '-' || ch == '_' || ch == '.' || ch == '~' || ch == '/') {
                out.append(ch);
            } else if (ch == ' ') {
                out.append("%20");
            } else {
                out.append('%');
                out.append("0123456789ABCDEF".charAt((ch >> 4) & 0xF));
                out.append("0123456789ABCDEF".charAt(ch & 0xF));
            }
        }
        return out.toString();
    }

    private static String jsonString(String json, String field) {
        if (json == null || field == null) return "";
        String key = "\"" + field + "\"";
        int at = indexOfField(json, key);
        if (at < 0) return "";
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        i++;
        StringBuilder text = new StringBuilder();
        while (i < json.length()) {
            char ch = json.charAt(i++);
            if (ch == '\\' && i < json.length()) {
                text.append(json.charAt(i++));
            } else if (ch == '"') {
                break;
            } else {
                text.append(ch);
            }
        }
        return text.toString();
    }

    private static long jsonLong(String json, String field) {
        if (json == null || field == null) return 0;
        String key = "\"" + field + "\"";
        int at = indexOfField(json, key);
        if (at < 0) return 0;
        int colon = json.indexOf(':', at + key.length());
        if (colon < 0) return 0;
        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        if (i < json.length() && json.charAt(i) == '-') i++;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (start == i) return 0;
        try {
            return Long.parseLong(json.substring(start, i));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int indexOfField(String json, String key) {
        int from = 0;
        while (from < json.length()) {
            int at = json.indexOf(key, from);
            if (at < 0) return -1;
            if (at == 0 || json.charAt(at - 1) != '\\') return at;
            from = at + key.length();
        }
        return -1;
    }

    private static String nestedJsonString(String json, String object, String field) {
        String value = jsonString(json, field);
        return value;
    }

    private static long nestedJsonLong(String json, String object, String field) {
        return jsonLong(json, field);
    }

    private static void appendObjectSection(StringBuilder html, String heading, Object value) {
        if (value == null) return;
        if (value instanceof String text && text.isBlank()) return;
        if (value instanceof List<?> list && list.isEmpty()) return;
        if (value instanceof Map<?, ?> map && map.isEmpty()) return;
        html.append("<section><h2>").append(escape(heading)).append("</h2>\n<pre>");
        html.append(escape(value instanceof String text ? text : encodePretty(value)));
        html.append("</pre>\n</section>\n");
    }

    private static void appendNarrative(StringBuilder html, Object narrative) {
        if (!(narrative instanceof List<?> items) || items.isEmpty()) return;
        html.append("<section><h2>Narrative</h2>\n");
        for (Object item : items) {
            html.append("<p>").append(escape(String.valueOf(item))).append("</p>\n");
        }
        html.append("</section>\n");
    }

    private static String firstText(Map<?, ?> map, String... keys) {
        if (map == null) return "";
        for (String key : keys) {
            Object value = map.get(key);
            if (value instanceof String text && !text.isBlank()) return text.trim();
            if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        }
        return "";
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }

    private static boolean isJsonValue(Object value) {
        return value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Map<?, ?>
                || value instanceof List<?>;
    }

    static String encodePretty(Object value) {
        StringBuilder json = new StringBuilder();
        encode(json, value, 0);
        return json.toString();
    }

    private static void encode(StringBuilder json, Object value, int indent) {
        if (value == null) {
            json.append("null");
            return;
        }
        if (value instanceof String text) {
            json.append('"').append(escapeJson(text)).append('"');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            json.append('{');
            if (map.isEmpty()) {
                json.append('}');
                return;
            }
            json.append('\n');
            int index = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pad(json, indent + 1);
                json.append('"').append(escapeJson(String.valueOf(entry.getKey()))).append("\": ");
                encode(json, entry.getValue(), indent + 1);
                index++;
                json.append(index < map.size() ? ",\n" : "\n");
            }
            pad(json, indent);
            json.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            json.append('[');
            if (list.isEmpty()) {
                json.append(']');
                return;
            }
            json.append('\n');
            for (int i = 0; i < list.size(); i++) {
                pad(json, indent + 1);
                encode(json, list.get(i), indent + 1);
                json.append(i + 1 < list.size() ? ",\n" : "\n");
            }
            pad(json, indent);
            json.append(']');
            return;
        }
        json.append('"').append(escapeJson(String.valueOf(value))).append('"');
    }

    private static void pad(StringBuilder json, int indent) {
        json.append("  ".repeat(Math.max(0, indent)));
    }

    static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(ch);
            }
        }
        return escaped.toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private record ScenarioIdentity(String name, String feature, String scenarioId) { }
}
