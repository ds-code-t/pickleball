package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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
    public static final String TRUNCATION_MARK = "...+";

    private static final int TEXT_LIMIT = 160;
    private static final int SCAN_LINES = 20_000;
    private static final int FILE_BYTES = 4_000_000;
    private static final Set<String> OPS = Set.of("runs", "get", "query", "search", "props", "errors", "tail");
    private static final Set<String> KINDS = Set.of(
            "run", "scenario", "step", "event", "property", "artifact", "error", "log"
    );
    private static final Set<String> FIELDS = Set.of(
            "kind", "id", "seq", "runId", "scenarioId", "name", "outcome", "completion",
            "purpose", "scenarioCount", "failed", "where", "type", "status", "text",
            "message", "stackHead", "key", "value", "source", "artifact"
    );

    private static final ThreadLocal<List<String>> DECODE_WARNINGS = ThreadLocal.withInitial(ArrayList::new);

    private static final ThreadLocal<Boolean> INDEX_BUILD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    /** True while {@link DiagnosticSqliteIndex#rebuild} is reading these files into the disposable index. */
    static boolean indexing() {
        return Boolean.TRUE.equals(INDEX_BUILD.get());
    }

    static boolean beginIndexBuild() {
        boolean previous = indexing();
        INDEX_BUILD.set(Boolean.TRUE);
        return previous;
    }

    static void endIndexBuild(boolean previous) {
        INDEX_BUILD.set(previous);
    }

    private DiagnosticQuery() {
    }

    public record Outcome(Map<String, Object> body, String json, int exit) {
    }

    public static Outcome query(Path project, Map<String, ?> request) {
        return query(project, request, DEFAULT_BUDGET_BYTES);
    }

    static Outcome query(Path project, Map<String, ?> request, int budgetBytes) {
        DECODE_WARNINGS.get().clear();
        if (project == null) {
            return error(2, "missing project", "Pass the consumer project directory.");
        }
        String op = text(request, "op");
        if (op == null || !OPS.contains(op)) {
            return error(2, "missing op",
                    "Usage: query <runs|get|query|search|props|errors|tail> [--run-id=<id>] [--kind=<kind>] [--limit=<n>] [--cursor=<token>]");
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
        if (fields != null && !fields.isBlank()) {
            List<String> unknown = new ArrayList<>();
            for (String part : fields.split(",")) {
                String name = part.trim();
                if (!name.isEmpty() && !FIELDS.contains(name)) unknown.add(name);
            }
            if (!unknown.isEmpty()) {
                List<String> valid = new ArrayList<>(FIELDS);
                Collections.sort(valid);
                return error(2, "unknown field",
                        "Unknown field " + String.join(", ", unknown) + ". Valid fields: " + String.join(", ", valid) + ".");
            }
        }
        if (("get".equals(op) || "props".equals(op)) && runId == null) {
            return error(2, "missing runId", "Usage: query " + op + " --run-id=<id>");
        }
        if ("search".equals(op) && needle == null) {
            return error(2, "missing text", "Usage: query search --text=<words>");
        }
        if ("tail".equals(op) && runId == null) {
            return error(2, "missing runId", "Usage: query tail --run-id=<id>");
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
            DiagnosticSqliteIndex.refreshIfStale(root);
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
        if (capped) {
            body.put("scanCapped", true);
            body.put("fix", "Scan stopped after " + SCAN_LINES + " lines. Narrow it with --run-id=<id>.");
        }
        List<String> decoded = DECODE_WARNINGS.get();
        if (decoded != null && !decoded.isEmpty()) {
            body.put("decodeWarnings", List.copyOf(decoded));
        }
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
        if ("tail".equals(op)) {
            Loaded failures = load(project, "errors", runId, null, "error", null, null, null);
            if (!failures.items.isEmpty()) {
                List<Map<String, Object>> newest = new ArrayList<>(failures.items);
                Collections.reverse(newest);
                String hitWhere = null;
                for (Map<String, Object> item : newest) {
                    hitWhere = str(item.get("where"));
                    if (hitWhere != null) break;
                }
                return new Loaded(newest, hitWhere == null ? ".pickleball/runs/" + runId : hitWhere, failures.capped);
            }
            Scan tail = tailLines(project, runId);
            List<Map<String, Object>> newestFirst = new ArrayList<>(tail.events);
            Collections.reverse(newestFirst);
            return new Loaded(newestFirst, ".pickleball/runs/" + runId, tail.capped);
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
        if ("search".equals(op) && !items.isEmpty()) {
            for (Map<String, Object> item : items) {
                if (needle != null && !contains(item, needle)) continue;
                String hitWhere = str(item.get("where"));
                if (hitWhere != null) {
                    where = hitWhere;
                    break;
                }
            }
        }
        return new Loaded(items, where, capped);
    }

    private static boolean isErrorRow(Map<String, Object> event) {
        String type = str(event.get("type"));
        if (type != null && type.toUpperCase(Locale.ROOT).contains("SCENARIO_END")
                && event.get("message") == null && event.get("stackHead") == null) {
            return false;
        }
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
            if (indexing()) mergeOnDiskRuns(project, runs);
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

    /** A stale catalog must not hide a run directory that already has a run-index. Used only while rebuilding the disposable index. */
    private static void mergeOnDiskRuns(Path project, List<Map<String, Object>> runs) throws IOException {
        Set<String> listed = new TreeSet<>();
        for (Map<String, Object> run : runs) {
            String id = str(run.get("runId"));
            if (id != null) listed.add(id);
        }
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
            if (listed.contains(name)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runId", name);
            runs.add(row);
        }
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
        Path found = RunIndexFinder.find(project, runId);
        if (found != null) return new Resolved(found, found.getParent(), relative(project, found));
        Path sharedRoot = project.resolve("reports").resolve("diagnostic-runs").resolve(runId);
        if (Files.isDirectory(sharedRoot)) return new Resolved(null, sharedRoot, relative(project, sharedRoot));
        return new Resolved(null, null, null);
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
                String id = firstNonBlank(
                        str(map.get("scenarioId")),
                        str(map.get("id")),
                        str(map.get("executionId")),
                        str(map.get("scenarioExecutionId"))
                );
                String name = firstNonBlank(
                        str(map.get("name")),
                        str(map.get("scenarioName")),
                        str(map.get("scenario")),
                        identityName(map.get("identity"))
                );
                if (id == null) id = name == null ? "scenario-" + sequence : slug(name, sequence);
                if (!safeId(id)) id = "scenario-" + sequence;
                if (!seen.add(id)) continue;
                Path dir = scenarioDir(project, runId, resolved, id);
                if (dir == null) dir = summaryDirectory(project, runId, resolved, str(map.get("summary")));
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
                    String folderId = child.getFileName().toString();
                    if (!safeId(folderId)) continue;
                    Path summary = child.resolve("summary.json");
                    String name = null;
                    String outcome = null;
                    String summaryId = null;
                    String summaryExec = null;
                    if (Files.isRegularFile(summary)) {
                        Map<String, Object> body = objectOrEmpty(summary);
                        name = firstNonBlank(
                                str(body.get("name")),
                                str(body.get("scenarioName")),
                                identityName(body.get("identity"))
                        );
                        outcome = str(body.get("outcome"));
                        summaryId = str(body.get("scenarioId"));
                        summaryExec = firstNonBlank(str(body.get("scenarioExecutionId")), str(body.get("executionId")));
                    }
                    int slot = indexOfScenario(found, folderId, summaryId, summaryExec);
                    String where = Files.isRegularFile(summary) ? relative(project, summary) : relative(project, child);
                    if (slot >= 0) {
                        ScenarioRef current = found.get(slot);
                        found.set(slot, new ScenarioRef(
                                current.id,
                                firstNonBlank(current.name, name),
                                firstNonBlank(current.outcome, outcome),
                                summary != null && Files.isRegularFile(summary) ? summary : current.summary,
                                child,
                                where
                        ));
                        seen.add(folderId);
                        if (summaryId != null) seen.add(summaryId);
                        if (summaryExec != null) seen.add(summaryExec);
                        continue;
                    }
                    if (!seen.add(folderId)) continue;
                    found.add(new ScenarioRef(folderId, name, outcome, summary, child, where));
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
        List<String> lines = readLinesReplacing(file, project);
        for (String line : lines) {
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
        attachFailureStacks(events);
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
        if (emptyScenarioEnd(type, body)) return false;
        String upperType = type == null ? "" : type.toUpperCase(Locale.ROOT);
        String upperStatus = status == null ? "" : status.toUpperCase(Locale.ROOT);
        if ("FAILURE".equals(upperType)) return true;
        if ("FAILED".equals(upperStatus) || "ERROR".equals(upperStatus) || "SEVERE".equals(upperStatus)) return true;
        if (upperType.contains("ERROR") || upperType.contains("EXCEPTION") || "FAIL".equals(upperType)) return true;
        return body.containsKey("exception") || body.containsKey("stack") || body.containsKey("stackTrace")
                || body.containsKey("failures");
    }

    private static boolean emptyScenarioEnd(String type, Map<String, Object> body) {
        if (type == null || !type.toUpperCase(Locale.ROOT).contains("SCENARIO_END")) return false;
        return stackHead(body) == null
                && firstNonBlank(str(body.get("message")), str(body.get("error")), str(body.get("errorMessage"))) == null;
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
        if (stack == null && map.get("failures") instanceof List<?> failures && !failures.isEmpty()) {
            Object first = failures.getFirst();
            if (first instanceof Map<?, ?> failure) {
                stack = failure.get("stackTrace");
                if (stack == null) stack = failure.get("stack");
                if (stack == null) {
                    String klass = str(failure.get("class"));
                    String message = str(failure.get("message"));
                    if (klass != null && message != null) stack = klass + ": " + message;
                    else stack = firstNonBlank(message, klass);
                }
            }
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
            List<String> lines = readLinesReplacing(file, project);
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
                List<String> lines = readLinesReplacing(log, project);
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
        String text = readTextReplacing(file, null, size > FILE_BYTES);
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
        String collapsed = stripInvisibles(value).replace('\n', ' ').replace('\r', ' ').strip();
        if (collapsed.length() <= TEXT_LIMIT) return collapsed;
        return collapsed.substring(0, TEXT_LIMIT) + "...";
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
        String text = stripInvisibles(String.valueOf(value)).trim();
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

    private static int indexOfScenario(List<ScenarioRef> found, String... ids) {
        if (found == null || ids == null) return -1;
        for (int index = 0; index < found.size(); index++) {
            ScenarioRef scenario = found.get(index);
            for (String id : ids) {
                if (id == null) continue;
                if (id.equals(scenario.id)) return index;
            }
        }
        return -1;
    }

    private static Path summaryDirectory(Path project, String runId, Resolved resolved, String summary) {
        if (summary == null || summary.isBlank()) return null;
        for (Path root : scenarioRoots(project, runId, resolved)) {
            Path file = root.resolve(summary);
            if (Files.isRegularFile(file) && file.getParent() != null) return file.getParent();
        }
        return null;
    }

    private static String identityName(Object identity) {
        Map<String, Object> map = JsonLite.object(identity);
        if (map == null) return null;
        return firstNonBlank(str(map.get("scenarioName")), str(map.get("name")));
    }

    private static void attachFailureStacks(List<Map<String, Object>> events) {
        String head = null;
        String message = null;
        for (Map<String, Object> event : events) {
            if (!"failure".equalsIgnoreCase(str(event.get("type")))) continue;
            if (event.get("stackHead") != null) head = str(event.get("stackHead"));
            if (event.get("message") != null) message = str(event.get("message"));
        }
        if (head == null && message == null) return;
        for (Map<String, Object> event : events) {
            if (!"step".equals(str(event.get("kind")))) continue;
            String status = str(event.get("status"));
            if (status == null || (!"FAILED".equalsIgnoreCase(status) && !"ERROR".equalsIgnoreCase(status))) continue;
            if (event.get("stackHead") == null && head != null) event.put("stackHead", head);
            if (event.get("message") == null && message != null) event.put("message", message);
        }
    }

    private static Scan tailLines(Path project, String runId) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean capped = false;
        Path agent = project.resolve(".pickleball").resolve("agent-log");
        if (Files.isRegularFile(agent)) {
            List<String> lines = readLinesReplacing(agent, project);
            int start = 0;
            if (lines.size() > SCAN_LINES) {
                start = lines.size() - SCAN_LINES;
                capped = true;
            }
            for (int index = start; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                String[] parts = line.split("\t", 6);
                String loggedRun = parts.length >= 3 ? parts[2] : null;
                if (runId != null && loggedRun != null && !"-".equals(loggedRun) && !runId.equals(loggedRun)) continue;
                items.add(logItem(index + 1, "agent-log", "-".equals(loggedRun) ? null : loggedRun,
                        parts.length >= 5 ? parts[4] : line, ".pickleball/agent-log"));
            }
        }
        List<Path> files = new ArrayList<>();
        Path runDir = project.resolve(".pickleball").resolve("runs").resolve(runId);
        if (Files.isDirectory(runDir)) {
            try (var walk = Files.walk(runDir, 6)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName() == null ? "" : path.getFileName().toString();
                            return name.endsWith(".log") || "session.log".equals(name);
                        })
                        .sorted()
                        .forEach(files::add);
            }
        }
        int seq = items.size();
        for (Path log : files) {
            List<String> lines = readLinesReplacing(log, project);
            int start = 0;
            if (lines.size() > 40) {
                start = lines.size() - 40;
                capped = true;
            }
            for (int index = start; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank()) continue;
                seq++;
                items.add(logItem(seq, "run-log", runId, line, relative(project, log)));
            }
        }
        return new Scan(items, capped);
    }

    private static Map<String, Object> logItem(int seq, String source, String runId, String text, String where) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", "log");
        item.put("id", Integer.toString(seq));
        item.put("seq", seq);
        item.put("source", source);
        item.put("runId", runId);
        item.put("text", clip(text));
        item.put("where", where);
        return item;
    }

    private static List<String> readLinesReplacing(Path file, Path project) {
        String text = readTextReplacing(file, project, false);
        if (text.isEmpty()) return List.of();
        return List.of(text.split("\\R", -1));
    }

    private static String readTextReplacing(Path file, Path project, boolean cap) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (cap && bytes.length > FILE_BYTES) {
                byte[] sliced = new byte[FILE_BYTES];
                System.arraycopy(bytes, 0, sliced, 0, FILE_BYTES);
                bytes = sliced;
            }
            try {
                CharsetDecoder strict = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return strict.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException failure) {
                noteDecode(project, file);
                CharsetDecoder replacing = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                return replacing.decode(ByteBuffer.wrap(bytes)).toString();
            }
        } catch (IOException failure) {
            noteDecode(project, file);
            return "";
        }
    }

    private static void noteDecode(Path project, Path file) {
        String name = project == null ? file.getFileName().toString() : relative(project, file);
        List<String> warnings = DECODE_WARNINGS.get();
        if (!warnings.contains(name)) warnings.add(name);
    }

    private static String stripInvisibles(String value) {
        if (value == null || value.isEmpty()) return value == null ? "" : value;
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); ) {
            int point = value.codePointAt(index);
            if (point == 0xFEFF || point == 0x200B || point == 0x200C || point == 0x200D
                    || point == 0x2060 || point == 0x180E) {
                index += Character.charCount(point);
                continue;
            }
            if (Character.isSpaceChar(point) && point != ' ' && point != '\n' && point != '\r' && point != '\t') {
                out.append(' ');
            } else {
                out.appendCodePoint(point);
            }
            index += Character.charCount(point);
        }
        return out.toString();
    }

    private static Outcome error(int exit, String error, String fix) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("v", 1);
        body.put("error", error);
        body.put("fix", fix);
        return new Outcome(body, JsonLite.write(body), exit);
    }
}
