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
    }

    private static Path resolveSingleFileOutput(Path perScenarioReportFile) {
        String p = System.getProperty("pickleball.simplehtml.singleFile.path");
        if (p == null || p.isBlank()) p = System.getenv("PICKLEBALL_SIMPLEHTML_SINGLEFILE_PATH");
        if (p != null && !p.isBlank()) {
            try {
                return Path.of(p.trim());
            } catch (Throwable ignored) {
            }
        }

        Path dir = (perScenarioReportFile != null && perScenarioReportFile.getParent() != null)
                ? perScenarioReportFile.getParent()
                : Path.of(".");
        return dir.resolve("cucumber-report.html");
    }

    @Override
    protected void onClose() {
        // IMPORTANT: We do NOT auto-generate the final HTML here.
        // close() only contributes this converter's in-memory scenario data to the shared run snapshot.
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
            }
        }
    }

    private static ScopeState deepCopyScopeState(ScopeState src) {
        ScopeState c = new ScopeState();
        c.rootId = src.rootId;
        c.rowKey = src.rowKey;

        // NEW
        c.includeInSummary = src.includeInSummary;

        for (Map.Entry<String, HtmlNode> e : src.nodes.entrySet()) {
            c.nodes.put(e.getKey(), e.getValue().deepCopy());
        }
        return c;
    }

    // ---------------------------------------------------------------------------------
    // Explicit finalization API (caller-controlled)
    // ---------------------------------------------------------------------------------

    /**
     * Clears all accumulated scenario data for the current JVM. Call this once before a new Cucumber run
     * if you reuse the same JVM (e.g., from Gradle daemon, Surefire reuseForks, IDE, etc.).
     */
    public static void resetRun() {
        synchronized (SharedSingleFile.LOCK) {
            SharedSingleFile.ALL_SCOPES.clear();
            SharedSingleFile.REPORT_FILE = null;
        }
    }

    /**
     * Returns the resolved default output file for the single combined report.
     */
    public static Path defaultReportFile(Path anyPerScenarioPath) {
        return resolveSingleFileOutput(anyPerScenarioPath);
    }

    /**
     * Writes the combined, tabbed HTML report to the default single-file output location.
     * This must be called explicitly by external code once the entire Cucumber run is complete.
     */
    public static void writeFinalReport() {
        Path out;
        synchronized (SharedSingleFile.LOCK) {
            out = SharedSingleFile.REPORT_FILE;
        }
        if (out == null) out = Path.of("cucumber-report.html");
        writeFinalReport(out);
    }

    /**
     * Writes the combined, tabbed HTML report to {@code outFile}.
     * This must be called explicitly by external code once the entire Cucumber run is complete.
     */
    public static void writeFinalReport(Path outFile) {
        if (outFile == null) outFile = Path.of("cucumber-report.html");

        synchronized (SharedSingleFile.LOCK) {
            SharedSingleFile.REPORT_FILE = outFile;
        }

        try {
            // Use a lightweight instance to reuse the existing (instance) rendering code that depends on BaseConverter.rowData(...).
            new SimpleHtmlReportConverter(outFile).writeCombinedReport(outFile);
        } catch (Throwable ex) {
            System.err.println("[SimpleHtmlReportConverter] FAILED writing combined report: " + ex);
        }
    }


    private void writeCombinedReport(Path outFile) throws IOException {
        Map<String, ScopeState> snapshot = new LinkedHashMap<>();

        synchronized (SharedSingleFile.LOCK) {
            for (Map.Entry<String, ScopeState> e : SharedSingleFile.ALL_SCOPES.entrySet()) {
                snapshot.put(e.getKey(), deepCopyScopeState(e.getValue()));
            }
        }

        if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());

        String html = renderHtmlDocument(snapshot, outFile);
        Files.writeString(outFile, html, StandardCharsets.UTF_8);

        System.out.println("[SimpleHtmlReportConverter] wrote combined: " + outFile.toAbsolutePath());
    }

    @Override
    public void onStart(Entry scope, Entry entry) {
        emit(scope, entry, Phase.START);
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        emit(scope, entry, Phase.TIMESTAMP);
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        emit(scope, entry, Phase.STOP);
    }

    private void emit(Entry scope, Entry entry, Phase phase) {
        ScopeState s = scopes.computeIfAbsent(scope.id, id -> initScope(scope));

        synchronized (lock) {
            HtmlNode node = ensureNode(s, scope, entry);

            if (phase == Phase.START) {
                node.startedAt = (entry.startedAt != null) ? entry.startedAt : Instant.now();
            } else if (phase == Phase.TIMESTAMP) {
                node.timestampedAt = (entry.timestampedAt != null) ? entry.timestampedAt : Instant.now();
            } else if (phase == Phase.STOP) {
                node.stoppedAt = (entry.stoppedAt != null) ? entry.stoppedAt : Instant.now();
                if (entry.status != null) node.status = entry.status;
            }

            if (entry.level != null) node.level = entry.level;

            // Only keep text log rows when they add information beyond the node title itself.
            if (phase == Phase.TIMESTAMP) {
                Status st = (entry.status != null) ? entry.status : Status.INFO;

                String nodeTitlePlain = normalizeNodeTitle(node.title);
                String entryTextPlain = normalizeNodeTitle(sanitizeNodeName(entry.text));

                if (!entryTextPlain.equals(nodeTitlePlain)) {
                    node.addLogLine(LogLine.of(
                            (entry.timestampedAt != null) ? entry.timestampedAt : Instant.now(),
                            mapStatusCss(st),
                            entry.text,
                            node.nextEventOrder++
                    ));
                }
            }

            if (phase == Phase.START) {
                node.addLogLine(LogLine.of(
                        (entry.startedAt != null) ? entry.startedAt : Instant.now(),
                        "muted",
                        "START",
                        node.nextEventOrder++
                ));
            }

            if (phase == Phase.STOP) {
                Status st = (entry.status != null) ? entry.status : Status.UNKNOWN;
                node.addLogLine(LogLine.of(
                        (entry.stoppedAt != null) ? entry.stoppedAt : Instant.now(),
                        mapStatusCss(st),
                        "STOP " + labelForStatus(st),
                        node.nextEventOrder++
                ));
            }

            applyMetaOncePerChange(s, entry, node, phase);
        }
    }

    private static String tsOf(Instant i) {
        if (i == null) i = Instant.now();
        return TS_FMT.format(i);
    }

    private ScopeState initScope(Entry scope) {
        ScopeState s = new ScopeState();
        s.rootId = scope.id;

        // NEW: read the flag from the root scope entry
        s.includeInSummary = scope.isIncludedInSummary();

        try {
            s.rowKey = GlobalState.getCurrentScenarioState().id.toString();
        } catch (Throwable ignored) {
            s.rowKey = null;
        }

        HtmlNode root = new HtmlNode(scope.id, sanitizeNodeName(scope.text), null);
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

    private void applyMetaOncePerChange(ScopeState s, Entry e, HtmlNode node, Phase phase) {
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

        Instant attachmentTime = timeForPhase(e, phase);

        for (int i = m.attachesN; i < attachesN; i++) {
            Attachment a = e.attachments.get(i);
            String fp = attachmentFingerprint(a);

            // hard dedupe: do not render the same attachment payload twice for the same node
            if (!m.attachmentFingerprints.add(fp)) {
                continue;
            }

            String rawName = (a.name() == null || a.name().isBlank()) ? "Attachment" : a.name();
            String mime = (a.mime() == null) ? "" : a.mime();
            String data = a.path();

            boolean image = mime.startsWith("image/");
            String displayName = displayAttachmentLabel(rawName, image);

            if (image) {
                InlineImage img = inlineImage(
                        displayName,
                        mime,
                        data,
                        attachmentTime,
                        node.nextEventOrder++
                );
                node.attachments.add(img);
            } else {
                node.attachments.add(InlineImage.textOnly(
                        displayName,
                        sanitizeLogText(String.valueOf(data)),
                        attachmentTime,
                        node.nextEventOrder++
                ));
            }
        }

        m.attachesN = attachesN;
    }

    private InlineImage inlineImage(String name, String mime, String data, Instant at, int order) {
        try {
            if (mime.contains("base64")) {
                String raw = rawBase64(data);
                if (raw == null || raw.isBlank()) {
                    return InlineImage.textOnly(name, sanitizeLogText("<empty base64>"), at, order);
                }
                String mediaType = mime.substring(0, mime.indexOf(';') >= 0 ? mime.indexOf(';') : mime.length());
                String src = "data:" + mediaType + ";base64," + raw;
                return InlineImage.image(name, src, at, order);
            }

            String trimmed = (data == null) ? "" : data.trim();
            if (trimmed.startsWith("data:image")) {
                return InlineImage.image(name, trimmed, at, order);
            }

            if (trimmed.isBlank()) {
                return InlineImage.textOnly(name, sanitizeLogText("<empty path>"), at, order);
            }

            Path p = Path.of(trimmed);
            if (!Files.exists(p)) {
                Path maybeRel = (reportFile == null || reportFile.getParent() == null) ? p : reportFile.getParent().resolve(trimmed);
                if (Files.exists(maybeRel)) p = maybeRel;
            }

            if (!Files.exists(p)) {
                return InlineImage.textOnly(name, sanitizeLogText("Missing image file: " + trimmed), at, order);
            }

            long size = Files.size(p);
            if (size > MAX_INLINE_IMAGE_BYTES) {
                return InlineImage.textOnly(
                        name,
                        sanitizeLogText("Image too large to inline (" + size + " bytes). Not embedded."),
                        at,
                        order
                );
            }

            String guessed = Files.probeContentType(p);
            if (guessed == null || !guessed.startsWith("image/")) {
                guessed = "image/png";
            }

            byte[] bytes = Files.readAllBytes(p);
            String src = "data:" + guessed + ";base64," + Base64.getEncoder().encodeToString(bytes);
            return InlineImage.image(name, src, at, order);
        } catch (Exception ex) {
            return InlineImage.textOnly(name, sanitizeLogText("Failed to inline image: " + ex), at, order);
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

        List<ScopeState> all = new ArrayList<>(scopesToRender.values());
        all.sort(Comparator.comparing(s -> {
            String k = (s.rowKey == null) ? "" : s.rowKey;
            return k.isBlank() ? (s.rootId == null ? "" : s.rootId) : k;
        }));

        // Split: included scopes get tabs; excluded scopes render under MAIN tab.
        List<ScopeState> included = new ArrayList<>();
        List<ScopeState> excluded = new ArrayList<>();

        for (ScopeState s : all) {
            if (s == null) continue;
            if (s.includeInSummary) included.add(s);
            else excluded.add(s);
        }

        // Compute statuses (for included scopes only, since those become scenario tabs)
        List<Status> includedStatus = new ArrayList<>(included.size());
        for (ScopeState s : included) includedStatus.add(computeOverallStatusForScope(s));

        // Summary counts should still be based on "includeInSummary" scopes
        int totalCounted = included.size();
        int passedCounted = 0;
        int failedCounted = 0;
        for (Status st : includedStatus) {
            if (st == Status.FAIL) failedCounted++;
            else passedCounted++;
        }

        int passPct = (totalCounted == 0) ? 0 : (int) Math.round((passedCounted * 100.0) / totalCounted);
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
        out.append("          <span class=\"pill passPill\">Passed: ")
                .append(passedCounted).append("/").append(totalCounted).append("</span>\n");
        out.append("          <span class=\"pill failPill\">Failed: ")
                .append(failedCounted).append("/").append(totalCounted).append("</span>\n");
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

        // MAIN tab label
        out.append("        <button class=\"tabBtn main active\" data-tab=\"main\">");
        out.append("Scenarios (").append(totalCounted).append(")");
        out.append("</button>\n");

        // Scenario tabs (included only)
        for (int i = 0; i < included.size(); i++) {
            ScopeState s = included.get(i);
            HtmlNode root = s.nodes.get(s.rootId);
            if (root == null) continue;

            String label = root.title;
            if (label == null || label.isBlank()) label = "Scenario " + (i + 1);

            Status st = includedStatus.get(i);
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

        // MAIN panel
        out.append("<section class=\"scope tabPanel active\" id=\"main\">\n");
        out.append("  <div class=\"scopeHeader\">\n");
        out.append("    <div class=\"scopeName\">All Scenarios</div>\n");
        out.append("  </div>\n");

        // Overview table (included scopes only)
        renderOverviewSpreadsheet(out, included, includedStatus);

        // NEW: excluded scopes appear under the overview table
        renderExcludedScopesUnderMain(out, excluded);

        out.append("</section>\n");

        // Scenario panels (included only)
        for (int i = 0; i < included.size(); i++) {
            ScopeState s = included.get(i);
            HtmlNode root = s.nodes.get(s.rootId);
            if (root == null) continue;

            rebuildChildrenLinks(s);
            collapseRedundantEchoNodes(root);
            out.append("<section class=\"scope tabPanel\" id=\"sc").append(i).append("\">\n");

            if (s.rowKey != null) renderScenarioSummary(out, s.rowKey);

            out.append("  <div class=\"tree\">\n");
            renderNode(out, root, 0);
            out.append("  </div>\n");
            out.append("</section>\n");
        }

        // JS unchanged (still targets mainSummaryTable, and jumps to sc{i})
        out.append("<script>\n");
        out.append("(function(){\n");
        out.append("  var btns = document.querySelectorAll('.tabBtn');\n");
        out.append("  var panels = document.querySelectorAll('.tabPanel');\n");
        out.append("  function show(id){\n");
        out.append("    panels.forEach(function(p){ p.classList.toggle('active', p.id === id); });\n");
        out.append("    btns.forEach(function(b){ b.classList.toggle('active', b.getAttribute('data-tab') === id); });\n");
        out.append("  }\n");
        out.append("  window.__showTab = show;\n");
        out.append("  btns.forEach(function(b){ b.addEventListener('click', function(){ show(b.getAttribute('data-tab')); }); });\n");
        out.append("  var first = document.querySelector('.tabBtn[data-tab=\"main\"]');\n");
        out.append("  if (first) show('main');\n");
        out.append("\n");
        out.append("  var table = document.getElementById('mainSummaryTable');\n");
        out.append("  if (!table) return;\n");
        out.append("  var tbody = table.querySelector('tbody');\n");
        out.append("  var hdrCells = table.querySelectorAll('thead tr.hdrRow th');\n");
        out.append("  var filterInputs = table.querySelectorAll('input.colFilter');\n");
        out.append("  var statusRadios = document.querySelectorAll('input[name=\"ovStatus\"]');\n");
        out.append("\n");
        out.append("  function getStatusBucket(tr){\n");
        out.append("    if (tr.classList.contains('rowPass')) return 'pass';\n");
        out.append("    if (tr.classList.contains('rowFail')) return 'fail';\n");
        out.append("    return 'all';\n");
        out.append("  }\n");
        out.append("\n");
        out.append("  function applyFilters(){\n");
        out.append("    var wanted = 'all';\n");
        out.append("    statusRadios.forEach(function(r){ if (r.checked) wanted = r.value; });\n");
        out.append("\n");
        out.append("    var colFilters = {};\n");
        out.append("    filterInputs.forEach(function(inp){\n");
        out.append("      var th = inp.closest('th');\n");
        out.append("      var idx = Array.prototype.indexOf.call(th.parentNode.children, th);\n");
        out.append("      var val = (inp.value || '').trim().toLowerCase();\n");
        out.append("      if (val) colFilters[idx] = val;\n");
        out.append("    });\n");
        out.append("\n");
        out.append("    var rows = tbody.querySelectorAll('tr');\n");
        out.append("    rows.forEach(function(tr){\n");
        out.append("      var ok = true;\n");
        out.append("      if (wanted !== 'all') {\n");
        out.append("        var bucket = getStatusBucket(tr);\n");
        out.append("        if (bucket !== wanted) ok = false;\n");
        out.append("      }\n");
        out.append("      if (ok) {\n");
        out.append("        for (var idx in colFilters) {\n");
        out.append("          var i = parseInt(idx, 10);\n");
        out.append("          var td = tr.children[i];\n");
        out.append("          var txt = (td ? (td.textContent || '') : '').toLowerCase();\n");
        out.append("          if (txt.indexOf(colFilters[idx]) === -1) { ok = false; break; }\n");
        out.append("        }\n");
        out.append("      }\n");
        out.append("      tr.style.display = ok ? '' : 'none';\n");
        out.append("    });\n");
        out.append("  }\n");
        out.append("\n");
        out.append("  filterInputs.forEach(function(inp){ inp.addEventListener('input', applyFilters); });\n");
        out.append("  statusRadios.forEach(function(r){ r.addEventListener('change', applyFilters); });\n");
        out.append("\n");
        out.append("  table.addEventListener('click', function(e){\n");
        out.append("    var btn = e.target.closest('.jumpBtn');\n");
        out.append("    if (!btn) return;\n");
        out.append("    var id = btn.getAttribute('data-tab');\n");
        out.append("    if (id) show(id);\n");
        out.append("  });\n");
        out.append("\n");
        out.append("  function normalize(s){ return (s||'').trim(); }\n");
        out.append("  function isNumeric(s){ return /^-?\\d+(\\.\\d+)?$/.test(s); }\n");
        out.append("\n");
        out.append("  function sortByCol(colIdx){\n");
        out.append("    if (colIdx === 0) return;\n");
        out.append("    var th = hdrCells[colIdx];\n");
        out.append("    var cur = th.getAttribute('data-sort') || 'none';\n");
        out.append("    var dir = (cur === 'asc') ? 'desc' : 'asc';\n");
        out.append("    hdrCells.forEach(function(h, i){ if (i !== colIdx) h.setAttribute('data-sort','none'); });\n");
        out.append("    th.setAttribute('data-sort', dir);\n");
        out.append("    var rows = Array.prototype.slice.call(tbody.querySelectorAll('tr'));\n");
        out.append("    rows.sort(function(a,b){\n");
        out.append("      var av = normalize(a.children[colIdx] ? a.children[colIdx].textContent : '');\n");
        out.append("      var bv = normalize(b.children[colIdx] ? b.children[colIdx].textContent : '');\n");
        out.append("      if (isNumeric(av) && isNumeric(bv)) {\n");
        out.append("        var an = parseFloat(av), bn = parseFloat(bv);\n");
        out.append("        return (dir === 'asc') ? (an - bn) : (bn - an);\n");
        out.append("      }\n");
        out.append("      var cmp = av.localeCompare(bv);\n");
        out.append("      return (dir === 'asc') ? cmp : -cmp;\n");
        out.append("    });\n");
        out.append("    rows.forEach(function(r){ tbody.appendChild(r); });\n");
        out.append("    applyFilters();\n");
        out.append("  }\n");
        out.append("\n");
        out.append("  hdrCells.forEach(function(th, idx){\n");
        out.append("    if (idx === 0) return;\n");
        out.append("    th.addEventListener('click', function(){ sortByCol(idx); });\n");
        out.append("  });\n");
        out.append("\n");
        out.append("  applyFilters();\n");
        out.append("})();\n");
        out.append("</script>\n");

        out.append("  </main>\n");
        out.append("</div>\n");

        out.append("</body>\n</html>\n");
        return out.toString();
    }

    private void renderOverviewSpreadsheet(StringBuilder out, List<ScopeState> includedScopes, List<Status> includedStatus) {
        // Determine the one true header set (order preserved) from the first scenario that provides it.
        List<String> summaryHeaders = List.of();
        for (ScopeState s : includedScopes) {
            if (s.rowKey == null) continue;
            Optional<RowData> od = rowData(s.rowKey);
            if (od.isPresent()) {
                summaryHeaders = List.copyOf(od.get().headers());
                break;
            }
        }

        // Build rows: one per scenario tab, preserving same column shape.
        // Also capture the tab id (sc{i}) so the link icon can jump to the scenario.
        final class Row {
            final String tabId;
            final Map<String, Object> values;

            Row(String tabId, Map<String, Object> values) {
                this.tabId = tabId;
                this.values = values;
            }
        }

        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < includedScopes.size(); i++) {
            ScopeState s = includedScopes.get(i);
            if (s.rowKey == null) continue;

            Optional<RowData> od = rowData(s.rowKey);
            if (od.isEmpty()) continue;

            rows.add(new Row("sc" + i, od.get().valuesByHeader()));
        }

        out.append("  <div class=\"overview\">\n");

        // Radio filter
        out.append("    <div class=\"overviewControls\">\n");
        out.append("      <div class=\"statusRadios\" role=\"radiogroup\" aria-label=\"Scenario status filter\">\n");
        out.append("        <label><input type=\"radio\" name=\"ovStatus\" value=\"all\" checked> All</label>\n");
        out.append("        <label><input type=\"radio\" name=\"ovStatus\" value=\"pass\"> Passed</label>\n");
        out.append("        <label><input type=\"radio\" name=\"ovStatus\" value=\"fail\"> Failed</label>\n");
        out.append("      </div>\n");
        out.append("      <div class=\"overviewHint\">Click headers to sort. Use filters under headers.</div>\n");
        out.append("    </div>\n");

        out.append("    <div class=\"overviewTableWrap\">\n");
        out.append("      <table class=\"summaryTable summaryTable--multi\" id=\"mainSummaryTable\">\n");

        // THEAD: header row + filter row
        out.append("        <thead>\n");

        // Header row (includes a leading link column)
        out.append("          <tr class=\"hdrRow\">\n");
        out.append("            <th class=\"linkCol\" data-sort=\"none\" title=\"Open scenario tab\">🔗</th>\n");
        for (String h : summaryHeaders) {
            out.append("            <th class=\"sortable\" data-sort=\"none\">").append(escapeHtml(h)).append("</th>\n");
        }
        out.append("          </tr>\n");

        // Filter inputs row (also includes a blank cell for link column)
        out.append("          <tr class=\"filterRow\">\n");
        out.append("            <th class=\"linkCol\"></th>\n");
        for (String h : summaryHeaders) {
            out.append("            <th>")
                    .append("<input class=\"colFilter\" type=\"text\" placeholder=\"Filter\" ")
                    .append("data-col=\"").append(escapeHtml(h)).append("\" aria-label=\"Filter ").append(escapeHtml(h)).append("\">")
                    .append("</th>\n");
        }
        out.append("          </tr>\n");

        out.append("        </thead>\n");

        // TBODY
        out.append("        <tbody>\n");

        for (Row r : rows) {
            // Determine row pass/fail class from STATUS column if present
            String rowStatusClass = "";
            Object stVal = null;
            for (String h : summaryHeaders) {
                if ("STATUS".equalsIgnoreCase(h.trim())) {
                    stVal = r.values.get(h);
                    break;
                }
            }
            String st = (stVal == null) ? "" : String.valueOf(stVal).trim();
            String stUpper = st.toUpperCase(Locale.ROOT);
            if (stUpper.startsWith("PASS")) rowStatusClass = "rowPass";
            else if (stUpper.startsWith("FAIL")) rowStatusClass = "rowFail";

            out.append("          <tr class=\"").append(rowStatusClass).append("\" data-tab=\"")
                    .append(escapeHtml(r.tabId)).append("\">\n");

            // Link icon cell
            out.append("            <td class=\"linkCol\">")
                    .append("<button class=\"jumpBtn\" type=\"button\" data-tab=\"")
                    .append(escapeHtml(r.tabId))
                    .append("\" title=\"Open scenario\">↪</button>")
                    .append("</td>\n");

            // Data cells in header order, with STATUS cell styling
            for (String h : summaryHeaders) {
                Object v = r.values.get(h);
                String cellText = (v == null) ? "" : String.valueOf(v);

                String tdClass = summaryCellClass(h, cellText); // your PASS/FAIL startsWith() logic
                if (!tdClass.isBlank()) out.append("            <td class=\"").append(tdClass).append("\">");
                else out.append("            <td>");

                out.append(escapeHtml(cellText)).append("</td>\n");
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

    private static String tagCssClasses(HtmlNode node) {
        if (node == null || node.tags == null || node.tags.isEmpty()) return "";

        StringBuilder out = new StringBuilder();

        for (String tag : node.tags) {
            String cls = cssClassForTag(tag);
            if (!cls.isBlank()) {
                out.append(" tag-").append(cls);
            }
        }

        return out.toString();
    }

    private static String cssClassForTag(String tag) {
        if (tag == null || tag.isBlank()) return "";

        String s = stripTagsForSort(tag)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");

        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static void renderNode(StringBuilder out, HtmlNode node, int depth) {
        String indentClass = "d" + Math.min(depth, 6);
        String tagClasses = tagCssClasses(node);

        out.append("<div class=\"node ")
                .append(indentClass)
                .append(tagClasses)
                .append("\">");

        boolean hasDetails = !node.children.isEmpty()
                || !node.attachments.isEmpty()
                || !node.logs.isEmpty();

        if (hasDetails) {
            out.append("<details open>");
        }

        out.append("<summary class=\"nodeHeader ")
                .append(headerBgClass(node))
                .append(' ')
                .append(statusHeaderCss(node.status))
                .append(' ')
                .append(levelHeaderCss(node.level))
                .append("\">");

        out.append("<div class=\"hdrLeft\">");

        out.append("<div class=\"hdrTitle\">")
                .append(node.title)
                .append("</div>");

        out.append("<div class=\"hdrMeta\">")
                .append(escapeHtml(headerMeta(node)))
                .append("</div>");

        out.append("</div>");

        out.append("<div class=\"hdrRight\">");

        // Badge order intentionally matches the header gradient layout:
        // Status is represented on the LEFT side of the header, Level on the RIGHT side.
        if (node.status != null) {
            out.append("<span class=\"badge statusBadge ")
                    .append(mapStatusCss(node.status))
                    .append("\">")
                    .append(escapeHtml(labelForStatus(node.status)))
                    .append("</span>");
        }

        if (node.level != null) {
            out.append("<span class=\"badge levelBadge ")
                    .append(mapLevelCss(node.level))
                    .append("\">")
                    .append(escapeHtml(node.level.name()))
                    .append("</span>");
        }

        out.append("</div>");

        out.append("</summary>");

        if (hasDetails) {
            out.append("<div class=\"details\">");

            // Fields and tags are intentionally NOT rendered visibly.
            // They remain available internally through node.fields/node.tags.

            renderTimeline(out, node);

            if (!node.children.isEmpty()) {
                out.append("<div class=\"kids\">");
                for (HtmlNode child : node.children) {
                    renderNode(out, child, depth + 1);
                }
                out.append("</div>");
            }

            out.append("</div>");
            out.append("</details>");
        }

        out.append("</div>\n");
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
        return "bgNormal";
    }

    private static String statusHeaderCss(Status status) {
        if (status == null) return "status-unknown";
        return switch (status) {
            case PASS -> "status-pass";
            case FAIL -> "status-fail";
            case SKIP -> "status-skip";
            case WARN -> "status-warn";
            case INFO -> "status-info";
            case UNKNOWN -> "status-unknown";
        };
    }

    private static String levelHeaderCss(Level level) {
        if (level == null) return "level-none";
        return switch (level) {
            case ERROR -> "level-error";
            case WARN -> "level-warn";
            case INFO -> "level-info";
            case DEBUG -> "level-debug";
            case TRACE -> "level-trace";
        };
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
            case ERROR -> "level-error-badge";
            case WARN -> "level-warn-badge";
            case INFO -> "level-info-badge";
            case DEBUG -> "level-debug-badge";
            case TRACE -> "level-trace-badge";
        };
    }

    private enum Phase {START, TIMESTAMP, STOP}

    private static final class ScopeState {
        String rootId;
        String rowKey;

        // NEW: whether this top-level scope counts in run summary metrics
        boolean includeInSummary = true;

        final Map<String, HtmlNode> nodes = new LinkedHashMap<>();
        final Map<String, Meta> meta = new HashMap<>();
    }

    private static final class Meta {
        int tagsN;
        int fieldsN;
        int attachesN;
        final Set<String> attachmentFingerprints = new LinkedHashSet<>();
    }

    private static final class HtmlNode {
        final String id;
        final String title;
        final String parentId;

        Status status;
        Level level;

        Instant startedAt;
        Instant stoppedAt;
        Instant timestampedAt;

        List<String> tags = List.of();
        Map<String, String> fields = Map.of();
        final List<InlineImage> attachments = new ArrayList<>();
        final List<LogLine> logs = new ArrayList<>();
        final List<HtmlNode> children = new ArrayList<>();

        int nextEventOrder = 0;

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
            c.nextEventOrder = this.nextEventOrder;
            return c;
        }
    }

    private static final class LogLine {
        final String ts;
        final String cssClass;
        final String msg;
        final long sortKey;
        final int order;

        private LogLine(String ts, String cssClass, String msg, long sortKey, int order) {
            this.ts = ts;
            this.cssClass = cssClass;
            this.msg = msg;
            this.sortKey = sortKey;
            this.order = order;
        }

        static LogLine of(Instant at, String cssClass, String msg, int order) {
            return new LogLine(
                    escapeHtml(tsOf(at)),
                    cssClass == null ? "info" : cssClass,
                    sanitizeLogText(msg),
                    sortKeyOf(at),
                    order
            );
        }
    }

    private static final class InlineImage {
        final String name;
        final boolean isImage;
        final String src;
        final String text;
        final String ts;
        final long sortKey;
        final int order;

        private InlineImage(String name, boolean isImage, String src, String text, String ts, long sortKey, int order) {
            this.name = name;
            this.isImage = isImage;
            this.src = src;
            this.text = text;
            this.ts = ts;
            this.sortKey = sortKey;
            this.order = order;
        }

        static InlineImage image(String name, String dataUri, Instant at, int order) {
            return new InlineImage(
                    escapeHtml(name),
                    true,
                    dataUri,
                    null,
                    escapeHtml(tsOf(at)),
                    sortKeyOf(at),
                    order
            );
        }

        static InlineImage textOnly(String name, String text, Instant at, int order) {
            return new InlineImage(
                    escapeHtml(name),
                    false,
                    null,
                    text,
                    escapeHtml(tsOf(at)),
                    sortKeyOf(at),
                    order
            );
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
            out.append("    <table class=\"summaryTable summaryTable--single\">\n");

            // single scenario => single row
            renderSummaryTable(out, rd.headers(), List.of(rd.valuesByHeader()));

            out.append("    </table>\n");
            out.append("  </div>\n");
        });
    }

    private static void renderSummaryTable(
            StringBuilder out,
            List<String> summaryHeaders,
            List<Map<String, Object>> rows
    ) {
        // Headers (sole source of truth; order preserved)
        out.append("      <thead><tr>\n");
        for (String h : summaryHeaders) {
            out.append("        <th>").append(escapeHtml(h)).append("</th>\n");
        }
        out.append("      </tr></thead>\n");

        // Rows
        out.append("      <tbody>\n");
        for (Map<String, Object> row : rows) {
            out.append("        <tr>\n");
            for (String h : summaryHeaders) {
                Object v = (row == null) ? null : row.get(h);
                String cellText = (v == null) ? "" : String.valueOf(v);

                String tdClass = summaryCellClass(h, cellText);
                if (!tdClass.isBlank()) {
                    out.append("          <td class=\"").append(tdClass).append("\">");
                } else {
                    out.append("          <td>");
                }

                out.append(escapeHtml(cellText));
                out.append("</td>\n");
            }
            out.append("        </tr>\n");
        }
        out.append("      </tbody>\n");
    }

    private static String summaryCellClass(String header, String value) {
        if (header == null) return "";
        if (!"STATUS".equalsIgnoreCase(header.trim())) return "";

        String v = (value == null) ? "" : value.trim().toUpperCase(Locale.ROOT);

        if (v.startsWith("PASS")) return "statusCell pass";
        if (v.startsWith("FAIL")) return "statusCell fail";
        return "statusCell";
    }


    private static void rebuildChildrenLinks(ScopeState s) {
        if (s == null) return;
        for (HtmlNode n : s.nodes.values()) n.children.clear();
        for (HtmlNode n : s.nodes.values()) {
            if (n.parentId != null) {
                HtmlNode parent = s.nodes.get(n.parentId);
                if (parent != null) parent.children.add(n);
            }
        }
    }

    private static void renderExcludedScopesUnderMain(StringBuilder out, List<ScopeState> excluded) {
        if (excluded == null || excluded.isEmpty()) return;

        out.append("  <div class=\"excludedSection\">\n");
        out.append("    <div class=\"excludedHeader\">\n");
        out.append("      <div class=\"excludedTitle\">General Logs</div>\n");
        out.append("      <div class=\"excludedHint\">Logs not associated with a scenario (excluded scopes).</div>\n");
        out.append("    </div>\n");
        out.append("    <div class=\"tree\">\n");

        int idx = 0;
        for (ScopeState s : excluded) {
            if (s == null) continue;
            HtmlNode root = s.nodes.get(s.rootId);
            if (root == null) continue;

            // Ensure tree relationships exist
            rebuildChildrenLinks(s);
            collapseRedundantEchoNodes(root);
            // Optional: wrap each excluded scope in a container so it’s visually separated
            out.append("      <div class=\"excludedBlock\">\n");
            out.append("        <div class=\"excludedBlockTitle\">")
                    .append(escapeHtml(root.title == null || root.title.isBlank() ? ("General Log " + (++idx)) : root.title))
                    .append("</div>\n");
            renderNode(out, root, 0);
            out.append("      </div>\n");
        }

        out.append("    </div>\n");
        out.append("  </div>\n");
    }


    private static final String CSS = """
            :root {
              --bg: #ffffff;
              --fg: #111827;
              --muted: #6b7280;
              --card: #f9fafb;
              --line: #e5e7eb;
              --pill: #eef2ff;
              --pass: #047857;
              --fail: #b91c1c;
              --warn: #ca8a04;
              --info: #2563eb;
              --skip: #64748b;
              --unknown: #7c3aed;
              --debug: #9333ea;
              --trace: #0f766e;
              --main: #1d4ed8;

              --status-pass: #22c55e;
              --status-fail: #ef4444;
              --status-warn: #facc15;
              --status-info: #3b82f6;
              --status-skip: #94a3b8;
              --status-unknown: #a855f7;

              --level-error: #7f1d1d;
              --level-warn: #eab308;
              --level-info: #2563eb;
              --level-debug: #9333ea;
              --level-trace: #0f766e;

              --status-pass-wash: rgba(34, 197, 94, 0.28);
              --status-fail-wash: rgba(239, 68, 68, 0.34);
              --status-warn-wash: rgba(250, 204, 21, 0.42);
              --status-info-wash: rgba(59, 130, 246, 0.25);
              --status-skip-wash: rgba(148, 163, 184, 0.28);
              --status-unknown-wash: rgba(168, 85, 247, 0.25);

              --level-error-wash: rgba(127, 29, 29, 0.22);
              --level-warn-wash: rgba(234, 179, 8, 0.28);
              --level-info-wash: rgba(37, 99, 235, 0.20);
              --level-debug-wash: rgba(147, 51, 234, 0.21);
              --level-trace-wash: rgba(15, 118, 110, 0.21);

              --hdrNormal: #f9fafb;
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
            .node {
              margin: var(--node-margin-y, 10px) 0;
              font-size: var(--node-font-size, 12px);
              opacity: var(--node-opacity, 1);
            }
            
            .node details {
              border: var(--node-border-width, 1px) solid var(--node-border-color, var(--line));
              border-radius: var(--node-radius, 12px);
              background: #fff;
              overflow: hidden;
              box-shadow: var(--node-shadow, none);
            }
            
            /* Reset summary defaults cleanly */
            .node summary {
              list-style: none;
              cursor: pointer;
            }
            .node summary::-webkit-details-marker { display: none; }
            
            .nodeHeader {
              position: relative;
              display: flex;
              align-items: flex-start;
              justify-content: space-between;
              gap: 12px;
              padding: var(--header-padding-y, 10px) var(--header-padding-x, 12px);
              border-bottom: 1px solid var(--line);
              border-left: 10px solid var(--status-accent, #cbd5e1);
              border-right: 10px solid var(--level-accent, #cbd5e1);
              background:
                linear-gradient(90deg,
                  var(--status-wash, transparent) 0%,
                  rgba(255,255,255,0.04) 48%,
                  rgba(255,255,255,0.04) 52%,
                  var(--level-wash, transparent) 100%),
                var(--tag-header-bg, var(--hdrNormal));
              color: var(--tag-header-color, #374151);
            }
            .nodeHeader.bgNormal {
              background:
                linear-gradient(90deg,
                  var(--status-wash, transparent) 0%,
                  rgba(255,255,255,0.04) 48%,
                  rgba(255,255,255,0.04) 52%,
                  var(--level-wash, transparent) 100%),
                var(--tag-header-bg, var(--hdrNormal));
            }
            
            /* Status is the left-side accent and left wash. */
            .nodeHeader.status-pass { --status-accent: var(--status-pass); --status-wash: var(--status-pass-wash); }
            .nodeHeader.status-fail { --status-accent: var(--status-fail); --status-wash: var(--status-fail-wash); }
            .nodeHeader.status-warn { --status-accent: var(--status-warn); --status-wash: var(--status-warn-wash); }
            .nodeHeader.status-info { --status-accent: var(--status-info); --status-wash: var(--status-info-wash); }
            .nodeHeader.status-skip { --status-accent: var(--status-skip); --status-wash: var(--status-skip-wash); }
            .nodeHeader.status-unknown { --status-accent: var(--status-unknown); --status-wash: var(--status-unknown-wash); }
            
            /* Level is the right-side accent and right wash. */
            .nodeHeader.level-error { --level-accent: var(--level-error); --level-wash: var(--level-error-wash); }
            .nodeHeader.level-warn { --level-accent: var(--level-warn); --level-wash: var(--level-warn-wash); }
            .nodeHeader.level-info { --level-accent: var(--level-info); --level-wash: var(--level-info-wash); }
            .nodeHeader.level-debug { --level-accent: var(--level-debug); --level-wash: var(--level-debug-wash); }
            .nodeHeader.level-trace { --level-accent: var(--level-trace); --level-wash: var(--level-trace-wash); }
            .nodeHeader.level-none { --level-accent: #cbd5e1; --level-wash: transparent; }
            
            .nodeHeader.status-fail { box-shadow: inset 0 0 0 1px rgba(239, 68, 68, 0.55); }
            .nodeHeader.level-error { outline: 1px solid rgba(127, 29, 29, 0.28); outline-offset: -2px; }
            
            .hdrLeft { min-width: 0; }
            .hdrTitle {
              font-weight: var(--title-weight, 700);
              font-size: var(--header-font-size, 13px);
              word-break: break-word;
              letter-spacing: var(--title-letter-spacing, normal);
              text-transform: var(--title-transform, none);
              font-family: var(--title-font-family, inherit);
              text-align: var(--title-align, left);
            }
            .hdrMeta { margin-top: 4px; font-size: 12px; color: var(--muted); }
            
            .hdrRight { display: flex; gap: 6px; flex-wrap: wrap; justify-content: flex-end; }
            
            .details {
              padding: var(--details-padding-y, 10px) var(--details-padding-x, 12px) var(--details-padding-bottom, 12px) var(--details-padding-x, 12px);
              background: #fff;
            }
            
            /* ---------------- Tag-driven base category presentation ---------------- */
            .node.tag-global,
            .node.tag-runlog {
              --node-font-size: 15px;
              --header-font-size: 22px;
              --node-border-color: #2563eb;
              --node-border-width: 4px;
              --tag-header-bg: #dbeafe;
              --tag-header-color: #1e3a8a;
            }
            
            .node.tag-scenario {
              --node-font-size: 14px;
              --header-font-size: 19px;
              --node-border-color: #7c3aed;
              --node-border-width: 3px;
              --tag-header-bg: #ede9fe;
              --tag-header-color: #4c1d95;
            }
            
            .node.tag-step {
              --node-font-size: 13px;
              --header-font-size: 16px;
              --node-border-color: #f59e0b;
              --node-border-width: 2px;
              --tag-header-bg: #fffbeb;
              --tag-header-color: #78350f;
            }
            
            .node.tag-phrase {
              --node-font-size: 12px;
              --header-font-size: 14px;
              --node-border-color: #10b981;
              --node-border-width: 1px;
              --tag-header-bg: #ecfdf5;
              --tag-header-color: #064e3b;
            }
            
            .node.tag-screenshot {
              --node-font-size: 12px;
              --header-font-size: 14px;
              --node-border-color: #0ea5e9;
              --node-border-width: 2px;
              --tag-header-bg: #e0f2fe;
              --tag-header-color: #075985;
            }
            
            /* ---------------- Additive general-purpose visual profile tags ----------------
               These are named after visual effects, not entry meaning.
               They adjust properties that the category tags above do not own, so they compose cleanly. */
            .node.tag-compact {
              --node-margin-y: 5px;
              --header-padding-y: 6px;
              --header-padding-x: 8px;
              --details-padding-y: 6px;
              --details-padding-x: 8px;
              --details-padding-bottom: 8px;
            }
            .node.tag-spacious {
              --node-margin-y: 16px;
              --header-padding-y: 14px;
              --header-padding-x: 16px;
              --details-padding-y: 14px;
              --details-padding-x: 16px;
              --details-padding-bottom: 16px;
            }
            .node.tag-rounded { --node-radius: 18px; }
            .node.tag-sharp { --node-radius: 4px; }
            .node.tag-elevated { --node-shadow: 0 8px 22px rgba(15, 23, 42, 0.12); }
            .node.tag-flat { --node-shadow: none; }
            .node.tag-bold-title { --title-weight: 850; }
            .node.tag-wide-title { --title-letter-spacing: 0.04em; }
            .node.tag-uppercase-title { --title-transform: uppercase; }
            .node.tag-monospace-title { --title-font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace; }
            .node.tag-center-title { --title-align: center; }
            .node.tag-muted-visual { --node-opacity: 0.78; }
            
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
            .badge.warn { color: #854d0e; border-color: #fde047; background: #fef9c3; }
            .badge.skip { color: var(--skip); border-color: #cbd5e1; background: #f8fafc; }
            .badge.info { color: var(--info); border-color: #bfdbfe; background: #eff6ff; }
            .badge.muted { color: var(--muted); border-color: #e5e7eb; background: #fff; }
            .badge.statusBadge { border-left: 5px solid var(--status-accent, #cbd5e1); }
            .badge.levelBadge { border-right: 5px solid var(--level-accent, #cbd5e1); }
            .badge.level-error-badge { color: var(--level-error); border-color: #fecaca; background: #fff1f2; }
            .badge.level-warn-badge { color: #713f12; border-color: #facc15; background: #fefce8; }
            .badge.level-info-badge { color: var(--level-info); border-color: #bfdbfe; background: #eff6ff; }
            .badge.level-debug-badge { color: var(--level-debug); border-color: #e9d5ff; background: #faf5ff; }
            .badge.level-trace-badge { color: var(--level-trace); border-color: #99f6e4; background: #f0fdfa; }
            
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
                height: 120px;
                max-width: 240px;
                object-fit: contain;
                border-radius: 6px;
                border: 1px solid var(--line);
                background: #fff;
                display: block;
                cursor: pointer;
            }
            
            /* Lightbox overlay for full-size screenshot */
            .imgOverlay {
                position: fixed;
                top: 0; left: 0; right: 0; bottom: 0;
                background: rgba(0,0,0,.75);
                display: flex;
                align-items: center;
                justify-content: center;
                z-index: 99999;
                cursor: pointer;
            }
            
            .imgOverlay img {
                max-width: 92vw;
                max-height: 92vh;
                object-fit: contain;
                border-radius: 10px;
                box-shadow: 0 8px 32px rgba(0,0,0,.5);
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
            
            
            /* Summary tables (shared by main + scenario tabs) */
            .summaryTable { width: 100%; border-collapse: collapse; font-size: 13px; }
            .summaryTable th, .summaryTable td {
              border: 1px solid #e0e0e0;
              padding: 6px 8px;
              vertical-align: top;
            }
            .summaryTable th { background: #f2f2f2; text-align: left; }
            
            /* STATUS cell indicators */
            .statusCell {
              font-weight: 700;
              white-space: nowrap;
            }
            .statusCell.pass {
              color: var(--pass);
              background: #ecfdf5;
            }
            .statusCell.fail {
              color: var(--fail);
              background: #fef2f2;
            }
            .statusCell.pass::before {
              content: "✓ ";
            }
            .statusCell.fail::before {
              content: "✕ ";
            }
            
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
            
            /* MAIN tab controls */
            .overviewControls {
              display: flex;
              gap: 12px;
              align-items: center;
              justify-content: space-between;
              padding: 10px 12px 0 12px;
            }
            .statusRadios {
              display: flex;
              gap: 10px;
              align-items: center;
              flex-wrap: wrap;
              font-size: 12px;
              color: var(--fg);
            }
            .statusRadios label { display: inline-flex; gap: 6px; align-items: center; }
            
            /* Make header row look clickable for sorting */
            .summaryTable--multi thead tr.hdrRow th.sortable {
              cursor: pointer;
              user-select: none;
              position: sticky;
              top: 0;
              z-index: 6;
            }
            .summaryTable--multi thead tr.hdrRow th.sortable:hover {
              filter: brightness(0.98);
            }
            
            /* Visual sort indicator using data-sort */
            .summaryTable--multi thead tr.hdrRow th[data-sort="asc"]::after  { content: " ▲"; color: var(--muted); }
            .summaryTable--multi thead tr.hdrRow th[data-sort="desc"]::after { content: " ▼"; color: var(--muted); }
            
            /* Filter row sticks below header row */
            .summaryTable--multi thead tr.filterRow th {
              position: sticky;
              top: 34px;          /* approx height of header row; adjust if needed */
              z-index: 5;
              background: #fff;
            }
            .colFilter {
              width: 100%;
              font-size: 12px;
              padding: 6px 8px;
              border: 1px solid var(--line);
              border-radius: 8px;
            }
            
            /* Link column */
            .linkCol { width: 42px; min-width: 42px; max-width: 42px; text-align: center; }
            .jumpBtn {
              cursor: pointer;
              border: 1px solid var(--line);
              background: #fff;
              border-radius: 10px;
              padding: 4px 8px;
              font-size: 12px;
            }
            .jumpBtn:hover { filter: brightness(0.98); }
            
            /* Optional subtle row tint for quick scan */
            .summaryTable--multi tbody tr.rowPass td { background: #fcfffd; }
            .summaryTable--multi tbody tr.rowFail td { background: #fffdfd; }
            
            /* STATUS cell indicators (you already have these, keep/merge) */
            .statusCell { font-weight: 700; white-space: nowrap; }
            .statusCell.pass { color: var(--pass); background: #ecfdf5; }
            .statusCell.fail { color: var(--fail); background: #fef2f2; }
            .statusCell.pass::before { content: "✓ "; }
            .statusCell.fail::before { content: "✕ "; }
            /* ---------------- Excluded / General logs under MAIN ---------------- */
            .excludedSection {
              margin-top: 14px;
              border-top: 1px dashed var(--line);
              padding-top: 12px;
            }
            .excludedHeader {
              display: flex;
              align-items: baseline;
              justify-content: space-between;
              gap: 10px;
              padding: 0 12px 8px 12px;
            }
            .excludedTitle { font-weight: 800; }
            .excludedHint { color: var(--muted); font-size: 12px; }
            .excludedBlock {
              margin: 10px 12px 14px 12px;
              padding: 10px;
              border: 1px solid var(--line);
              border-radius: 10px;
              background: #fff;
            }
            .excludedBlockTitle {
              font-weight: 700;
              margin-bottom: 8px;
              color: var(--info);
            }
            .timelineAttachment {
              align-items: flex-start;
            }
            .timelineAttachment .msgBlock {
              display: block;
              min-width: 0;
              width: 100%;
            }
            .timelineAttachment .msgLabel {
              font-weight: 700;
              margin-bottom: 8px;
              word-break: break-word;
            }
            .timelineAttachment .imgDetails {
              max-width: 480px;
            }
            .timelineAttachment .imgThumb {
              height: 120px;
              max-width: 240px;
            }
            .timelineAttachment .attText {
              padding: 0;
              border: 0;
              background: transparent;
            }
            """;


    private static Instant timeForPhase(Entry entry, Phase phase) {
        if (phase == Phase.START) {
            return entry.startedAt != null ? entry.startedAt : Instant.now();
        }
        if (phase == Phase.STOP) {
            return entry.stoppedAt != null ? entry.stoppedAt : Instant.now();
        }
        return entry.timestampedAt != null ? entry.timestampedAt : Instant.now();
    }

    private static long sortKeyOf(Instant at) {
        return at == null ? Long.MAX_VALUE : at.toEpochMilli();
    }

    private static String displayAttachmentLabel(String rawName, boolean image) {
        String cleaned = sanitizeAttachmentName(rawName);
        if (image) cleaned = stripKnownImageExtension(cleaned);
        if (cleaned == null || cleaned.isBlank()) return image ? "Screenshot" : "Attachment";
        return cleaned;
    }

    private static String stripKnownImageExtension(String s) {
        if (s == null) return "";
        String t = s.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return t.substring(0, t.length() - 4);
        if (lower.endsWith(".jpg")) return t.substring(0, t.length() - 4);
        if (lower.endsWith(".jpeg")) return t.substring(0, t.length() - 5);
        if (lower.endsWith(".gif")) return t.substring(0, t.length() - 4);
        if (lower.endsWith(".bmp")) return t.substring(0, t.length() - 4);
        if (lower.endsWith(".webp")) return t.substring(0, t.length() - 5);
        return t;
    }

    private static void renderTimeline(StringBuilder out, HtmlNode node) {
        int total = node.logs.size() + node.attachments.size();
        if (total == 0) return;

        List<Object> items = new ArrayList<>(total);
        items.addAll(node.logs);
        items.addAll(node.attachments);

        items.sort((a, b) -> {
            long ak = timelineSortKey(a);
            long bk = timelineSortKey(b);
            if (ak != bk) return Long.compare(ak, bk);

            int ao = timelineOrder(a);
            int bo = timelineOrder(b);
            return Integer.compare(ao, bo);
        });

        out.append("<div class=\"row\"><span class=\"v\">");
        out.append("<div class=\"log\">");

        for (Object item : items) {
            if (item instanceof LogLine line) {
                out.append("<div class=\"logLine ").append(line.cssClass).append("\">");
                out.append("<span class=\"ts\">").append(line.ts).append("</span>");
                out.append("<span class=\"msg\">").append(line.msg).append("</span>");
                out.append("</div>");
                continue;
            }

            InlineImage a = (InlineImage) item;
            out.append("<div class=\"logLine info timelineAttachment\">");
            out.append("<span class=\"ts\">").append(a.ts).append("</span>");
            out.append("<div class=\"msg msgBlock\">");

            out.append("<div class=\"msgLabel\">").append(a.name).append("</div>");

            if (a.isImage && a.src != null && !a.src.isBlank()) {
                out.append("<img class=\"imgThumb\" alt=\"")
                        .append(a.name)
                        .append("\" src=\"")
                        .append(a.src)
                        .append("\" onclick=\"(function(s){var o=document.createElement('div');o.className='imgOverlay';o.onclick=function(){o.remove()};var i=document.createElement('img');i.src=s;o.appendChild(i);document.body.appendChild(o)})(this.src)\">");
            } else {
                out.append("<div class=\"attText\">").append(a.text == null ? "" : a.text).append("</div>");
            }

            out.append("</div>");
            out.append("</div>");
        }

        out.append("</div>");
        out.append("</span></div>");
    }

    private static long timelineSortKey(Object o) {
        if (o instanceof LogLine l) return l.sortKey;
        return ((InlineImage) o).sortKey;
    }

    private static int timelineOrder(Object o) {
        if (o instanceof LogLine l) return l.order;
        return ((InlineImage) o).order;
    }

    private static void collapseRedundantEchoNodes(HtmlNode parent) {
        if (parent == null || parent.children.isEmpty()) return;

        List<HtmlNode> kept = new ArrayList<>();

        for (HtmlNode child : parent.children) {
            collapseRedundantEchoNodes(child);

            if (isRedundantEchoChild(parent, child)) {
                mergeEchoChildIntoParent(parent, child);
            } else {
                kept.add(child);
            }
        }

        parent.children.clear();
        parent.children.addAll(kept);
    }



    private static boolean isRedundantEchoChild(HtmlNode parent, HtmlNode child) {
        if (parent == null || child == null) return false;

        String p = normalizeNodeTitle(parent.title);
        String c = normalizeNodeTitle(child.title);

        if (!p.equals(c)) return false;

        boolean childIsPlainEvent = child.startedAt == null && child.stoppedAt == null;
        boolean childHasNoExtraMeta = (child.tags == null || child.tags.isEmpty())
                && (child.fields == null || child.fields.isEmpty());

        return childIsPlainEvent && childHasNoExtraMeta;
    }

    private static void mergeEchoChildIntoParent(HtmlNode parent, HtmlNode child) {
        parent.logs.addAll(child.logs);
        parent.attachments.addAll(child.attachments);
        parent.children.addAll(child.children);
    }

    private static String normalizeNodeTitle(String s) {
        return stripTagsForSort(s == null ? "" : s).replaceAll("\\s+", " ").trim();
    }

    private static String attachmentFingerprint(Attachment a) {
        if (a == null) return "null";

        String name = a.name() == null ? "" : a.name().trim();
        String mime = a.mime() == null ? "" : a.mime().trim();
        String path = a.path() == null ? "" : a.path().trim();

        String payloadKey;
        if (path.length() > 256) {
            payloadKey = Integer.toHexString(path.hashCode()) + ":" + path.length();
        } else {
            payloadKey = path;
        }

        return name + "|" + mime + "|" + payloadKey;
    }


}