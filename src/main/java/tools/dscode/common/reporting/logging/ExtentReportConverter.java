package tools.dscode.common.reporting.logging;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.MediaEntityBuilder;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ExtentReportConverter (drop-in)
 *
 * Fixes:
 *  1) Enable Spark offline mode so the JS/CSS required for clickable thumbnails/lightbox is always present.
 *  2) Strip data-uri prefix and pass RAW base64 to Extent.
 *  3) Attach screenshots as *media on the log line* via MediaEntityBuilder so Spark renders a thumbnail
 *     that expands when clicked (instead of a non-clickable paperclip).
 *
 * Notes:
 *  - This class assumes your Attachment#path() carries either:
 *      a) raw base64 ("iVBORw0KGgo..."), OR
 *      b) data-uri ("data:image/png;base64,iVBORw0KGgo...")
 *  - If you also support filesystem paths, it will still handle addScreenCaptureFromPath.
 */
public final class ExtentReportConverter extends BaseConverter {

    private static final boolean DEBUG = true;

    private final Object lock = new Object();

    private final ExtentReports extent = new ExtentReports();
    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();

    private final Path reportFile;
    private static final Object SPARK_ASSETS_LOCK = new Object();
    private static volatile boolean SPARK_ASSETS_READY = false;

    public ExtentReportConverter(Path reportFile) {
        this.reportFile = reportFile;
        dbg("CTOR reportFile=" + reportFile.toAbsolutePath());

        ExtentSparkReporter spark = new ExtentSparkReporter(reportFile.toString());
        spark.config().thumbnailForBase64(true);

        // Write offline spark assets ONCE (thread-safe) even if multiple reports share the directory
        ensureSparkOfflineAssetsOnce(spark, reportFile);

        extent.attachReporter(spark);
        dbg("CTOR attached spark reporter");
    }

    private static void ensureSparkOfflineAssetsOnce(ExtentSparkReporter spark, Path reportFile) {
        // spark assets folder is created next to the HTML file
        Path dir = reportFile.toAbsolutePath().getParent();
        Path sparkDir = dir.resolve("spark");

        if (SPARK_ASSETS_READY) {
            dbgStatic("spark assets already marked ready; skipping enableOfflineMode(true)");
            return;
        }

        synchronized (SPARK_ASSETS_LOCK) {
            if (SPARK_ASSETS_READY) {
                dbgStatic("spark assets already marked ready inside lock; skipping");
                return;
            }

            // If another earlier run (or previous JVM) already created them, we can also skip.
            if (looksLikeSparkAssetsExist(sparkDir)) {
                dbgStatic("spark assets folder already exists and looks complete -> " + sparkDir);
                SPARK_ASSETS_READY = true;
                return;
            }

            dbgStatic("creating spark offline assets once -> " + sparkDir);
            try {
                spark.config().enableOfflineMode(true);
                SPARK_ASSETS_READY = true;
                dbgStatic("spark offline assets created");
            } catch (Throwable t) {
                // If creation fails, do NOT mark ready; later attempt may succeed
                dbgStatic("FAILED to create spark offline assets: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    private static boolean looksLikeSparkAssetsExist(Path sparkDir) {
        try {
            if (sparkDir == null || !java.nio.file.Files.isDirectory(sparkDir)) return false;

            // Minimal checks: the key JS/CSS files that drive thumbnail/lightbox
            Path css = sparkDir.resolve("css").resolve("spark-style.css");
            Path js  = sparkDir.resolve("js").resolve("spark-script.js");

            return java.nio.file.Files.exists(css) && java.nio.file.Files.exists(js);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void dbgStatic(String s) {
        if (DEBUG) System.out.println("[ExtentReportConverter] " + s);
    }

    @Override
    protected void onClose() {
        dbg("CLOSE extent.flush() -> " + reportFile.toAbsolutePath());
        synchronized (lock) {
            extent.flush();
        }
        dbg("CLOSE extent.flush() done");
    }

    @Override
    public void onStart(Entry scope, Entry entry) {
        dbg("onStart scope=" + safe(scope.text) + " entry=" + safe(entry.text));
        emit(scope, entry, Phase.START);
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        dbg("onTimestamp scope=" + safe(scope.text) + " entry=" + safe(entry.text) + " attachments=" + entry.attachments.size());
        emit(scope, entry, Phase.TIMESTAMP);
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        dbg("onStop scope=" + safe(scope.text)
                + " entry=" + safe(entry.text)
                + " attachments=" + entry.attachments.size()
                + " status=" + entry.status);
        emit(scope, entry, Phase.STOP);
    }

    private void emit(Entry scope, Entry entry, Phase phase) {
        ScopeState s = scopes.computeIfAbsent(scope.id, id -> initScope(scope));

        synchronized (lock) {
            ExtentTest node = ensureNode(s, scope, entry);
            dbg("emit phase=" + phase + " ensuredNode entry=" + safe(entry.text) + " node=" + System.identityHashCode(node));

            applyMetaOncePerChange(s, entry, node);

            // Keep your original status behavior (blank message is fine; Spark shows status icon)
            if (phase == Phase.TIMESTAMP) node.log(mapStatusOrInfo(entry), "");
            if (phase == Phase.STOP && entry.status != null) node.log(mapStatus(entry.status), "");
        }
    }

    private ScopeState initScope(Entry scope) {
        synchronized (lock) {
            dbg("initScope scope=" + safe(scope.text) + " scopeId=" + safe(scope.id));
            ScopeState s = new ScopeState();
            s.root = extent.createTest(scope.text);
            s.nodes.put(scope.id, s.root);
            return s;
        }
    }

    // lock must be held
    private ExtentTest ensureNode(ScopeState s, Entry scope, Entry entry) {
        ExtentTest existing = s.nodes.get(entry.id);
        if (existing != null) return existing;

        if (entry == scope) return s.root;

        if (entry.parent != null && isInScope(scope, entry.parent)) ensureNode(s, scope, entry.parent);

        ExtentTest parent =
                (entry.parent != null && isInScope(scope, entry.parent))
                        ? s.nodes.get(entry.parent.id)
                        : s.root;

        ExtentTest node = parent.createNode(entry.text);
        s.nodes.put(entry.id, node);

        dbg("ensureNode created node entry=" + safe(entry.text)
                + " parent=" + (entry.parent != null ? safe(entry.parent.text) : "<null>")
                + " node=" + System.identityHashCode(node));
        return node;
    }

    private static boolean isInScope(Entry scope, Entry entry) {
        for (Entry n = entry; n != null; n = n.parent) if (n == scope) return true;
        return false;
    }

    private void applyMetaOncePerChange(ScopeState s, Entry e, ExtentTest node) {
        int tagsN = e.tags.size();
        int fieldsN = e.fields.size();
        int attachesN = e.attachments.size();

        Meta m = s.meta.computeIfAbsent(e.id, id -> new Meta());

        if (m.tagsN != tagsN) {
            dbg("meta tags changed entry=" + safe(e.text) + " tagsN=" + tagsN);
            if (tagsN > 0) node.assignCategory(e.tags.toArray(String[]::new));
            m.tagsN = tagsN;
        }

        if (m.fieldsN != fieldsN) {
            dbg("meta fields changed entry=" + safe(e.text) + " fieldsN=" + fieldsN + " fields=" + e.fields);
            if (fieldsN > 0) node.info("Fields: " + e.fields);
            m.fieldsN = fieldsN;
        }

        if (m.attachesN != attachesN) {
            dbg("meta attachments changed entry=" + safe(e.text) + " prev=" + m.attachesN + " now=" + attachesN);
        }

        // Attach only newly-added attachments (by index)
        for (int i = m.attachesN; i < attachesN; i++) {
            Attachment a = e.attachments.get(i);

            String name = (a.name() == null || a.name().isBlank()) ? "Screenshot" : a.name();
            String mime = a.mime() == null ? "" : a.mime();
            String data = a.path();

            dbg("ATTACH[" + i + "] entry=" + safe(e.text)
                    + " name=" + safe(name)
                    + " mime=" + safe(mime)
                    + " dataLen=" + (data == null ? -1 : data.length())
                    + " dataHead=" + head(data, 40));

            if (mime.startsWith("image/")) {
                attachImage(node, name, mime, data);
            } else {
                dbg(" -> non-image attachment info()");
                node.info(((a.name() == null || a.name().isBlank()) ? "Attachment" : a.name()) + ": " + data);
            }
        }

        m.attachesN = attachesN;

        if (m.level != e.level && e.level != null) {
            dbg("meta level changed entry=" + safe(e.text) + " level=" + e.level);
            m.level = e.level;
            node.log(mapLevel(e.level), "");
        }
    }

    /**
     * The key behavioral change:
     *  - For base64 screenshots: log with MediaEntityBuilder so Spark renders a thumbnail that expands on click.
     *  - Strip any data-uri prefix and pass RAW base64 to Extent.
     */
    private void attachImage(ExtentTest node, String name, String mime, String data) {
        try {
            if (mime.contains("base64")) {
                String raw = rawBase64(data);

                dbg(" -> attach base64 image as MEDIA (thumbnail/lightbox)");
                dbg("    rawLen=" + (raw == null ? -1 : raw.length()) + " rawHead=" + head(raw, 48));

                if (raw == null || raw.isBlank()) {
                    dbg(" !! base64 data was null/blank; falling back to info()");
                    node.info(name + ": <empty base64>");
                    return;
                }

                // Attach as media on a log line (renders as thumbnail + click-to-expand in Spark)
                node.info(name,
                        MediaEntityBuilder.createScreenCaptureFromBase64String(raw).build()
                );

            } else {
                // Path-based screenshots (file exists relative to report, or absolute)
                dbg(" -> attach path screenshot via addScreenCaptureFromPath path=" + safe(data));
                if (data == null || data.isBlank()) {
                    node.info(name + ": <empty path>");
                    return;
                }
                node.addScreenCaptureFromPath(data, name);
            }
        } catch (Throwable ex) {
            dbg(" !! EXCEPTION adding screenshot: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            node.info(name + ": " + data);
        }
    }

    /**
     * Accepts either raw base64, or a full data-uri "data:image/png;base64,...."
     * Returns the raw base64 only.
     */
    private static String rawBase64(String maybeDataUri) {
        if (maybeDataUri == null) return null;
        String s = maybeDataUri.trim();
        if (s.startsWith("data:image")) {
            int comma = s.indexOf(',');
            if (comma >= 0 && comma + 1 < s.length()) return s.substring(comma + 1);
        }
        return s;
    }

    private static com.aventstack.extentreports.Status mapStatusOrInfo(Entry e) {
        return e.status != null ? mapStatus(e.status) : com.aventstack.extentreports.Status.INFO;
    }

    private static com.aventstack.extentreports.Status mapStatus(Status s) {
        return switch (s) {
            case PASS -> com.aventstack.extentreports.Status.PASS;
            case FAIL -> com.aventstack.extentreports.Status.FAIL;
            case SKIP -> com.aventstack.extentreports.Status.SKIP;
            case WARN -> com.aventstack.extentreports.Status.WARNING;
            case INFO, UNKNOWN -> com.aventstack.extentreports.Status.INFO;
        };
    }

    private static com.aventstack.extentreports.Status mapLevel(Level l) {
        return switch (l) {
            case ERROR -> com.aventstack.extentreports.Status.FAIL;
            case WARN -> com.aventstack.extentreports.Status.WARNING;
            case INFO, DEBUG, TRACE -> com.aventstack.extentreports.Status.INFO;
        };
    }

    private enum Phase { START, TIMESTAMP, STOP }

    private static final class ScopeState {
        ExtentTest root;
        final Map<String, ExtentTest> nodes = new ConcurrentHashMap<>();
        final Map<String, Meta> meta = new ConcurrentHashMap<>();
    }

    private static final class Meta {
        int tagsN;
        int fieldsN;
        int attachesN;
        Level level;
    }

    // ---- debug helpers ----

    private static void dbg(String s) {
        if (DEBUG) System.out.println("[ExtentReportConverter] " + s);
    }

    private static String safe(String s) {
        return s == null ? "<null>" : s;
    }

    private static String head(String s, int n) {
        if (s == null) return "<null>";
        if (s.length() <= n) return s;
        return s.substring(0, n) + "...";
    }
}
