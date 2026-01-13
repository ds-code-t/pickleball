package tools.dscode.common.reporting.logging;

import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ExtentReportConverter extends BaseConverter {

    private final Object lock = new Object();

    private final ExtentReports extent = new ExtentReports();
    private final Map<String, ScopeState> scopes = new ConcurrentHashMap<>();

    public ExtentReportConverter(Path reportFile) {
        ExtentSparkReporter spark = new ExtentSparkReporter(reportFile.toString());
        spark.config().thumbnailForBase64(true);
        extent.attachReporter(spark);
    }


    @Override
    protected void onClose() {
        synchronized (lock) {
            extent.flush();
        }
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
            ExtentTest node = ensureNode(s, scope, entry);

            applyMetaOncePerChange(s, entry, node);

            if (phase == Phase.TIMESTAMP) node.log(mapStatusOrInfo(entry), "");
            if (phase == Phase.STOP && entry.status != null) node.log(mapStatus(entry.status), "");
        }
    }

    private ScopeState initScope(Entry scope) {
        synchronized (lock) {
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
            if (tagsN > 0) node.assignCategory(e.tags.toArray(String[]::new));
            m.tagsN = tagsN;
        }

        if (m.fieldsN != fieldsN) {
            if (fieldsN > 0) node.info("Fields: " + e.fields);
            m.fieldsN = fieldsN;
        }

        // attach only newly-added attachments (by index)
        for (int i = m.attachesN; i < attachesN; i++) {
            Attachment a = e.attachments.get(i);
            String data = a.path(); // in your schema this may be a path OR base64 data depending on mime

            if (a.mime().startsWith("image/")) {
                try {
                    if (a.mime().contains("base64")) {
                        // Extent supports base64 screenshots
                        if (a.name() == null || a.name().isBlank()) node.addScreenCaptureFromBase64String(data);
                        else node.addScreenCaptureFromBase64String(data, a.name());
                    } else {
                        // file path screenshot
                        if (a.name() == null || a.name().isBlank()) node.addScreenCaptureFromPath(data);
                        else node.addScreenCaptureFromPath(data, a.name());
                    }
                } catch (Exception ex) {
                    String label = (a.name() == null || a.name().isBlank()) ? "Screenshot" : a.name();
                    node.info(label + ": " + data);
                }
            } else {
                String label = (a.name() == null || a.name().isBlank()) ? "Attachment" : a.name();
                node.info(label + ": " + data);
            }
        }
        m.attachesN = attachesN;

        if (m.level != e.level && e.level != null) {
            m.level = e.level;
            node.log(mapLevel(e.level), "");
        }
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

}
