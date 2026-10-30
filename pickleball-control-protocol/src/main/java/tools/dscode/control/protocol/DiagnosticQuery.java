package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Read-only query over retained diagnostic JSON. Files stay the source of truth.
 * The same method serves the launcher {@code query} command and the MCP retrieval tools.
 */
public final class DiagnosticQuery {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;
    /** Default compact JSON stays under this many UTF-8 bytes. */
    public static final int DEFAULT_BUDGET_BYTES = 4096;
    public static final String TRUNCATION_MARK = "…+";

    private static final int TEXT_LIMIT = 160;
    private static final int SCAN_LINES = 20_000;
    private static final int FILE_BYTES = 4_000_000;
    private static final Set<String> OPS = Set.of("runs", "get", "query", "search", "props", "errors");
    private static final Set<String> KINDS = Set.of(
            "run", "scenario", "step", "event", "property", "artifact", "error", "log"
    );

    private DiagnosticQuery() {
    }

    public record Outcome(Map<String, Object> body, String json, int exit) {
    }

    public static Outcome query(Path project, Map<String, ?> request) {
        return query(project, request, DEFAULT_BUDGET_BYTES);
    }

    static Outcome query(Path project, Map<String, ?> request, int budgetBytes) {
        if (project == null) {
            return error(2, "missing project", "Pass the consumer project directory.");
        }
        String op = text(request, "op");
        if (op == null || !OPS.contains(op)) {
            return error(2, "missing op",
                    "Usage: query <runs|get|query|search|props|errors> [--run-id=<id>] [--kind=<kind>] [--limit=<n>] [--cursor=<token>]");
        }
        String runId = firstText(request, "runId", "run");
        String scenarioId = firstText(request, "scenarioId", "scenario");
        String kind = text(request, "kind");
        String needle = text(request, "text");
        String fields = text(request, "fields");
        String status = firstText(request, "status", "outcome");
        String id = text(request, "id");
        if (runId != null && !safeId(runId)) {
            return error(2, "bad runId", "runId must be a single folder name. query runs lists ids.");
        }
        if (scenarioId != null && !safeId(scenarioId)) {
            return error(2, "bad scenarioId", "scenarioId must be a single folder name.");
        }
        if (kind != null && !KINDS.contains(kind)) {
            return error(2, "bad kind", "kind is run, scenario, step, event, property, artifact, error, or log.");
        }
        int limit;
        try {
            limit = limit(request);
        } catch (IllegalArgumentException failure) {
            return error(2, "bad limit", "limit is a whole number from 1 to " + MAX_LIMIT + ".");
        }
        int offset;
        try {
            offset = cursor(text(request, "cursor"));
        } catch (IllegalArgumentException failure) {
            return error(2, "bad cursor", "Pass nextCursor from the previous page. Example: --cursor=20");
        }
        if (("get".equals(op) || "props".equals(op)) && runId == null) {
            return error(2, "missing runId", "Usage: query " + op + " --run-id=<id>");
        }
        if ("search".equals(op) && needle == null) {
            return error(2, "missing text", "Usage: query search --text=<words>");
        }
        if ("get".equals(op) && kind == null) kind = "run";
        if ("props".equals(op)) kind = "property";
        if ("errors".equals(op)) kind = "error";
        if ("runs".equals(op)) kind = "run";
        if ("search".equals(op) && kind == null) kind = null;
        if ("query".equals(op) && kind == null) kind = runId == null ? "run" : "event";

        Path root = project.toAbsolutePath().normalize();
        boolean capped = false;
        List<Map<String, Object>> matched = null;
        String where = null;
        if ("search".equals(op) && !unpaged(request)) {
            List<Map<String, Object>> indexed = DiagnosticSqliteIndex.find(root, needle, runId, kind);
            if (indexed != null) {
                matched = indexed;
                where = "reports/diagnostic-runs/" + DiagnosticSqliteIndex.FILE_NAME;
            }
        }
        if (matched == null) {
            try {
                Loaded loaded = load(root, op, runId, scenarioId, kind, needle, status, id);
                matched = loaded.items;
                where = loaded.where;
                capped = loaded.capped;
            } catch (IOException failure) {
                return error(1, "unreadable diagnostics", "Could not read diagnostics: " + oneLine(failure.getMessage()));
            }
        }
        int total = matched.size();
        if (offset > total) offset = total;
        List<Map<String, Object>> page = new ArrayList<>();
        int end = Math.min(total, offset + limit);
        for (int index = offset; index < end; index++) page.add(project(matched.get(index), fields));

        int budget = budgetBytes <= 0 ? DEFAULT_BUDGET_BYTES : budgetBytes;
        boolean budgeted = !unpaged(request) && fields == null && (request == null || request.get("limit") == null
                || limit <= DEFAULT_LIMIT);
        int dropped = 0;
        if (budgeted) {
            while (page.size() > 1 && utf8(envelope(op, where, limit, total, offset, page, capped, 0)) > budget) {
                page.remove(page.size() - 1);
                dropped++;
            }
        }
        Map<String, Object> body = envelope(op, where, limit, total, offset, page, capped, dropped);
        String json = JsonLite.write(body);
        if (budgeted && json.getBytes(StandardCharsets.UTF_8).length > budget && page.size() > 1) {
            page.subList(1, page.size()).clear();
            body = envelope(op, where, limit, total, offset, page, capped, total - offset - 1);
            json = JsonLite.write(body);
        }
        return new Outcome(body, json, 0);
    }

    private static Map<String, Object> envelope(
            String op,
            String where,
            int limit,
            int total,
            int offset,
            List<Map<String, Object>> page,
            boolean capped,
            int extraDropped
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("v", 1);
        body.put("op", op);
        body.put("where", where);
        body.put("limit", limit);
        body.put("matched", total);
        body.put("items", page);
        int next = offset + page.size();
        if (next < total || extraDropped > 0) {
            int more = Math.max(0, total - next);
            body.put("nextCursor", Integer.toString(next));
            body.put("truncated", TRUNCATION_MARK + more + " more, use cursor");
        }
        if (capped) body.put("scanCapped", true);
        return body;
    }

    private record Loaded(List<Map<String, Object>> items, String where, boolean capped) {
    }

    private static Loaded load(
            Path project,
            String op,
            String runId,
            String scenarioId,
            String kind,
            String needle,
            String status,
            String id
    ) throws IOException {
        Catalog catalog = readCatalog(project);
        if ("runs".equals(op) || ("query".equals(op) && "run".equals(kind) && runId == null && needle == null)) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> run : catalog.runs) {
                if (status != null && !status.equalsIgnoreCase(str(run.get("outcome")))) continue;
                if (needle != null && !contains(run, needle)) continue;
                items.add(runItem(project, run));
            }
            return new Loaded(items, catalog.where, catalog.capped);
        }
        List<String> runIds = new ArrayList<>();
        if (runId != null) runIds.add(runId);
        else {
            for (Map<String, Object> run : catalog.runs) {
                String listed = str(run.get("runId"));
                if (listed != null) runIds.add(listed);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        boolean capped = catalog.capped;
        String where = catalog.where;
        for (String current : runIds) {
            Resolved resolved = resolve(project, current);
            if (resolved.where != null) where = resolved.where;
            if ("get".equals(op) && "run".equals(kind)) {
                items.add(runItem(project, catalogRow(catalog, current)));
                continue;
            }
            if ("props".equals(op) || "property".equals(kind)) {
                items.addAll(properties(project, current, resolved));
                continue;
            }
            if ("log".equals(kind)) continue;
            List<ScenarioRef> scenarios = scenarios(project, current, resolved);
            for (ScenarioRef scenario : scenarios) {
                if (scenarioId != null && !scenarioId.equals(scenario.id)) continue;
                if ("search".equals(op)) {
                    Map<String, Object> scenarioMatch = scenarioItem(current, scenario);
                    if (needle != null && contains(scenarioMatch, needle)) items.add(scenarioMatch);
                }
                if ("get".equals(op) && "scenario".equals(kind)) {
                    if (id != null && !id.equals(scenario.id) && !id.equals(scenario.name)) continue;
                    items.add(scenarioItem(current, scenario));
                    continue;
                }
                if ("scenario".equals(kind)) {
                    Map<String, Object> item = scenarioItem(current, scenario);
                    if (status != null && !status.equalsIgnoreCase(str(item.get("outcome")))) continue;
                    if (needle != null && !contains(item, needle)) continue;
                    items.add(item);
                    continue;
                }
                if ("artifact".equals(kind) || ("get".equals(op) && "artifact".equals(kind))) {
                    for (Map<String, Object> artifact : artifacts(project, current, scenario)) {
                        if (id != null && !id.equals(str(artifact.get("id")))) continue;
                        if (needle != null && !contains(artifact, needle)) continue;
                        items.add(artifact);
                    }
                    continue;
                }
                Scan scan = events(project, current, scenario);
                if (scan.capped) capped = true;
                boolean sawError = false;
                for (Map<String, Object> event : scan.events) {
                    String eventKind = str(event.get("kind"));
                    if ("get".equals(op)) {
                        if ("step".equals(kind) && !"step".equals(eventKind)) continue;
                        if ("event".equals(kind) && id == null) {
                            // A get without an id is the compact page of events.
                        } else if ("event".equals(kind) && id != null
                                && !id.equals(str(event.get("seq"))) && !id.equals(str(event.get("id")))) {
                            continue;
                        }
                        if ("error".equals(kind) && !isErrorRow(event)) continue;
                        if ("step".equals(kind) && id != null
                                && !id.equals(str(event.get("seq"))) && !id.equals(str(event.get("id")))) continue;
                        if ("error".equals(kind) && id != null
                                && !id.equals(str(event.get("seq"))) && !id.equals(str(event.get("id")))) continue;
                    } else if ("search".equals(op)) {
                        if (kind != null && !"event".equals(kind) && !kind.equals(eventKind) && !("error".equals(kind) && isErrorRow(event))) {
                            continue;
                        }
                        if (needle != null && !contains(event, needle)) continue;
                    } else {
                        if ("step".equals(kind) && !"step".equals(eventKind)) continue;
                        if ("error".equals(kind) && !isErrorRow(event)) continue;
                        if (kind != null && !"event".equals(kind) && !"step".equals(kind) && !"error".equals(kind)) continue;
                    }
                    if (status != null && !status.equalsIgnoreCase(str(event.get("status")))
                            && !status.equalsIgnoreCase(str(event.get("outcome")))) continue;
                    if (needle != null && !"search".equals(op) && !contains(event, needle)) continue;
                    if ("event".equals(kind) || "step".equals(kind) || "error".equals(kind) || "search".equals(op)
                            || ("get".equals(op) && ("event".equals(kind) || "step".equals(kind) || "error".equals(kind)))) {
                        items.add(event);
                        if (isErrorRow(event)) sawError = true;
                    }
                }
                if (("error".equals(kind) || "errors".equals(op) || "search".equals(op)) && !sawError) {
                    Map<String, Object> summaryFailure = summaryError(current, scenario);
                    if (summaryFailure != null && (needle == null || contains(summaryFailure, needle))) {
                        items.add(summaryFailure);
                    }
                }
            }
            if ("error".equals(kind) || "errors".equals(op)) {
                items.addAll(indexErrors(project, current, resolved, needle));
            }
        }
        if ("log".equals(kind) || "search".equals(op) || ("get".equals(op) && "log".equals(kind))) {
            Scan logs = logs(project, runId);
            if (logs.capped) capped = true;
            for (Map<String, Object> line : logs.events) {
                if (needle != null && !contains(line, needle)) continue;
                if (id != null && !id.equals(str(line.get("seq")))) continue;
                items.add(line);
            }
            if (where == null) where = ".pickleball/agent-log";
        }
        if ("search".equals(op)) {
            for (Map<String, Object> run : catalog.runs) {
                Map<String, Object> item = runItem(project, run);
                if (contains(item, needle)) items.add(item);
            }
        }
        return new Loaded(items, where, capped);
    }

    private static boolean isErrorRow(Map<String, Object> event) {
        if ("error".equals(str(event.get("kind")))) return true;
        if (event.get("message") != null || event.get("stackHead") != null) return true;
        String status = str(event.get("status"));
        return status != null && ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status));
    }

    private static Map<String, Object> catalogRow(Catalog catalog, String runId) {
        for (Map<String, Object> run : catalog.runs) {
            if (runId.equals(str(run.get("runId")))) return run;
        }
        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("runId", runId);
        return blank;
    }

    private record Catalog(List<Map<String, Object>> runs, String where, boolean capped) {
    }

    private static Catalog readCatalog(Path project) throws IOException {
        Path file = project.resolve("reports").resolve("diagnostic-runs").resolve("run-catalog.json");
        String where = relative(project, file);
        if (Files.isRegularFile(file)) {
            Object parsed = readJson(file);
            Map<String, Object> root = JsonLite.object(parsed);
            List<Object> rows = List.of();
            if (root != null && root.get("runs") instanceof List<?> list) {
                rows = cast(list);
            } else if (parsed instanceof List<?> list) {
                rows = cast(list);
            }
            List<Map<String, Object>> runs = new ArrayList<>();
            for (Object row : rows) {
                Map<String, Object> map = JsonLite.object(row);
                if (map == null) continue;
                if (str(map.get("runId")) == null && str(map.get("id")) != null) map.put("runId", str(map.get("id")));
                if (!safeId(str(map.get("runId")))) continue;
                runs.add(map);
            }
            return new Catalog(runs, where, false);
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        collectRunDirs(project.resolve("reports").resolve("diagnostic-runs"), seen);
        Path runsRoot = project.resolve(".pickleball").resolve("runs");
        if (Files.isDirectory(runsRoot)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(runsRoot)) {
                for (Path child : stream) {
                    if (!Files.isDirectory(child)) continue;
                    String name = child.getFileName().toString();
                    if (!safeId(name)) continue;
                    if (resolve(project, name).index != null) seen.add(name);
                }
            }
        }
        for (String name : seen) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runId", name);
            runs.add(row);
        }
        String discovered = Files.isDirectory(project.resolve("reports").resolve("diagnostic-runs"))
                ? "reports/diagnostic-runs"
                : ".pickleball/runs";
        return new Catalog(runs, discovered, false);
    }

    private static void collectRunDirs(Path root, Set<String> seen) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            List<Path> children = new ArrayList<>();
            for (Path child : stream) children.add(child);
            children.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path child : children) {
                if (!Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (!safeId(name)) continue;
                if (Files.isRegularFile(child.resolve("run-index.json"))) seen.add(name);
            }
        }
    }

    private record Resolved(Path index, Path runRoot, String where) {
    }

    static Resolved resolve(Path project, String runId) throws IOException {
        List<Path> candidates = new ArrayList<>();
        Path runDir = project.resolve(".pickleball").resolve("runs").resolve(runId);
        candidates.add(runDir.resolve("diagnostic").resolve(runId).resolve("run-index.json"));
        candidates.add(runDir.resolve("diagnostic").resolve("run-index.json"));
        Path shared = project.resolve("reports").resolve("diagnostic-runs").resolve(runId).resolve("run-index.json");
        candidates.add(shared);
        Path record = runDir.resolve("record.json");
        if (Files.isRegularFile(record)) {
            try {
                Map<String, Object> body = JsonLite.object(readJson(record));
                String dataPath = body == null ? null : str(body.get("dataPath"));
                Path data = flexible(dataPath);
                if (data != null) {
                    candidates.add(data.resolve("diagnostic").resolve(runId).resolve("run-index.json"));
                    candidates.add(data.resolve("diagnostic").resolve("run-index.json"));
                }
            } catch (RuntimeException ignored) {
                // An unreadable record is not an index.
            }
        }
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return new Resolved(candidate, candidate.getParent(), relative(project, candidate));
            }
        }
        Path walked = walkIndex(runDir, runId);
        if (walked == null) walked = walkIndex(shared.getParent(), runId);
        if (walked != null) return new Resolved(walked, walked.getParent(), relative(project, walked));
        Path sharedRoot = shared.getParent();
        if (Files.isDirectory(sharedRoot)) return new Resolved(null, sharedRoot, relative(project, sharedRoot));
        return new Resolved(null, null, null);
    }

    private static Path walkIndex(Path root, String runId) throws IOException {
        if (root == null || !Files.isDirectory(root)) return null;
        try (var walk = Files.walk(root, 5)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                if (path.getFileName() == null || !"run-index.json".equals(path.getFileName().toString())) continue;
                if (Files.size(path) > FILE_BYTES) continue;
                try {
                    Map<String, Object> body = JsonLite.object(readJson(path));
                    if (body != null && runId.equals(str(body.get("runId")))) return path;
                } catch (RuntimeException ignored) {
                    Path parent = path.getParent();
                    if (parent != null && parent.getFileName() != null && runId.equals(parent.getFileName().toString())) {
                        return path;
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Object> runItem(Path project, Map<String, Object> row) throws IOException {
        String runId = str(row.get("runId"));
        Resolved resolved = runId == null ? new Resolved(null, null, null) : resolve(project, runId);
        Map<String, Object> index = resolved.index == null ? Map.of() : objectOrEmpty(resolved.index);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "run");
        item.put("runId", runId);
        item.put("outcome", firstNonBlank(str(index.get("outcome")), str(row.get("outcome"))));
        item.put("completion", firstNonBlank(str(index.get("completion")), str(row.get("completion"))));
        item.put("purpose", purpose(row, index));
        Object counts = index.get("counts");
        if (counts instanceof Map<?, ?> map && map.get("total") instanceof Number total) {
            item.put("scenarioCount", total.intValue());
        }
        item.put("failed", failed(item.get("outcome"), index.get("counts"), row.get("counts")));
        item.put("where", resolved.where == null ? null : resolved.where);
        return item;
    }

    private static String purpose(Map<String, Object> row, Map<String, Object> index) {
        String direct = firstNonBlank(str(row.get("purpose")), str(row.get("runPurpose")), str(index.get("purpose")));
        if (direct != null) return clip(direct);
        Object lineage = row.get("lineage");
        if (lineage instanceof Map<?, ?> map) {
            Object value = map.get("runPurpose");
            if (value == null) value = map.get("purpose");
            if (value != null) return clip(String.valueOf(value));
        }
        return null;
    }

    private static boolean failed(Object outcome, Object counts, Object catalogCounts) {
        if (outcome != null) {
            String text = String.valueOf(outcome).toUpperCase(Locale.ROOT);
            if ("FAILED".equals(text) || "ERROR".equals(text)) return true;
        }
        return count(counts, "failed") + count(counts, "errors") + count(counts, "error")
                + count(catalogCounts, "failed") + count(catalogCounts, "errors") > 0;
    }

    private static int count(Object counts, String key) {
        if (!(counts instanceof Map<?, ?> map)) return 0;
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private record ScenarioRef(String id, String name, String outcome, Path summary, Path dir, String where) {
    }

    private static List<ScenarioRef> scenarios(Path project, String runId, Resolved resolved) throws IOException {
        List<ScenarioRef> found = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        Map<String, Object> index = resolved.index == null ? Map.of() : objectOrEmpty(resolved.index);
        Object listed = index.get("scenarios");
        if (listed instanceof List<?> rows) {
            int sequence = 0;
            for (Object row : rows) {
                sequence++;
                Map<String, Object> map = JsonLite.object(row);
                if (map == null) continue;
                String id = firstNonBlank(str(map.get("scenarioId")), str(map.get("id")), str(map.get("executionId")));
                String name = firstNonBlank(str(map.get("name")), str(map.get("scenarioName")), str(map.get("scenario")));
                if (id == null) id = name == null ? "scenario-" + sequence : slug(name, sequence);
                if (!safeId(id)) id = "scenario-" + sequence;
                if (!seen.add(id)) continue;
                Path dir = scenarioDir(project, runId, resolved, id);
                Path summary = dir == null ? null : dir.resolve("summary.json");
                String where = summary != null && Files.isRegularFile(summary)
                        ? relative(project, summary)
                        : resolved.where;
                found.add(new ScenarioRef(id, name, str(map.get("outcome")), summary, dir, where));
            }
        }
        for (Path root : scenarioRoots(project, runId, resolved)) {
            Path parent = root.resolve("scenarios");
            if (!Files.isDirectory(parent)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
                List<Path> children = new ArrayList<>();
                for (Path child : stream) children.add(child);
                children.sort(Comparator.comparing(path -> path.getFileName().toString()));
                for (Path child : children) {
                    if (!Files.isDirectory(child)) continue;
                    String id = child.getFileName().toString();
                    if (!safeId(id) || !seen.add(id)) continue;
                    Path summary = child.resolve("summary.json");
                    String name = null;
                    String outcome = null;
                    if (Files.isRegularFile(summary)) {
                        Map<String, Object> body = objectOrEmpty(summary);
                        name = firstNonBlank(str(body.get("name")), str(body.get("scenarioName")));
                        outcome = str(body.get("outcome"));
                    }
                    String where = Files.isRegularFile(summary) ? relative(project, summary) : relative(project, child);
                    found.add(new ScenarioRef(id, name, outcome, summary, child, where));
                }
            }
        }
        return found;
    }

    private static Path scenarioDir(Path project, String runId, Resolved resolved, String id) {
        for (Path root : scenarioRoots(project, runId, resolved)) {
            Path dir = root.resolve("scenarios").resolve(id);
            if (Files.isDirectory(dir)) return dir;
        }
        return null;
    }

    private static List<Path> scenarioRoots(Path project, String runId, Resolved resolved) {
        List<Path> roots = new ArrayList<>();
        if (resolved.runRoot != null) roots.add(resolved.runRoot);
        roots.add(project.resolve("reports").resolve("diagnostic-runs").resolve(runId));
        Path runDir = project.resolve(".pickleball").resolve("runs").resolve(runId);
        roots.add(runDir.resolve("diagnostic").resolve(runId));
        roots.add(runDir.resolve("diagnostic"));
        return roots;
    }

    private static Map<String, Object> scenarioItem(String runId, ScenarioRef scenario) throws IOException {
        Map<String, Object> summary = scenario.summary != null && Files.isRegularFile(scenario.summary)
                ? objectOrEmpty(scenario.summary)
                : Map.of();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "scenario");
        item.put("id", scenario.id);
        item.put("runId", runId);
        item.put("scenarioId", scenario.id);
        item.put("name", firstNonBlank(str(summary.get("name")), scenario.name));
        item.put("outcome", firstNonBlank(str(summary.get("outcome")), scenario.outcome));
        item.put("where", scenario.where);
        return item;
    }

    private record Scan(List<Map<String, Object>> events, boolean capped) {
    }

    private static Scan events(Path project, String runId, ScenarioRef scenario) throws IOException {
        if (scenario.dir == null) return new Scan(List.of(), false);
        Path file = scenario.dir.resolve("events.jsonl");
        if (!Files.isRegularFile(file)) return new Scan(List.of(), false);
        List<Map<String, Object>> events = new ArrayList<>();
        boolean capped = false;
        int lineNo = 0;
        String where = relative(project, file);
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo > SCAN_LINES) {
                    capped = true;
                    break;
                }
                if (line.isBlank()) continue;
                Map<String, Object> body;
                try {
                    body = JsonLite.object(JsonLite.parse(line));
                } catch (RuntimeException ignored) {
                    continue;
                }
                if (body == null) continue;
                events.add(eventItem(runId, scenario, body, lineNo, where));
            }
        }
        return new Scan(events, capped);
    }

    private static Map<String, Object> eventItem(
            String runId,
            ScenarioRef scenario,
            Map<String, Object> body,
            int lineNo,
            String where
    ) {
        String type = firstNonBlank(str(body.get("type")), str(body.get("eventType")));
        String text = firstNonBlank(
                str(body.get("text")),
                str(body.get("stepText")),
                str(body.get("message")),
                str(body.get("lastStepText"))
        );
        String status = firstNonBlank(str(body.get("status")), str(body.get("outcome")), str(body.get("level")));
        long seq = longValue(body.get("eventSeq"), longValue(body.get("seq"), lineNo));
        boolean step = "step".equalsIgnoreCase(type) || (type == null && str(body.get("stepText")) != null);
        boolean error = isError(type, status, body);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", step ? "step" : error ? "error" : "event");
        item.put("id", Long.toString(seq));
        item.put("runId", runId);
        item.put("scenarioId", scenario.id);
        item.put("seq", seq);
        item.put("type", type);
        item.put("status", status);
        item.put("text", clip(text));
        if (error) {
            item.put("message", clip(firstNonBlank(str(body.get("message")), text, str(body.get("error")))));
            item.put("stackHead", stackHead(body));
        }
        item.put("where", where);
        return item;
    }

    private static boolean isError(String type, String status, Map<String, Object> body) {
        String upperType = type == null ? "" : type.toUpperCase(Locale.ROOT);
        String upperStatus = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if ("FAILED".equals(upperStatus) || "ERROR".equals(upperStatus) || "SEVERE".equals(upperStatus)) return true;
        if (upperType.contains("ERROR") || upperType.contains("EXCEPTION") || "FAIL".equals(upperType)) return true;
        return body.containsKey("exception") || body.containsKey("stack") || body.containsKey("stackTrace");
    }

    private static String stackHead(Object body) {
        if (!(body instanceof Map<?, ?> map)) return null;
        Object stack = map.get("stack");
        if (stack == null) stack = map.get("stackTrace");
        if (stack == null && map.get("exception") instanceof Map<?, ?> exception) {
            stack = exception.get("stackTrace");
            if (stack == null) stack = exception.get("stack");
            if (stack == null) stack = exception.get("message");
        }
        if (stack instanceof List<?> lines && !lines.isEmpty()) stack = lines.getFirst();
        if (stack == null) return null;
        String text = String.valueOf(stack);
        int newline = text.indexOf('\n');
        if (newline >= 0) text = text.substring(0, newline);
        text = text.strip();
        if (text.isEmpty()) return null;
        return clip(text);
    }

    private static Map<String, Object> summaryError(String runId, ScenarioRef scenario) throws IOException {
        if (scenario.summary == null || !Files.isRegularFile(scenario.summary)) return null;
        Map<String, Object> summary = objectOrEmpty(scenario.summary);
        Object failure = summary.get("failure");
        if (failure == null) failure = summary.get("error");
        String message = null;
        String stack = null;
        if (failure instanceof Map<?, ?> map) {
            message = firstNonBlank(str(map.get("message")), str(map.get("errorMessage")));
            stack = stackHead(map);
        } else if (failure instanceof String text) {
            message = text;
        }
        message = firstNonBlank(message, str(summary.get("failureSummary")), str(summary.get("errorMessage")));
        if (message == null && !failed(summary.get("outcome"), null, null)) return null;
        if (message == null) message = str(summary.get("lastStepText"));
        if (message == null) return null;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "error");
        item.put("id", scenario.id);
        item.put("runId", runId);
        item.put("scenarioId", scenario.id);
        item.put("message", clip(message));
        item.put("stackHead", stack);
        item.put("where", scenario.where);
        return item;
    }

    private static List<Map<String, Object>> indexErrors(Path project, String runId, Resolved resolved, String needle) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        if (resolved.index == null) return items;
        Map<String, Object> index = objectOrEmpty(resolved.index);
        String summary = str(index.get("failureSummary"));
        if (summary != null && (needle == null || summary.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT)))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "error");
            item.put("id", runId);
            item.put("runId", runId);
            item.put("message", clip(summary));
            item.put("stackHead", null);
            item.put("where", resolved.where);
            items.add(item);
        }
        Path clusters = resolved.index.getParent() == null ? null : resolved.index.getParent().resolve("clusters.json");
        if (clusters != null && Files.isRegularFile(clusters)) {
            Map<String, Object> body = objectOrEmpty(clusters);
            Object rows = body.get("clusters");
            if (!(rows instanceof List<?> list)) rows = body.get("groups");
            if (rows instanceof List<?> list) {
                int sequence = 0;
                for (Object row : list) {
                    sequence++;
                    Map<String, Object> map = JsonLite.object(row);
                    if (map == null) continue;
                    String message = firstNonBlank(str(map.get("message")), str(map.get("title")), str(map.get("representative")));
                    if (message == null) continue;
                    if (needle != null && !message.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("kind", "error");
                    item.put("id", firstNonBlank(str(map.get("id")), "cluster-" + sequence));
                    item.put("runId", runId);
                    item.put("message", clip(message));
                    item.put("stackHead", stackHead(map));
                    item.put("where", relative(project, clusters));
                    items.add(item);
                }
            }
        }
        return items;
    }

    private static List<Map<String, Object>> properties(Path project, String runId, Resolved resolved) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Object> index = resolved.index == null ? Map.of() : objectOrEmpty(resolved.index);
        String profile = firstNonBlank(str(index.get("runProfile")), str(index.get("directRunProfile")));
        if (profile != null) addProfile(items, runId, profile, resolved.where);
        addScalars(items, runId, index.get("environment"), resolved.where);
        addScalars(items, runId, index.get("properties"), resolved.where);
        if (resolved.runRoot != null) {
            addFileScalars(items, runId, resolved.runRoot.resolve("configuration.json"), project);
            addFileScalars(items, runId, resolved.runRoot.resolve("environment.json"), project);
        }
        Path shared = project.resolve("reports").resolve("diagnostic-runs").resolve(runId);
        addFileScalars(items, runId, shared.resolve("configuration.json"), project);
        addFileScalars(items, runId, shared.resolve("environment.json"), project);
        return items;
    }

    private static void addProfile(List<Map<String, Object>> items, String runId, String profile, String where) {
        for (String part : profile.split(", ")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.isEmpty()) continue;
            items.add(property(runId, key, value, where));
        }
    }

    private static void addScalars(List<Map<String, Object>> items, String runId, Object source, String where) {
        if (!(source instanceof Map<?, ?> map)) return;
        int added = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (added >= 40) return;
            if (!(entry.getKey() instanceof String key)) continue;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> || value instanceof List<?>) continue;
            items.add(property(runId, key, value == null ? "" : String.valueOf(value), where));
            added++;
        }
    }

    private static void addFileScalars(List<Map<String, Object>> items, String runId, Path file, Path project) throws IOException {
        if (file == null || !Files.isRegularFile(file) || Files.size(file) > FILE_BYTES) return;
        Map<String, Object> body = objectOrEmpty(file);
        addScalars(items, runId, body, relative(project, file));
    }

    private static Map<String, Object> property(String runId, String key, String value, String where) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "property");
        item.put("id", key);
        item.put("runId", runId);
        item.put("key", key);
        item.put("value", clip(value));
        item.put("where", where);
        return item;
    }

    private static List<Map<String, Object>> artifacts(Path project, String runId, ScenarioRef scenario) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        if (scenario.dir == null || !Files.isDirectory(scenario.dir)) return items;
        Path shots = scenario.dir.resolve("screenshots");
        if (Files.isDirectory(shots)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(shots)) {
                List<Path> files = new ArrayList<>();
                for (Path file : stream) files.add(file);
                files.sort(Comparator.comparing(path -> path.getFileName().toString()));
                int kept = 0;
                for (Path file : files) {
                    if (!Files.isRegularFile(file)) continue;
                    if (kept++ >= 100) break;
                    items.add(artifact(runId, scenario.id, "screenshot", file.getFileName().toString(), relative(project, file)));
                }
            }
        }
        for (String name : List.of("trace.jsonl.gz", "trace.jsonl")) {
            Path trace = scenario.dir.resolve(name);
            if (Files.isRegularFile(trace)) {
                items.add(artifact(runId, scenario.id, "trace", name, relative(project, trace.getParent())));
            }
        }
        return items;
    }

    private static Map<String, Object> artifact(String runId, String scenarioId, String artifactKind, String id, String where) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "artifact");
        item.put("id", id);
        item.put("runId", runId);
        item.put("scenarioId", scenarioId);
        item.put("artifact", artifactKind);
        item.put("where", where);
        return item;
    }

    private static Scan logs(Path project, String runId) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean capped = false;
        Path file = project.resolve(".pickleball").resolve("agent-log");
        if (Files.isRegularFile(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            capped = lines.size() > SCAN_LINES;
            int start = capped ? lines.size() - SCAN_LINES : 0;
            for (int index = start; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", 6);
                String text = parts.length >= 5 ? parts[4] : line;
                String loggedRun = parts.length >= 3 ? parts[2] : null;
                if (runId != null && loggedRun != null && !"-".equals(loggedRun) && !runId.equals(loggedRun)) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", "log");
                item.put("id", Integer.toString(index + 1));
                item.put("seq", index + 1);
                item.put("source", "agent-log");
                item.put("runId", "-".equals(loggedRun) ? null : loggedRun);
                item.put("text", clip(text));
                item.put("where", ".pickleball/agent-log");
                items.add(item);
            }
        }
        capped = appendWorkerLogs(project, items) || capped;
        return new Scan(items, capped);
    }

    /** Last lines of worker stdout/stderr so agents can query them with the short log. */
    private static boolean appendWorkerLogs(Path project, List<Map<String, Object>> items) throws IOException {
        Path root = project.resolve(".pickleball");
        if (!Files.isDirectory(root)) return false;
        boolean capped = false;
        try (var walk = Files.walk(root, 6)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName() == null ? "" : path.getFileName().toString();
                        return name.startsWith("worker-") && name.endsWith(".log");
                    })
                    .sorted()
                    .toList();
            int seq = items.size();
            for (Path log : files) {
                List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
                int start = 0;
                if (lines.size() > 40) {
                    start = lines.size() - 40;
                    capped = true;
                }
                for (int index = start; index < lines.size(); index++) {
                    String line = lines.get(index);
                    if (line.isBlank()) continue;
                    seq++;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("kind", "log");
                    item.put("id", "worker-" + seq);
                    item.put("seq", seq);
                    item.put("source", "worker");
                    item.put("text", clip(line));
                    item.put("where", relative(project, log));
                    items.add(item);
                }
            }
        }
        return capped;
    }

    private static Map<String, Object> project(Map<String, Object> item, String fields) {
        return projectFields(item, fields);
    }

    private static Map<String, Object> projectFields(Map<String, Object> source, String fields) {
        Map<String, Object> item = new LinkedHashMap<>(source);
        if (fields == null || fields.isBlank()) return item;
        Set<String> keep = new TreeSet<>();
        for (String part : fields.split(",")) {
            String name = part.trim();
            if (!name.isEmpty()) keep.add(name);
        }
        Map<String, Object> projected = new LinkedHashMap<>();
        for (String key : keep) {
            if (item.containsKey(key)) projected.put(key, item.get(key));
        }
        return projected;
    }

    private static boolean contains(Map<String, Object> item, String needle) {
        if (needle == null || needle.isBlank()) return true;
        String lower = needle.toLowerCase(Locale.ROOT);
        for (Object value : item.values()) {
            if (value == null) continue;
            if (String.valueOf(value).toLowerCase(Locale.ROOT).contains(lower)) return true;
        }
        return false;
    }

    private static Map<String, Object> objectOrEmpty(Path file) throws IOException {
        try {
            Map<String, Object> map = JsonLite.object(readJson(file));
            return map == null ? Map.of() : map;
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }

    private static Object readJson(Path file) throws IOException {
        long size = Files.size(file);
        String text;
        if (size > FILE_BYTES) {
            byte[] bytes = Files.readAllBytes(file);
            text = new String(bytes, 0, FILE_BYTES, StandardCharsets.UTF_8);
        } else {
            text = Files.readString(file, StandardCharsets.UTF_8);
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return JsonLite.parse(text);
    }

    private static String relative(Path project, Path file) {
        try {
            return project.toAbsolutePath().normalize()
                    .relativize(file.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
        } catch (RuntimeException failure) {
            return file.getFileName() == null ? "" : file.getFileName().toString();
        }
    }

    private static Path flexible(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String text = raw.trim();
        if (text.startsWith("\\\\?\\")) text = text.substring(4);
        return Path.of(text.replace('\\', '/'));
    }

    private static boolean safeId(String id) {
        if (id == null || id.isBlank()) return false;
        return !id.contains("..") && !id.contains("/") && !id.contains("\\") && !id.contains("\0");
    }

    private static String slug(String name, int sequence) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isBlank() || cleaned.contains("..")) return "scenario-" + sequence;
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static String clip(String value) {
        if (value == null) return null;
        String collapsed = value.replace('\n', ' ').replace('\r', ' ').strip();
        if (collapsed.length() <= TEXT_LIMIT) return collapsed;
        return collapsed.substring(0, TEXT_LIMIT) + "…";
    }

    private static String oneLine(String value) {
        if (value == null) return "";
        return clip(value);
    }

    private static int limit(Map<String, ?> request) {
        Object value = request == null ? null : request.get("limit");
        if (value == null) return DEFAULT_LIMIT;
        int parsed;
        if (value instanceof Number number) parsed = number.intValue();
        else {
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) return DEFAULT_LIMIT;
            try {
                parsed = Integer.parseInt(text);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(text);
            }
        }
        if (parsed < 1) throw new IllegalArgumentException(String.valueOf(parsed));
        int cap = unpaged(request) ? 100_000 : MAX_LIMIT;
        return Math.min(parsed, cap);
    }

    private static boolean unpaged(Map<String, ?> request) {
        return request != null && Boolean.TRUE.equals(request.get("unpaged"));
    }

    private static int cursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int value = Integer.parseInt(cursor.trim());
            if (value < 0) throw new IllegalArgumentException(cursor);
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(cursor);
        }
    }

    private static String text(Map<String, ?> request, String key) {
        if (request == null || key == null) return null;
        Object value = request.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equals(text) ? null : text;
    }

    private static String firstText(Map<String, ?> request, String... keys) {
        if (keys == null) return null;
        for (String key : keys) {
            String value = text(request, key);
            if (value != null) return value;
        }
        return null;
    }

    private static String str(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static long longValue(Object value, long fallback) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<Object> cast(List<?> list) {
        return new ArrayList<>(list);
    }

    private static int utf8(Map<String, Object> body) {
        return JsonLite.write(body).getBytes(StandardCharsets.UTF_8).length;
    }

    private static Outcome error(int exit, String error, String fix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("v", 1);
        body.put("error", error);
        body.put("fix", fix);
        return new Outcome(body, JsonLite.write(body), exit);
    }
}
