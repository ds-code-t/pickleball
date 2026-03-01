// file: tools/dscode/common/reporting/logging/simplehtml/SimpleHtmlReportConverter.java
package tools.dscode.common.reporting.logging.simplehtml;

import io.cucumber.core.runner.GlobalState;
import tools.dscode.common.reporting.logging.Attachment;
import tools.dscode.common.reporting.logging.BaseConverter;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.reporting.logging.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Always generates a single, tabbed HTML report for the entire Cucumber run (parallel-safe):
 * - A MAIN tab with an overview spreadsheet of all scenarios (using RowData headers/values).
 * - One tab per scenario with full tree/log/attachments + scenario summary table.
 */
public final class SimpleHtmlReportConverter extends BaseConverter {

    private final Object lock = new Object();

    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();
    private final Path reportFile;

    // ---------------- Single-file (multi-scenario) aggregation ----------------
    private static final class SharedSingleFile {
        static final Object LOCK = new Object();
        static final AtomicInteger ACTIVE = new AtomicInteger(0);

        // Key: scenario rowKey (preferred), else rootId
        static final Map<String, ScopeState> ALL_SCOPES = new ConcurrentHashMap<>();

        static volatile Path REPORT_FILE = null;
    }

    // Guards to prevent giant/bloated emails
    private static final int MAX_TEXT_LEN = 20_000;
    private static final int MAX_INLINE_IMAGE_BYTES = 3_000_000; // ~3MB per image (before base64)
    private static final int MAX_LOG_LINES_PER_NODE = 5_000;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    public SimpleHtmlReportConverter(Path reportFile) {
        this.reportFile = reportFile;

        if (SharedSingleFile.REPORT_FILE == null) {
            SharedSingleFile.REPORT_FILE = resolveSingleFileOutput(reportFile);
        }
        SharedSingleFile.ACTIVE.incrementAndGet();
    }

    private static Path resolveSingleFileOutput(Path perScenarioReportFile) {
        String p = System.getProperty("pickleball.simplehtml.singleFile.path");
        if (p == null || p.isBlank()) p = System.getenv("PICKLEBALL_SIMPLEHTML_SINGLEFILE_PATH");
        if (p != null && !p.isBlank()) {
            try {
                return Path.of(p.trim());
            } catch (Throwable ignored) { }
        }

        Path dir = (perScenarioReportFile != null && perScenarioReportFile.getParent() != null)
                ? perScenarioReportFile.getParent()
                : Path.of(".");
        return dir.resolve("cucumber-report.html");
    }

    @Override
    protected void onClose() {
        synchronized (lock) {
            try {
                synchronized (SharedSingleFile.LOCK) {
                    for (ScopeState s : scopes.values()) {
                        String key = (s.rowKey != null && !s.rowKey.isBlank()) ? s.rowKey : s.rootId;
                        if (key == null) continue;
                        SharedSingleFile.ALL_SCOPES.put(key, deepCopyScopeState(s));
                    }
                }

            } catch (Throwable e) {
                System.err.println("[SimpleHtmlReportConverter] FAILED onClose: " + e);
            } finally {
                int remaining = SharedSingleFile.ACTIVE.decrementAndGet();
                if (remaining == 0) {
                    try {
                        Path outFile = SharedSingleFile.REPORT_FILE;
                        if (outFile == null) outFile = Path.of("cucumber-report.html");
                        writeCombinedReport(outFile);
                    } catch (Throwable ex) {
                        System.err.println("[SimpleHtmlReportConverter] FAILED writing combined report: " + ex);
                    }
                }
            }
        }
    }

    private static ScopeState deepCopyScopeState(ScopeState src) {
        ScopeState c = new ScopeState();
        c.rootId = src.rootId;
        c.rowKey = src.rowKey;
        for (Map.Entry<String, HtmlNode> e : src.nodes.entrySet()) {
            c.nodes.put(e.getKey(), e.getValue().deepCopy());
        }
        return c;
    }

    private void writeCombinedReport(Path outFile) throws IOException {
        Map<String, ScopeState> snapshot;
        synchronized (SharedSingleFile.LOCK) {
            snapshot = new LinkedHashMap<>(SharedSingleFile.ALL_SCOPES);
        }

        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());

        String html = renderHtmlDocument(snapshot, outFile);
        Files.writeString(outFile, html, StandardCharsets.UTF_8);

        for (ScopeState s : snapshot.values()) {
            if (s.rowKey != null) GlobalState.registerScenarioHtml(s.rowKey, outFile);
        }

        System.out.println("[SimpleHtmlReportConverter] wrote combined: " + outFile.toAbsolutePath());
    }

    @Override public void onStart(Entry scope, Entry entry) { emit(scope, entry, Phase.START); }
    @Override public void onTimestamp(Entry scope, Entry entry) { emit(scope, entry, Phase.TIMESTAMP); }
    @Override public void onStop(Entry scope, Entry entry) { emit(scope, entry, Phase.STOP); }

    private void emit(Entry scope, Entry entry, Phase phase) {
        ScopeState s = scopes.computeIfAbsent(scope.id, id -> initScope(scope));

        synchronized (lock) {
            HtmlNode node = ensureNode(s, scope, entry);
            applyMetaOncePerChange(s, entry, node);

            // Capture lifecycle/timing on the node itself for cleaner headers.
            if (phase == Phase.START) {
                node.startedAt = (entry.startedAt != null) ? entry.startedAt : Instant.now();
            } else if (phase == Phase.TIMESTAMP) {
                node.timestampedAt = (entry.timestampedAt != null) ? entry.timestampedAt : Instant.now();
            } else if (phase == Phase.STOP) {
                node.stoppedAt = (entry.stoppedAt != null) ? entry.stoppedAt : Instant.now();
                if (entry.status != null) node.status = entry.status;
            }

            if (entry.level != null) node.level = entry.level;

            // Keep logs (still useful for “what happened”), but don’t rely on them for header timing.
            if (phase == Phase.TIMESTAMP) {
                Status st = (entry.status != null) ? entry.status : Status.INFO;
                node.addLogLine(LogLine.of(tsOf(entry.timestampedAt), mapStatusCss(st), entry.text));
            }

            if (phase == Phase.START) {
                node.addLogLine(LogLine.of(tsOf(entry.startedAt), "muted", "START"));
            }

            if (phase == Phase.STOP) {
                Status st = (entry.status != null) ? entry.status : Status.UNKNOWN;
                node.addLogLine(LogLine.of(tsOf(entry.stoppedAt), mapStatusCss(st), "STOP " + labelForStatus(st)));
            }
        }
    }

    private static String tsOf(Instant i) {
        if (i == null) i = Instant.now();
        return TS_FMT.format(i);
    }

    private ScopeState initScope(Entry scope) {
        ScopeState s = new ScopeState();
        s.rootId = scope.id;

        try {
            s.rowKey = GlobalState.getCurrentScenarioState().id.toString();
        } catch (Throwable ignored) {
            s.rowKey = null;
        }

        HtmlNode root = new HtmlNode(scope.id, sanitizeNodeName(scope.text), null);
        // Root is a span-like container; try to carry start/stop too if present
        root.startedAt = scope.startedAt;
        root.stoppedAt = scope.stoppedAt;
        root.timestampedAt = scope.timestampedAt;
        root.status = scope.status;
        root.level = scope.level;

        s.nodes.put(scope.id, root);
        return s;
    }

    // lock must be held
    private HtmlNode ensureNode(ScopeState s, Entry scope, Entry entry) {
        HtmlNode existing = s.nodes.get(entry.id);
        if (existing != null) return existing;

        if (safeEquals(entry.id, scope.id)) return s.nodes.get(s.rootId);

        if (entry.parent != null && isInScope(scope, entry.parent)) {
            ensureNode(s, scope, entry.parent);
        }

        String parentId =
                (entry.parent != null && isInScope(scope, entry.parent))
                        ? entry.parent.id
                        : s.rootId;

        HtmlNode node = new HtmlNode(entry.id, sanitizeNodeName(entry.text), parentId);
        node.startedAt = entry.startedAt;
        node.stoppedAt = entry.stoppedAt;
        node.timestampedAt = entry.timestampedAt;
        node.status = entry.status;
        node.level = entry.level;

        s.nodes.put(entry.id, node);
        return node;
    }

    private static boolean isInScope(Entry scope, Entry entry) {
        for (Entry n = entry; n != null; n = n.parent) {
            if (safeEquals(n.id, scope.id)) return true;
        }
        return false;
    }

    private void applyMetaOncePerChange(ScopeState s, Entry e, HtmlNode node) {
        int tagsN = e.tags.size();
        int fieldsN = e.fields.size();
        int attachesN = e.attachments.size();

        Meta m = s.meta.computeIfAbsent(e.id, id -> new Meta());

        if (m.tagsN != tagsN) {
            node.tags = sanitizeTags(e.tags);
            m.tagsN = tagsN;
        }

        if (m.fieldsN != fieldsN) {
            node.fields = sanitizeFields(e.fields);
            m.fieldsN = fieldsN;
        }

        for (int i = m.attachesN; i < attachesN; i++) {
            Attachment a = e.attachments.get(i);

            String name = sanitizeAttachmentName(
                    (a.name() == null || a.name().isBlank()) ? "Attachment" : a.name()
            );
            String mime = (a.mime() == null) ? "" : a.mime();
            String data = a.path();

            if (mime.startsWith("image/")) {
                InlineImage img = inlineImage(name, mime, data);
                node.attachments.add(img);
            } else {
                node.attachments.add(InlineImage.textOnly(name, sanitizeLogText(String.valueOf(data))));
            }
        }
        m.attachesN = attachesN;
    }

    private InlineImage inlineImage(String name, String mime, String data) {
        try {
            if (mime.contains("base64")) {
                String raw = rawBase64(data);
                if (raw == null || raw.isBlank()) {
                    return InlineImage.textOnly(name, sanitizeLogText("<empty base64>"));
                }
                String mediaType = mime.substring(0, mime.indexOf(';') >= 0 ? mime.indexOf(';') : mime.length());
                String src = "data:" + mediaType + ";base64," + raw;
                return InlineImage.image(name, src);
            }

            String trimmed = (data == null) ? "" : data.trim();
            if (trimmed.startsWith("data:image")) {
                return InlineImage.image(name, trimmed);
            }

            if (trimmed.isBlank()) {
                return InlineImage.textOnly(name, sanitizeLogText("<empty path>"));
            }

            Path p = Path.of(trimmed);
            if (!Files.exists(p)) {
                Path maybeRel = (reportFile == null || reportFile.getParent() == null) ? p : reportFile.getParent().resolve(trimmed);
                if (Files.exists(maybeRel)) p = maybeRel;
            }

            if (!Files.exists(p)) {
                return InlineImage.textOnly(name, sanitizeLogText("Missing image file: " + trimmed));
            }

            long size = Files.size(p);
            if (size > MAX_INLINE_IMAGE_BYTES) {
                return InlineImage.textOnly(
                        name,
                        sanitizeLogText("Image too large to inline (" + size + " bytes). Not embedded.")
                );
            }

            byte[] bytes = Files.readAllBytes(p);
            String b64 = Base64.getEncoder().encodeToString(bytes);
            String src = "data:" + mime + ";base64," + b64;
            return InlineImage.image(name, src);

        } catch (Throwable ex) {
            return InlineImage.textOnly(name, sanitizeLogText("Failed to inline image: " + ex));
        }
    }

    private Status computeOverallStatusForScope(ScopeState s) {
        if (s == null) return Status.PASS;
        for (HtmlNode n : s.nodes.values()) {
            if (n.status == Status.FAIL) return Status.FAIL;
        }
        return Status.PASS;
    }

    private String renderHtmlDocument(Map<String, ScopeState> scopesToRender, Path outFile) {
        StringBuilder out = new StringBuilder(256_000);
        out.append("<!doctype html>\n");
        out.append("<html lang=\"en\">\n<head>\n");
        out.append("  <meta charset=\"utf-8\">\n");
        out.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("  <title>").append(escapeHtml(outFile.getFileName().toString())).append("</title>\n");
        out.append("  <style>\n").append(CSS).append("\n  </style>\n");
        out.append("</head>\n<body>\n");

        List<ScopeState> scopeStates = new ArrayList<>(scopesToRender.values());
        scopeStates.sort(Comparator.comparing(s -> {
            String k = (s.rowKey == null) ? "" : s.rowKey;
            return k.isBlank() ? (s.rootId == null ? "" : s.rootId) : k;
        }));

        int total = scopeStates.size();
        int passed = 0;
        int failed = 0;

        List<Status> perScopeStatus = new ArrayList<>(scopeStates.size());
        for (ScopeState s : scopeStates) {
            Status st = computeOverallStatusForScope(s);
            perScopeStatus.add(st);
            if (st == Status.FAIL) failed++; else passed++;
        }
        int passPct = (total == 0) ? 0 : (int) Math.round((passed * 100.0) / total);
        passPct = Math.max(0, Math.min(100, passPct));

        out.append("<div class=\"page\">\n");
        out.append("  <aside class=\"leftRail\">\n");
        out.append("    <div class=\"railHeader\">\n");

        out.append("      <div class=\"topSummary\">\n");
        out.append("        <div class=\"summaryTitle\">Cucumber Run Summary</div>\n");
        out.append("        <div class=\"summaryMeta\">Generated ")
                .append(escapeHtml(TS_FMT.format(Instant.now())))
                .append("</div>\n");

        out.append("        <div class=\"summaryNumbers\">\n");
        out.append("          <span class=\"pill passPill\">Passed: ").append(passed).append("/").append(total).append("</span>\n");
        out.append("          <span class=\"pill failPill\">Failed: ").append(failed).append("/").append(total).append("</span>\n");
        out.append("          <span class=\"pill pctPill\">").append(passPct).append("% pass</span>\n");
        out.append("        </div>\n");

        out.append("        <div class=\"barWrap\" aria-label=\"Pass/fail percentage bar\">\n");
        out.append("          <div class=\"barFail\"></div>\n");
        out.append("          <div class=\"barPass\" style=\"width: ").append(passPct).append("%;\"></div>\n");
        out.append("        </div>\n");
        out.append("      </div>\n");

        out.append("    </div>\n"); // railHeader

        out.append("    <nav class=\"railTabs\">\n");
        out.append("      <div class=\"tabButtons\">\n");

        // MAIN tab label: "Scenarios (N)" (no dot)
        out.append("        <button class=\"tabBtn main active\" data-tab=\"main\">");
        out.append("Scenarios (").append(total).append(")");
        out.append("</button>\n");

        for (int i = 0; i < scopeStates.size(); i++) {
            ScopeState s = scopeStates.get(i);
            HtmlNode root = s.nodes.get(s.rootId);
            if (root == null) continue;

            String label = root.title;
            if (label == null || label.isBlank()) label = "Scenario " + (i + 1);

            Status st = perScopeStatus.get(i);
            String stClass = (st == Status.FAIL) ? "fail" : "pass";

            out.append("        <button class=\"tabBtn ").append(stClass).append("\" data-tab=\"sc").append(i).append("\">");
            out.append("<span class=\"statusDot ").append(stClass).append("\"></span>");
            out.append(label);
            out.append("</button>\n");
        }

        out.append("      </div>\n");
        out.append("    </nav>\n");

        out.append("  </aside>\n");
        out.append("  <main class=\"main\">\n");

        out.append("<section class=\"scope tabPanel active\" id=\"main\">\n");
        out.append("  <div class=\"scopeHeader\">\n");
        out.append("    <div class=\"scopeName\">All Scenarios</div>\n");
        out.append("  </div>\n");
        renderOverviewSpreadsheet(out, scopeStates, perScopeStatus);
        out.append("</section>\n");

        for (int i = 0; i < scopeStates.size(); i++) {
            ScopeState s = scopeStates.get(i);
            HtmlNode root = s.nodes.get(s.rootId);
            if (root == null) continue;

            for (HtmlNode n : s.nodes.values()) n.children.clear();
            for (HtmlNode n : s.nodes.values()) {
                if (n.parentId != null) {
                    HtmlNode parent = s.nodes.get(n.parentId);
                    if (parent != null) parent.children.add(n);
                }
            }

            out.append("<section class=\"scope tabPanel\" id=\"sc").append(i).append("\">\n");
//            out.append("  <div class=\"scopeHeader\">\n");
//            out.append("    <div class=\"scopeName\">").append(root.title).append("</div>\n");
//            out.append("  </div>\n");

            if (s.rowKey != null) renderScenarioSummary(out, s.rowKey);

            out.append("  <div class=\"tree\">\n");
            renderNode(out, root, 0);
            out.append("  </div>\n");
            out.append("</section>\n");
        }

        out.append("<script>\n");
        out.append("(function(){\n");
        out.append("  var btns = document.querySelectorAll('.tabBtn');\n");
        out.append("  var panels = document.querySelectorAll('.tabPanel');\n");
        out.append("  function show(id){\n");
        out.append("    panels.forEach(function(p){ p.classList.toggle('active', p.id === id); });\n");
        out.append("    btns.forEach(function(b){ b.classList.toggle('active', b.getAttribute('data-tab') === id); });\n");
        out.append("  }\n");
        out.append("  btns.forEach(function(b){ b.addEventListener('click', function(){ show(b.getAttribute('data-tab')); }); });\n");
        out.append("  var first = document.querySelector('.tabBtn[data-tab=\"main\"]');\n");
        out.append("  if (first) show('main');\n");
        out.append("})();\n");
        out.append("</script>\n");

        out.append("  </main>\n");
        out.append("</div>\n");

        out.append("</body>\n</html>\n");
        return out.toString();
    }

    private void renderOverviewSpreadsheet(StringBuilder out, List<ScopeState> scopeStates, List<Status> perScopeStatus) {
        List<String> summaryHeaders = new ArrayList<>();
        for (ScopeState s : scopeStates) {
            if (s.rowKey == null) continue;
            Optional<RowData> od = rowData(s.rowKey);
            if (od.isPresent()) {
                summaryHeaders = new ArrayList<>(od.get().headers());
                break;
            }
        }

        out.append("  <div class=\"overview\">\n");
        out.append("    <div class=\"overviewHint\">Same headers as each scenario summary.</div>\n");

        out.append("    <div class=\"overviewTableWrap\">\n");
        out.append("      <table class=\"overviewTable\">\n");
        out.append("        <thead><tr>\n");
        out.append("          <th>Scenario</th>\n");
        out.append("          <th>Result</th>\n");
        for (String h : summaryHeaders) {
            out.append("          <th>").append(escapeHtml(h)).append("</th>\n");
        }
        out.append("        </tr></thead>\n");
        out.append("        <tbody>\n");

        for (int i = 0; i < scopeStates.size(); i++) {
            ScopeState s = scopeStates.get(i);
            HtmlNode root = (s.rootId == null) ? null : s.nodes.get(s.rootId);
            String scenarioName = (root == null || root.title == null || root.title.isBlank()) ? ("Scenario " + (i + 1)) : root.title;

            Status st = (i < perScopeStatus.size()) ? perScopeStatus.get(i) : Status.PASS;
            String stCss = (st == Status.FAIL) ? "fail" : "pass";
            String stLabel = (st == Status.FAIL) ? "FAIL" : "PASS";

            Map<String, Object> valuesByHeader = Map.of();
            if (s.rowKey != null) {
                Optional<RowData> od = rowData(s.rowKey);
                if (od.isPresent()) valuesByHeader = od.get().valuesByHeader();
            }

            out.append("          <tr>\n");
            out.append("            <td class=\"ovScenario\">").append(scenarioName).append("</td>\n");
            out.append("            <td class=\"ovResult\"><span class=\"pill ovPill ").append(stCss).append("\">").append(stLabel).append("</span></td>\n");

            for (String h : summaryHeaders) {
                Object v = valuesByHeader.get(h);
                out.append("            <td>").append(escapeHtml(v == null ? "" : String.valueOf(v))).append("</td>\n");
            }

            out.append("          </tr>\n");
        }

        out.append("        </tbody>\n");
        out.append("      </table>\n");
        out.append("    </div>\n");
        out.append("  </div>\n");
    }


    private static String stripTagsForSort(String escaped) {
        return escaped == null ? "" : escaped.replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static void renderNode(StringBuilder out, HtmlNode node, int depth) {
        String indentClass = "d" + Math.min(depth, 6);

        out.append("<div class=\"node ").append(indentClass).append("\">");

        boolean hasDetails = !node.children.isEmpty()
                || (node.tags != null && !node.tags.isEmpty())
                || (node.fields != null && !node.fields.isEmpty())
                || !node.attachments.isEmpty()
                || !node.logs.isEmpty();

        if (hasDetails) out.append("<details open>");
        out.append("<summary class=\"nodeHeader ").append(headerBgClass(node)).append("\">");

        // left: title
        out.append("<div class=\"hdrLeft\">");
        out.append("<div class=\"hdrTitle\">").append(node.title).append("</div>");
        out.append("<div class=\"hdrMeta\">").append(escapeHtml(headerMeta(node))).append("</div>");
        out.append("</div>");

        // right: pills
        out.append("<div class=\"hdrRight\">");
        if (node.level != null) {
            out.append("<span class=\"badge ").append(mapLevelCss(node.level)).append("\">")
                    .append(escapeHtml(node.level.name()))
                    .append("</span>");
        }
        if (node.status != null) {
            out.append("<span class=\"badge ").append(mapStatusCss(node.status)).append("\">")
                    .append(escapeHtml(labelForStatus(node.status)))
                    .append("</span>");
        }
        out.append("</div>");

        out.append("</summary>");

        if (hasDetails) {
            out.append("<div class=\"details\">");

            if (node.tags != null && !node.tags.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"v\">");
                for (String t : node.tags) out.append("<span class=\"pill\">").append(t).append("</span>");
                out.append("</span></div>");
            }

            if (node.fields != null && !node.fields.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"v\">");
                out.append("<table class=\"fields\"><tbody>");
                for (Map.Entry<String, String> e : node.fields.entrySet()) {
                    out.append("<tr><td class=\"fk\">").append(e.getKey())
                            .append("</td><td class=\"fv\">").append(e.getValue())
                            .append("</td></tr>");
                }
                out.append("</tbody></table>");
                out.append("</span></div>");
            }

            if (!node.attachments.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"v\">");
                out.append("<div class=\"attachments\">");
                for (InlineImage a : node.attachments) {
                    out.append("<div class=\"att\">");
                    out.append("<div class=\"attName\">").append(a.name).append("</div>");
                    if (a.isImage && a.src != null && !a.src.isBlank()) {
                        out.append("<details class=\"imgDetails\">");
                        out.append("<summary class=\"imgSummary\">");
                        out.append("<img class=\"imgThumb\" alt=\"").append(a.name).append("\" src=\"")
                                .append(a.src).append("\">");
                        out.append("</summary>");
                        out.append("<div class=\"imgFullWrap\">");
                        out.append("<img class=\"imgFull\" alt=\"").append(a.name).append("\" src=\"")
                                .append(a.src).append("\">");
                        out.append("</div>");
                        out.append("</details>");
                    } else {
                        out.append("<div class=\"attText\">").append(a.text == null ? "" : a.text).append("</div>");
                    }
                    out.append("</div>");
                }
                out.append("</div>");
                out.append("</span></div>");
            }

            if (!node.logs.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"v\">");
                out.append("<div class=\"log\">");
                for (LogLine line : node.logs) {
                    out.append("<div class=\"logLine ").append(line.cssClass).append("\">");
                    out.append("<span class=\"ts\">").append(line.ts).append("</span>");
                    out.append("<span class=\"msg\">").append(line.msg).append("</span>");
                    out.append("</div>");
                }
                out.append("</div>");
                out.append("</span></div>");
            }

            if (!node.children.isEmpty()) {
                out.append("<div class=\"kids\">");
                for (HtmlNode child : node.children) renderNode(out, child, depth + 1);
                out.append("</div>");
            }

            out.append("</div>"); // details
            out.append("</details>");
        }

        out.append("</div>\n"); // node
    }

    private static String headerMeta(HtmlNode node) {
        // Prefer span timing if present; otherwise show timestamp if present.
        if (node.startedAt != null) {
            String start = TS_FMT.format(node.startedAt);
            if (node.stoppedAt != null) {
                String stop = TS_FMT.format(node.stoppedAt);
                String dur = formatDuration(Duration.between(node.startedAt, node.stoppedAt));
                return "Start " + start + " • Stop " + stop + " • Duration " + dur;
            }
            return "Start " + start + " • (running)";
        }

        if (node.timestampedAt != null) {
            return TS_FMT.format(node.timestampedAt);
        }

        return "";
    }

    private static String formatDuration(Duration d) {
        long millis = Math.max(0, d.toMillis());
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        long seconds = (millis % 60_000) / 1_000;
        long ms = millis % 1_000;
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, ms);
    }

    private static String headerBgClass(HtmlNode node) {
        // Mild shading: failures/errors get attention
        if (node.status == Status.FAIL) return "bgFail";
        if (node.level == Level.ERROR) return "bgFail";
        if (node.status == Status.WARN || node.level == Level.WARN) return "bgWarn";
        if (node.status == Status.SKIP) return "bgSkip";
        return "bgNormal";
    }

    private static String labelForStatus(Status s) {
        return switch (s) {
            case PASS -> "PASS";
            case FAIL -> "FAIL";
            case SKIP -> "SKIP";
            case WARN -> "WARN";
            case INFO, UNKNOWN -> "INFO";
        };
    }

    private static String mapStatusCss(Status s) {
        return switch (s) {
            case PASS -> "pass";
            case FAIL -> "fail";
            case SKIP -> "skip";
            case WARN -> "warn";
            case INFO, UNKNOWN -> "info";
        };
    }

    private static String mapLevelCss(Level l) {
        return switch (l) {
            case ERROR -> "fail";
            case WARN -> "warn";
            case INFO -> "info";
            case DEBUG, TRACE -> "muted";
        };
    }

    private enum Phase {START, TIMESTAMP, STOP}

    private static final class ScopeState {
        String rootId;
        String rowKey;
        final Map<String, HtmlNode> nodes = new LinkedHashMap<>();
        final Map<String, Meta> meta = new HashMap<>();
    }

    private static final class Meta {
        int tagsN;
        int fieldsN;
        int attachesN;
    }

    private static final class HtmlNode {
        final String id;
        final String title;     // already sanitized/escaped
        final String parentId;

        Status status;
        Level level;

        Instant startedAt;
        Instant stoppedAt;
        Instant timestampedAt;

        List<String> tags = List.of();               // already escaped
        Map<String, String> fields = Map.of();       // escaped key/value
        final List<InlineImage> attachments = new ArrayList<>();
        final List<LogLine> logs = new ArrayList<>();
        final List<HtmlNode> children = new ArrayList<>();

        HtmlNode(String id, String title, String parentId) {
            this.id = id;
            this.title = title;
            this.parentId = parentId;
        }

        void addLogLine(LogLine line) {
            if (logs.size() >= MAX_LOG_LINES_PER_NODE) return;
            logs.add(line);
        }

        HtmlNode deepCopy() {
            HtmlNode c = new HtmlNode(this.id, this.title, this.parentId);
            c.status = this.status;
            c.level = this.level;
            c.startedAt = this.startedAt;
            c.stoppedAt = this.stoppedAt;
            c.timestampedAt = this.timestampedAt;
            c.tags = (this.tags == null) ? List.of() : List.copyOf(this.tags);
            c.fields = (this.fields == null) ? Map.of() : Map.copyOf(this.fields);
            c.attachments.addAll(this.attachments);
            c.logs.addAll(this.logs);
            return c;
        }
    }

    private static final class LogLine {
        final String ts;
        final String cssClass;
        final String msg;

        private LogLine(String ts, String cssClass, String msg) {
            this.ts = ts;
            this.cssClass = cssClass;
            this.msg = msg;
        }

        static LogLine of(String ts, String cssClass, String msg) {
            return new LogLine(
                    escapeHtml(ts),
                    cssClass == null ? "info" : cssClass,
                    sanitizeLogText(msg)
            );
        }
    }

    private static final class InlineImage {
        final String name;
        final boolean isImage;
        final String src;    // data: uri (NOT escaped)
        final String text;   // escaped (for non-image)

        private InlineImage(String name, boolean isImage, String src, String text) {
            this.name = name;
            this.isImage = isImage;
            this.src = src;
            this.text = text;
        }

        static InlineImage image(String name, String dataUri) {
            return new InlineImage(escapeHtml(name), true, dataUri, null);
        }

        static InlineImage textOnly(String name, String text) {
            return new InlineImage(escapeHtml(name), false, null, text);
        }
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static List<String> sanitizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return List.of();
        ArrayList<String> out = new ArrayList<>(tags.size());
        for (String t : tags) {
            String cleaned = sanitizeCommon(t).replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200) + "…";
            out.add(escapeHtml(cleaned));
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, String> sanitizeFields(Map<String, ?> fields) {
        if (fields == null || fields.isEmpty()) return Map.of();
        TreeMap<String, String> out = new TreeMap<>();
        for (Map.Entry<String, ?> e : fields.entrySet()) {
            String k = escapeHtml(sanitizeCommon(String.valueOf(e.getKey())).replaceAll("\\s+", " ").trim());
            String v = sanitizeLogText(String.valueOf(e.getValue()));
            out.put(k, v);
        }
        return Collections.unmodifiableMap(out);
    }

    private static String sanitizeNodeName(String s) {
        String cleaned = sanitizeCommon(s);
        cleaned = escapeHtml(cleaned);
        return cleaned.isBlank() ? "(unnamed)" : cleaned;
    }

    private static String sanitizeLogText(String s) {
        String cleaned = sanitizeCommon(s);
        return escapeHtml(cleaned);
    }

    private static String sanitizeAttachmentName(String s) {
        String cleaned = sanitizeCommon(s).replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 300) cleaned = cleaned.substring(0, 300) + "…";
        return cleaned;
    }

    private static String rawBase64(String maybeDataUri) {
        if (maybeDataUri == null) return null;
        String s = maybeDataUri.trim();
        if (s.startsWith("data:image")) {
            int comma = s.indexOf(',');
            if (comma >= 0 && comma + 1 < s.length()) return s.substring(comma + 1);
        }
        return s;
    }

    private static String sanitizeCommon(String s) {
        if (s == null) return "";

        String x = s;

        x = x.replace('\u2028', '\n')
                .replace('\u2029', '\n')
                .replace('\u0085', '\n');

        StringBuilder sb = new StringBuilder(x.length());
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);

            if (c == '\n' || c == '\r' || c == '\t') {
                sb.append(c);
                continue;
            }
            if (Character.isISOControl(c)) continue;

            sb.append(c);
        }

        String cleaned = sb.toString();

        if (cleaned.length() > MAX_TEXT_LEN) {
            cleaned = cleaned.substring(0, MAX_TEXT_LEN) + "\n…(truncated)…";
        }

        return cleaned;
    }

    private static String escapeHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder out = new StringBuilder((int) (s.length() * 1.1));
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private void renderScenarioSummary(StringBuilder out, String rowKey) {
        rowData(rowKey).ifPresent(rd -> {
            out.append("  <div class=\"scenarioSummary\">\n");
            out.append("    <div class=\"scenarioSummaryTitle\">Scenario Summary</div>\n");
            out.append("    <table class=\"summaryTable\">\n");
            out.append("      <thead><tr>\n");
            for (String h : rd.headers()) {
                out.append("        <th>").append(escapeHtml(h)).append("</th>\n");
            }
            out.append("      </tr></thead>\n");
            out.append("      <tbody><tr>\n");
            for (String h : rd.headers()) {
                Object v = rd.valuesByHeader().get(h);
                out.append("        <td>")
                        .append(escapeHtml(v == null ? "" : String.valueOf(v)))
                        .append("</td>\n");
            }
            out.append("      </tr></tbody>\n");
            out.append("    </table>\n");
            out.append("  </div>\n");
        });
    }

    private static final String CSS = """
:root {
  --bg: #ffffff;
  --fg: #111827;
  --muted: #6b7280;
  --card: #f9fafb;
  --line: #e5e7eb;
  --pill: #eef2ff;
  --pass: #065f46;
  --fail: #991b1b;
  --warn: #92400e;
  --info: #1f2937;
  --skip: #374151;
  --main: #1d4ed8;

  --hdrFail: #F26144;
  --hdrWarn: #fffbeb;
  --hdrSkip: #f3f4f6;
  --hdrNormal: #89D18B;
}

* { box-sizing: border-box; }
html, body {
  margin: 0;
  padding: 0;
  background: var(--bg);
  color: var(--fg);
  font-family: Arial, Helvetica, sans-serif;
}
a { color: inherit; }

.page { display: flex; height: 100vh; overflow: hidden; }

/* ---------------- Left rail / tabs ---------------- */
.leftRail {
  width: 320px;
  min-width: 240px;
  max-width: 420px;
  border-right: 1px solid var(--line);
  background: #fafafa;
  display: flex;
  flex-direction: column;
}

.railHeader {
  padding: 14px 14px 12px 14px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}

.topSummary .summaryTitle { font-weight: 800; font-size: 14px; margin-bottom: 6px; }
.topSummary .summaryMeta { font-size: 12px; color: var(--muted); margin-bottom: 10px; }

.summaryNumbers { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }

.pill {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--pill);
  border: 1px solid #e0e7ff;
  font-size: 12px;
  white-space: nowrap;
}
.passPill { border-color: #bbf7d0; background: #ecfdf5; color: var(--pass); }
.failPill { border-color: #fecaca; background: #fef2f2; color: var(--fail); }
.pctPill  { border-color: #e5e7eb; background: #fff; color: var(--info); }

.barWrap {
  position: relative;
  height: 14px;
  border-radius: 999px;
  overflow: hidden;
  border: 1px solid var(--line);
  background: #fff;
}
.barFail { position: absolute; inset: 0; background: #d63a3a; }
.barPass { position: absolute; inset: 0; background: #1f9d4c; }

.railTabs { padding: 10px; overflow: auto; flex: 1; }
.tabButtons { display: flex; flex-direction: column; gap: 8px; }

.tabBtn {
  cursor: pointer;
  border: 1px solid var(--line);
  background: #fff;
  color: var(--fg);
  border-radius: 10px;
  padding: 10px 10px;
  font-size: 13px;
  line-height: 1.2;
  text-align: left;
  display: flex;
  align-items: center;
  gap: 8px;
}
.tabBtn.active { outline: 2px solid #111827; border-color: #111827; }
.tabBtn.pass { border-left: 10px solid #1f9d4c; }
.tabBtn.fail { border-left: 10px solid #d63a3a; }
.tabBtn.main { border-left: 10px solid var(--main); }

.statusDot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex: 0 0 10px;
  border: 1px solid var(--line);
  background: #fff;
}
.statusDot.pass { background: #1f9d4c; border-color: #1f9d4c; }
.statusDot.fail { background: #d63a3a; border-color: #d63a3a; }

/* ---------------- Main panels ---------------- */
.main { flex: 1; overflow: auto; background: #fff; }

.tabPanel { display: none; }
.tabPanel.active { display: block; }

.scope {
  margin: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.scopeHeader {
  display: flex;
  gap: 10px;
  justify-content: space-between;
  align-items: baseline;
  padding: 12px 12px;
  border-bottom: 1px solid var(--line);
  background: var(--card);
}
.scopeName { font-weight: 700; }

/* ---------------- Tree / nodes ---------------- */
.tree { padding: 10px; }
.node { margin: 10px 0; }

.node details {
  border: 2px solid var(--line);          /* stronger outlines */
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
}

/* Reset summary defaults cleanly */
.node summary {
  list-style: none;
  cursor: pointer;
//  padding: 0;
}
.node summary::-webkit-details-marker { display: none; }

.nodeHeader {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--line);
}
.nodeHeader.bgFail { background: var(--hdrFail); }
.nodeHeader.bgWarn { background: var(--hdrWarn); }
.nodeHeader.bgSkip { background: var(--hdrSkip); }
.nodeHeader.bgNormal { background: var(--hdrNormal); }

.hdrLeft { min-width: 0; }
.hdrTitle { font-weight: 700; word-break: break-word; }
.hdrMeta { margin-top: 4px; font-size: 12px; color: var(--muted); }

.hdrRight { display: flex; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }

.details { padding: 10px 12px 12px 12px; background: #fff; }

.badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: #fff;
  color: var(--info);
  white-space: nowrap;
}
.badge.pass { color: var(--pass); border-color: #bbf7d0; background: #ecfdf5; }
.badge.fail { color: var(--fail); border-color: #fecaca; background: #fef2f2; }
.badge.warn { color: var(--warn); border-color: #fde68a; background: #fffbeb; }
.badge.skip { color: var(--skip); border-color: #e5e7eb; background: #f9fafb; }
.badge.info { color: var(--info); border-color: #e5e7eb; background: #f9fafb; }
.badge.muted { color: var(--muted); border-color: #e5e7eb; background: #fff; }

.row { margin-top: 10px; }
.v { display: block; min-width: 0; }

.pill { margin: 0 6px 6px 0; }

.fields { width: 100%; border-collapse: collapse; }
.fields td { border: 1px solid var(--line); padding: 6px 8px; vertical-align: top; }
.fk { width: 220px; color: var(--muted); font-size: 12px; }
.fv { font-size: 12px; white-space: pre-wrap; word-break: break-word; }

/* ---------------- Attachments (screenshots) ---------------- */
/* Bigger cards so single screenshot doesn’t look tiny */
.attachments {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 12px;
}

/* Make each card feel intentional */
.att {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--card);
  overflow: hidden;
}

.attName {
  padding: 8px 10px;
  font-size: 12px;
  font-weight: 700;
  border-bottom: 1px solid var(--line);
  background: #fff;
}

/* Reset <details>/<summary> quirks */
.imgDetails {
  border-top: 0;
  background: #fff;
//  width: 100%;
}
.imgDetails > summary {
  list-style: none;
//  width: 100%;
  margin: 0;
  padding: 12px;
  cursor: pointer;

  /* Force block sizing and prevent browser centering quirks */
  display: block !important;
}
.imgDetails > summary::-webkit-details-marker { display: none; }

/* Stack thumbnail + hint normally (NOT side-by-side) */
.imgSummary {
//  width: 100%;
}
.imgThumb {
//  width: 100%;
  height: 320px;              /* main “presence” control */
  object-fit: contain;        /* keep screenshots readable */
  border-radius: 10px;
  border: 1px solid var(--line);
  background: #fff;
  display: block;
}

/* Expanded image */
.imgFullWrap {
  padding: 10px;
  border-top: 1px solid var(--line);
  background: var(--card);
}
.imgFull {
  width: 100%;
  height: auto;
  border-radius: 10px;
  border: 1px solid var(--line);
  background: #fff;
}

/* Non-image attachments */
.attText {
  padding: 8px 10px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--fg);
}

/* ---------------- Log ---------------- */
.log { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: #fff; }
.logLine { display: flex; gap: 10px; padding: 6px 10px; border-top: 1px solid var(--line); font-size: 12px; }
.logLine:first-child { border-top: none; }
.ts { color: var(--muted); white-space: nowrap; }
.msg { white-space: pre-wrap; word-break: break-word; }
.logLine.pass .msg { color: var(--pass); }
.logLine.fail .msg { color: var(--fail); }
.logLine.warn .msg { color: var(--warn); }
.logLine.skip .msg { color: var(--skip); }

.kids { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--line); }

/* Indentation */
.d0 { margin-left: 0px; }
.d1 { margin-left: 12px; }
.d2 { margin-left: 24px; }
.d3 { margin-left: 36px; }
.d4 { margin-left: 48px; }
.d5 { margin-left: 60px; }
.d6 { margin-left: 72px; }

/* ---------------- Scenario summary ---------------- */
.scenarioSummary {
  margin: 12px 12px 16px 12px;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  background: #fafafa;
}
.scenarioSummaryTitle { font-weight: 700; margin-bottom: 8px; }
.summaryTable { width: 100%; border-collapse: collapse; font-size: 13px; }
.summaryTable th, .summaryTable td {
  border: 1px solid #e0e0e0;
  padding: 6px 8px;
  vertical-align: top;
}
.summaryTable th { background: #f2f2f2; text-align: left; }

/* ---------------- Overview table ---------------- */
.overview { padding: 12px; }
.overviewHint { color: var(--muted); font-size: 12px; margin-bottom: 10px; }
.overviewTableWrap { overflow: auto; border: 1px solid var(--line); border-radius: 10px; }
.overviewTable { width: 100%; border-collapse: collapse; font-size: 13px; }
.overviewTable th, .overviewTable td { border: 1px solid var(--line); padding: 8px 10px; vertical-align: top; }
.overviewTable th { background: var(--card); text-align: left; position: sticky; top: 0; z-index: 5; }
.ovScenario { font-weight: 700; min-width: 220px; }
.ovResult { white-space: nowrap; }
.ovPill.pass { color: var(--pass); border-color: #bbf7d0; background: #ecfdf5; }
.ovPill.fail { color: var(--fail); border-color: #fecaca; background: #fef2f2; }
""";
}