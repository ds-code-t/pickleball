// file: tools/dscode/common/reporting/logging/simplehtml/SimpleHtmlReportConverter.java
package tools.dscode.common.reporting.logging.simplehtml;

import tools.dscode.common.reporting.logging.Attachment;
import tools.dscode.common.reporting.logging.BaseConverter;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import io.cucumber.core.runner.GlobalState;
import tools.dscode.common.reporting.logging.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A minimal, single-file HTML report generator:
 * - embeds CSS (no CDN)
 * - embeds screenshots (base64 -> data: URIs)
 * - escapes all user-provided text to avoid HTML breakage / injection
 *
 * Mirrors the design pattern of ExtentReportConverter:
 * - scope state map
 * - ensureNode(...) creation
 * - applyMetaOncePerChange(...) incremental metadata attach
 * - emit(...) called from onStart/onTimestamp/onStop
 */
public final class SimpleHtmlReportConverter extends BaseConverter {

    private final Object lock = new Object();

    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();
    private final Path reportFile;

    // Guards to prevent giant/bloated emails
    private static final int MAX_TEXT_LEN = 20_000;
    private static final int MAX_INLINE_IMAGE_BYTES = 3_000_000; // ~3MB per image (before base64)
    private static final int MAX_LOG_LINES_PER_NODE = 5_000;

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                    .withZone(ZoneId.systemDefault());

    public SimpleHtmlReportConverter(Path reportFile) {
        this.reportFile = reportFile;
    }

    @Override
    protected void onClose() {
        synchronized (lock) {
            try {
                writeReport();
                scopes.values().forEach(s -> {
                    if (s.rowKey != null) GlobalState.registerScenarioHtml(s.rowKey, reportFile);                });
            } catch (IOException e) {
                // last-resort fallback
                System.err.println("[SimpleHtmlReportConverter] FAILED writing report: " + e);
            }
        }
    }


    private Status computeOverallStatus() {
        for (ScopeState s : scopes.values()) {
            for (HtmlNode n : s.nodes.values()) {
                if (n.status == Status.FAIL) {
                    return Status.FAIL;
                }
            }
        }
        return Status.PASS;
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
            applyMetaOncePerChange(s, entry, node);

            if (phase == Phase.TIMESTAMP) {
                // Similar to Extent: log a marker with status (or INFO).
                Status st = (entry.status != null) ? entry.status : Status.INFO;
                node.addLogLine(LogLine.of(now(), mapStatusCss(st), labelForStatus(st)));
            }

            if (phase == Phase.STOP && entry.status != null) {
                node.status = entry.status;
                node.addLogLine(LogLine.of(now(), mapStatusCss(entry.status), "STOP: " + labelForStatus(entry.status)));
            }

            if (entry.level != null && entry.level != node.level) {
                node.level = entry.level;
            }
        }
    }

    private ScopeState initScope(Entry scope) {
        ScopeState s = new ScopeState();
        s.rootId = scope.id;
        s.rowKey = GlobalState.getCurrentScenarioState().id.toString(); // <-- CAPTURE REAL XLSX ROW KEY

        HtmlNode root = new HtmlNode(scope.id, sanitizeNodeName(scope.text), null);
        s.nodes.put(scope.id, root);
        return s;
    }

    // lock must be held
    private HtmlNode ensureNode(ScopeState s, Entry scope, Entry entry) {
        HtmlNode existing = s.nodes.get(entry.id);
        if (existing != null) return existing;

        // If this entry is the scope root, reuse root
        if (safeEquals(entry.id, scope.id)) return s.nodes.get(s.rootId);

        // Ensure parent exists if it is in scope
        if (entry.parent != null && isInScope(scope, entry.parent)) {
            ensureNode(s, scope, entry.parent);
        }

        String parentId =
                (entry.parent != null && isInScope(scope, entry.parent))
                        ? entry.parent.id
                        : s.rootId;

        HtmlNode node = new HtmlNode(entry.id, sanitizeNodeName(entry.text), parentId);
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

        // Attach only newly-added attachments (by index)
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
                // For non-images, keep it as text
                node.attachments.add(InlineImage.textOnly(name, sanitizeLogText(String.valueOf(data))));
            }
        }
        m.attachesN = attachesN;
    }

    private InlineImage inlineImage(String name, String mime, String data) {
        try {
            // Case 1: already base64 in the "mime" (your BaseConverter uses "image/png;base64")
            if (mime.contains("base64")) {
                String raw = rawBase64(data);
                if (raw == null || raw.isBlank()) {
                    return InlineImage.textOnly(name, sanitizeLogText("<empty base64>"));
                }
                // Store a data URI
                String mediaType = mime.substring(0, mime.indexOf(';') >= 0 ? mime.indexOf(';') : mime.length());
                String src = "data:" + mediaType + ";base64," + raw;
                return InlineImage.image(name, src);
            }

            // Case 2: data is already a data-uri
            String trimmed = (data == null) ? "" : data.trim();
            if (trimmed.startsWith("data:image")) {
                return InlineImage.image(name, trimmed);
            }

            // Case 3: treat as a file path and inline it
            if (trimmed.isBlank()) {
                return InlineImage.textOnly(name, sanitizeLogText("<empty path>"));
            }

            Path p = Path.of(trimmed);
            if (!Files.exists(p)) {
                // Sometimes callers pass relative to report directory; try that too
                Path maybeRel = reportFile.getParent() == null ? p : reportFile.getParent().resolve(trimmed);
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

    private void writeReport() throws IOException {
        if (reportFile.getParent() != null) {
            Files.createDirectories(reportFile.getParent());
        }

        String html = renderHtmlDocument();
        Files.writeString(reportFile, html, StandardCharsets.UTF_8);

        System.out.println("[SimpleHtmlReportConverter] wrote: " + reportFile.toAbsolutePath());
    }

    private String renderHtmlDocument() {
        StringBuilder out = new StringBuilder(256_000);

        out.append("<!doctype html>\n");
        out.append("<html lang=\"en\">\n<head>\n");
        out.append("  <meta charset=\"utf-8\">\n");
        out.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        out.append("  <title>").append(escapeHtml(reportFile.getFileName().toString())).append("</title>\n");
        out.append("  <style>\n").append(CSS).append("\n  </style>\n");
        out.append("</head>\n<body>\n");

        Status overall = computeOverallStatus();
        String overallCss = (overall == Status.FAIL) ? "fail" : "pass";
        String overallLabel = (overall == Status.FAIL) ? "FAIL" : "PASS";

        out.append("<header class=\"top\">\n");
        out.append("  <div class=\"headerRow\">\n");
        out.append("    <div>\n");
        out.append("      <div class=\"title\">Test Report</div>\n");
        out.append("      <div class=\"subtitle\">Generated ")
                .append(escapeHtml(TS_FMT.format(Instant.now())))
                .append("</div>\n");
        out.append("    </div>\n");
        out.append("    <div class=\"overallBadge ").append(overallCss).append("\">")
                .append(overallLabel)
                .append("</div>\n");
        out.append("  </div>\n");
        out.append("</header>\n");


        // Render each scope as its own section
        List<ScopeState> scopeStates = new ArrayList<>(scopes.values());
        scopeStates.sort(Comparator.comparing(s -> s.rootId == null ? "" : s.rootId));

        for (ScopeState s : scopeStates) {
            HtmlNode root = s.nodes.get(s.rootId);
            if (root == null) continue;

            // Build children lists
            for (HtmlNode n : s.nodes.values()) {
                n.children.clear();
            }
            for (HtmlNode n : s.nodes.values()) {
                if (n.parentId != null) {
                    HtmlNode parent = s.nodes.get(n.parentId);
                    if (parent != null) parent.children.add(n);
                }
            }

            // Deterministic order: by creation/order-insensitive fallback on title+id
            sortTree(root);

            out.append("<section class=\"scope\">\n");
            out.append("  <div class=\"scopeHeader\">\n");
            out.append("    <div class=\"scopeName\">").append(root.title).append("</div>\n");
            out.append("    <div class=\"scopeMeta\">Nodes: ").append(s.nodes.size()).append("</div>\n");
            out.append("  </div>\n");

            renderScenarioSummary(out, s.rowKey);

            out.append("  <div class=\"tree\">\n");
            renderNode(out, root, 0);
            out.append("  </div>\n");
            out.append("</section>\n");
        }

        out.append("<footer class=\"foot\">Single-file HTML • No external assets</footer>\n");
        out.append("</body>\n</html>\n");

        return out.toString();
    }

    private static void sortTree(HtmlNode root) {
        Deque<HtmlNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            HtmlNode n = stack.pop();
            n.children.sort(Comparator
                    .comparing((HtmlNode x) -> stripTagsForSort(x.title))
                    .thenComparing(x -> x.id == null ? "" : x.id)
            );
            for (int i = n.children.size() - 1; i >= 0; i--) {
                stack.push(n.children.get(i));
            }
        }
    }

    // because titles are already HTML-escaped; for sorting, make it cheap
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

        // Collapsible if it has children or details
        boolean hasDetails = !node.children.isEmpty()
                || (node.tags != null && !node.tags.isEmpty())
                || (node.fields != null && !node.fields.isEmpty())
                || !node.attachments.isEmpty()
                || !node.logs.isEmpty();

        if (hasDetails) out.append("<details open>");
        out.append("<summary>");

        // Status/level badge (prefer status; else level)
        String badge = badgeText(node);
        String badgeClass = badgeClass(node);
        out.append("<span class=\"badge ").append(badgeClass).append("\">")
                .append(escapeHtml(badge))
                .append("</span>");

        out.append("<span class=\"nodeTitle\">").append(node.title).append("</span>");
        out.append("</summary>");

        if (hasDetails) {
            out.append("<div class=\"details\">");

            // Tags
            if (node.tags != null && !node.tags.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"k\">Tags</span><span class=\"v\">");
                for (String t : node.tags) {
                    out.append("<span class=\"pill\">").append(t).append("</span>");
                }
                out.append("</span></div>");
            }

            // Fields
            if (node.fields != null && !node.fields.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"k\">Fields</span><span class=\"v\">");
                out.append("<table class=\"fields\"><tbody>");
                for (Map.Entry<String, String> e : node.fields.entrySet()) {
                    out.append("<tr><td class=\"fk\">").append(e.getKey())
                            .append("</td><td class=\"fv\">").append(e.getValue())
                            .append("</td></tr>");
                }
                out.append("</tbody></table>");
                out.append("</span></div>");
            }

            // Attachments
            if (!node.attachments.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"k\">Attachments</span><span class=\"v\">");
                out.append("<div class=\"attachments\">");
                for (InlineImage a : node.attachments) {
                    out.append("<div class=\"att\">");
                    out.append("<div class=\"attName\">").append(a.name).append("</div>");
                    if (a.isImage && a.src != null && !a.src.isBlank()) {
                        out.append("<details class=\"imgDetails\">");
                        out.append("<summary class=\"imgSummary\">");
                        out.append("<img class=\"imgThumb\" alt=\"").append(a.name).append("\" src=\"")
                                .append(a.src).append("\">");
                        out.append("<div class=\"imgHint\">Click to enlarge</div>");
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

            // Logs
            if (!node.logs.isEmpty()) {
                out.append("<div class=\"row\"><span class=\"k\">Log</span><span class=\"v\">");
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

            // Children
            if (!node.children.isEmpty()) {
                out.append("<div class=\"kids\">");
                for (HtmlNode child : node.children) {
                    renderNode(out, child, depth + 1);
                }
                out.append("</div>");
            }

            out.append("</div>"); // details
            out.append("</details>");
        }

        out.append("</div>\n"); // node
    }

    private static String badgeText(HtmlNode node) {
        if (node.status != null) return labelForStatus(node.status);
        if (node.level != null) return node.level.name();
        return "INFO";
    }

    private static String badgeClass(HtmlNode node) {
        if (node.status != null) return mapStatusCss(node.status);
        if (node.level != null) return mapLevelCss(node.level);
        return "info";
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

    private static String now() {
        return TS_FMT.format(Instant.now());
    }

    private enum Phase { START, TIMESTAMP, STOP }

    private static final class ScopeState {
        String rootId;
        String rowKey; // <-- NEW
        final Map<String, HtmlNode> nodes = new HashMap<>();
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
    }

    private static final class LogLine {
        final String ts;       // escaped
        final String cssClass; // safe enum-like
        final String msg;      // escaped

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
        final String name;   // escaped
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
            // dataUri must not be HTML-escaped; browsers need it raw
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
        // stable order for deterministic HTML
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

    /**
     * Common sanitation:
     * - Normalize CRLF variants and unicode line separators
     * - Remove control chars except tab/newline/carriage return
     * - Trim extremely long text to avoid massive HTML
     */
    private static String sanitizeCommon(String s) {
        if (s == null) return "";

        String x = s;

        // Normalize some unicode separators to \n
        x = x.replace('\u2028', '\n') // line separator
                .replace('\u2029', '\n') // paragraph separator
                .replace('\u0085', '\n'); // next line

        // Remove control chars except \t \n \r
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

    /**
     * Minimal HTML escaping to prevent user content from breaking the DOM/layout.
     */
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

    // Clean, email-friendly CSS (no external fonts)
    private static final String CSS = """
.headerRow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.overallBadge {
  font-size: 16px;
  font-weight: 800;
  padding: 8px 18px;
  border-radius: 999px;
  border: 2px solid var(--line);
  letter-spacing: 0.5px;
}

.overallBadge.pass {
  color: var(--pass);
  background: #ecfdf5;
  border-color: #34d399;
}

.overallBadge.fail {
  color: var(--fail);
  background: #fef2f2;
  border-color: #f87171;
}




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
}

* { box-sizing: border-box; }
html, body { margin: 0; padding: 0; background: var(--bg); color: var(--fg); font-family: Arial, Helvetica, sans-serif; }
a { color: inherit; }

.top {
  padding: 16px 18px;
  border-bottom: 1px solid var(--line);
  background: #fff;
}
.title { font-size: 18px; font-weight: 700; }
.subtitle { margin-top: 4px; font-size: 12px; color: var(--muted); }

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
.scopeMeta { font-size: 12px; color: var(--muted); }

.tree { padding: 10px; }

.node { margin: 6px 0; }
.node details { border: 1px solid var(--line); border-radius: 10px; background: #fff; }
.node summary {
  list-style: none;
  cursor: pointer;
  padding: 10px 10px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.node summary::-webkit-details-marker { display: none; }
.nodeTitle { font-weight: 600; word-break: break-word; }

.details { padding: 8px 10px 12px 10px; border-top: 1px solid var(--line); background: #fff; }

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

.row { display: flex; gap: 10px; margin-top: 8px; }
.k { width: 90px; flex: 0 0 90px; font-size: 12px; color: var(--muted); padding-top: 2px; }
.v { flex: 1; min-width: 0; }

.pill {
  display: inline-block;
  padding: 2px 8px;
  margin: 0 6px 6px 0;
  border-radius: 999px;
  background: var(--pill);
  border: 1px solid #e0e7ff;
  font-size: 12px;
}

.fields { width: 100%; border-collapse: collapse; }
.fields td { border: 1px solid var(--line); padding: 6px 8px; vertical-align: top; }
.fk { width: 220px; color: var(--muted); font-size: 12px; }
.fv { font-size: 12px; white-space: pre-wrap; word-break: break-word; }

.attachments { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }
.att { border: 1px solid var(--line); border-radius: 10px; background: var(--card); overflow: hidden; }
.attName { padding: 8px 10px; font-size: 12px; font-weight: 700; border-bottom: 1px solid var(--line); background: #fff; }
.imgDetails { border-top: 1px solid var(--line); background: #fff; }
.imgSummary {
  list-style: none;
  cursor: pointer;
  padding: 8px 10px;
  display: grid;
  gap: 6px;
  align-items: start;
}
.imgSummary::-webkit-details-marker { display: none; }

.imgThumb {
  width: 100%;
  max-height: 140px;
  object-fit: contain;
  border-radius: 8px;
  border: 1px solid var(--line);
  background: #fff;
}

.imgHint {
  font-size: 12px;
  color: var(--muted);
}

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

.attText { padding: 8px 10px; font-size: 12px; white-space: pre-wrap; word-break: break-word; color: var(--fg); }

.log { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; background: #fff; }
.logLine { display: flex; gap: 10px; padding: 6px 10px; border-top: 1px solid var(--line); font-size: 12px; }
.logLine:first-child { border-top: none; }
.ts { color: var(--muted); white-space: nowrap; }
.msg { white-space: pre-wrap; word-break: break-word; }
.logLine.pass .msg { color: var(--pass); }
.logLine.fail .msg { color: var(--fail); }
.logLine.warn .msg { color: var(--warn); }
.logLine.skip .msg { color: var(--skip); }

.kids { margin-top: 10px; padding-top: 6px; border-top: 1px dashed var(--line); }

.d0 { margin-left: 0px; }
.d1 { margin-left: 12px; }
.d2 { margin-left: 24px; }
.d3 { margin-left: 36px; }
.d4 { margin-left: 48px; }
.d5 { margin-left: 60px; }
.d6 { margin-left: 72px; }

.foot {
  padding: 14px 18px;
  color: var(--muted);
  font-size: 12px;
}


.scenarioSummary {
  margin: 12px 0 16px 0;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  background: #fafafa;
}
.scenarioSummaryTitle {
  font-weight: 700;
  margin-bottom: 8px;
}
.summaryTable {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.summaryTable th, .summaryTable td {
  border: 1px solid #e0e0e0;
  padding: 6px 8px;
  vertical-align: top;
}
.summaryTable th {
  background: #f2f2f2;
  text-align: left;
}

""";
}



