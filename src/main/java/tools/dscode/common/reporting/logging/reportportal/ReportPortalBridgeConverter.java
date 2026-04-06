package tools.dscode.common.reporting.logging.reportportal;

import io.cucumber.core.runner.GlobalState;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.reporting.logging.Attachment;
import tools.dscode.common.reporting.logging.BaseConverter;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.reporting.logging.Status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportPortalBridgeConverter extends BaseConverter {

    private static final String RP_NEST_TAG = "RP_NEST";

    private final Set<String> sentAttachments = ConcurrentHashMap.newKeySet();
    private final Set<String> startedRpItems = ConcurrentHashMap.newKeySet();
    private final Set<String> finishedRpItems = ConcurrentHashMap.newKeySet();

    @Override
    public void onStart(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();
        ReportPortalBridge.startLaunchIfNeeded(null, null);

        // Ensure the scenario/root RP item exists before any nested RP child starts.
        if (isRpNest(scope)) {
            ensureRpItemStarted(scope);
        }

        if (!isRpNest(entry)) {
            return;
        }

        ensureRpItemStarted(entry);
        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        // Ensure root/scenario RP item exists even if the first thing we see is a timestamped child log.
        if (isRpNest(scope)) {
            ensureRpItemStarted(scope);
        }

        // If this event lives inside a non-RP span, buffer it until that span stops.
        if (isInsideBufferedSpan(entry)) {
            return;
        }

        ReportPortalBridge.log(level(entry.level), entry.text);

        for (int i = 0; i < entry.attachments.size(); i++) {
            String key = entry.id + ":" + i;
            if (!sentAttachments.add(key)) continue;

            Attachment a = entry.attachments.get(i);

            byte[] bytes;
            try {
                bytes = Files.readAllBytes(Path.of(a.path()));
            } catch (Exception e) {
                throw new RuntimeException("Failed to read attachment: " + a.path(), e);
            }

            String filename = (a.name() == null || a.name().isBlank())
                    ? Path.of(a.path()).getFileName().toString()
                    : a.name();

            ReportPortalBridge.logAttachment(level(entry.level), entry.text, bytes, filename);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        // Make sure the scenario/root item exists before summary logging.
        if (isRpNest(scope)) {
            ensureRpItemStarted(scope);
        }

        if (entry.parent == null) {
            String rowKey = GlobalState.getCurrentScenarioState().id.toString();
            renderScenarioSummary(scope, rowKey);
        }

        if (isRpNest(entry)) {
            if (finishedRpItems.add(entry.id)) {
                ensureRpItemStarted(entry);
                ReportPortalBridge.finishCurrentItem(status(entry.status));
                ReportPortalBridge.throwIfAsyncFailure();
            }
            return;
        }

        // Only the outermost buffered span emits the flattened summary.
        if (isSpan(entry) && !hasBufferedSpanAncestor(entry.parent)) {
            ReportPortalBridge.log(level(entry.level), flattenSpan(entry));
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    @Override
    protected void onScenarioSummary(Entry scope, String rowKey, RowData data) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("Scenario Summary\n");

        for (String h : data.headers()) {
            Object v = data.valuesByHeader().get(h);
            sb.append("- ").append(h).append(": ")
                    .append(v == null ? "" : String.valueOf(v))
                    .append('\n');
        }

        ReportPortalBridge.log("INFO", sb.toString());
    }

    @Override
    protected void onClose() {
        // Intentionally no-op.
        // The launch should be finished once per whole run, not per scenario converter instance.
    }

    @Override
    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        Entry rpScope = nearestRpNest(entry);
        if (rpScope != null) {
            ensureRpItemStarted(rpScope);
        }

        String b64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);

        String filename = (name == null || name.isBlank())
                ? "screenshot.png"
                : (name.endsWith(".png") ? name : name + ".png");

        // If inside a buffered span, keep it attached to the Entry only.
        // The flattened stop-summary will mention attachments.
        if (!isInsideBufferedSpan(entry)) {
            ReportPortalBridge.logAttachment("INFO", "Screenshot", Base64.getDecoder().decode(b64), filename);
            ReportPortalBridge.throwIfAsyncFailure();
        }

        return entry.attach(name, "image/png;base64", b64);
    }

    private void ensureRpItemStarted(Entry entry) {
        if (entry == null || !isRpNest(entry)) {
            return;
        }
        if (!startedRpItems.add(entry.id)) {
            return;
        }

        boolean root = entry.parent == null;

        // Keep root item behavior close to your previous implementation.
        String type = root ? "TEST" : "STEP";

        // ReportPortal nested steps are STEP items with hasStats=false.
        Boolean hasStats = root ? null : Boolean.FALSE;

        ReportPortalBridge.startItem(entry.text, type, null, hasStats);
    }

    private static Entry nearestRpNest(Entry entry) {
        for (Entry e = entry; e != null; e = e.parent) {
            if (isRpNest(e)) return e;
        }
        return null;
    }

    private static boolean isRpNest(Entry entry) {
        return hasTag(entry, RP_NEST_TAG);
    }

    private static boolean hasTag(Entry entry, String wantedTag) {
        if (entry == null || wantedTag == null) return false;

        for (String tag : entry.tags) {
            if (tag != null && wantedTag.equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpan(Entry entry) {
        return entry != null && entry.startedAt != null;
    }

    private static boolean hasBufferedSpanAncestor(Entry entry) {
        for (Entry e = entry; e != null; e = e.parent) {
            if (isRpNest(e)) {
                return false;
            }
            if (isSpan(e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideBufferedSpan(Entry entry) {
        return hasBufferedSpanAncestor(entry.parent);
    }

    private static String flattenSpan(Entry entry) {
        StringBuilder sb = new StringBuilder(1024);

        sb.append("SPAN: ").append(nullSafe(entry.text)).append('\n');
        sb.append("- status: ").append(status(entry.status)).append('\n');

        if (entry.startedAt != null) {
            sb.append("- started: ").append(entry.startedAt).append('\n');
        }
        if (entry.stoppedAt != null) {
            sb.append("- stopped: ").append(entry.stoppedAt).append('\n');
        }
        if (entry.startedAt != null) {
            sb.append("- duration: ").append(entry.durationFormatted()).append('\n');
        }

        appendAttachments(sb, entry, "  ");
        appendChildren(sb, entry, "  ");

        return sb.toString();
    }

    private static void appendChildren(StringBuilder sb, Entry parent, String indent) {
        for (Entry child : parent.children) {
            if (isRpNest(child)) {
                sb.append(indent).append("[RP_NEST] ").append(nullSafe(child.text)).append('\n');
                continue;
            }

            if (isSpan(child)) {
                sb.append(indent)
                        .append("[SPAN] ")
                        .append(nullSafe(child.text))
                        .append(" | status=").append(status(child.status));

                if (child.startedAt != null) {
                    sb.append(" | started=").append(child.startedAt);
                }
                if (child.stoppedAt != null) {
                    sb.append(" | stopped=").append(child.stoppedAt);
                }
                if (child.startedAt != null) {
                    sb.append(" | duration=").append(child.durationFormatted());
                }
                sb.append('\n');

                appendAttachments(sb, child, indent + "  ");
                appendChildren(sb, child, indent + "  ");
            } else {
                sb.append(indent).append("- ").append(nullSafe(child.text));

                if (child.level != null) {
                    sb.append(" [").append(level(child.level)).append(']');
                }
                if (child.timestampedAt != null) {
                    sb.append(" @ ").append(child.timestampedAt);
                }
                sb.append('\n');

                appendAttachments(sb, child, indent + "  ");
            }
        }
    }

    private static void appendAttachments(StringBuilder sb, Entry entry, String indent) {
        for (Attachment a : entry.attachments) {
            String name = (a.name() == null || a.name().isBlank()) ? "attachment" : a.name();
            sb.append(indent).append("[ATTACHMENT] ").append(name).append('\n');
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String level(Level lvl) {
        if (lvl == null) return "INFO";
        return switch (lvl) {
            case ERROR -> "ERROR";
            case WARN  -> "WARN";
            case DEBUG -> "DEBUG";
            case TRACE -> "TRACE";
            default    -> "INFO";
        };
    }

    private static String status(Status st) {
        if (st == null) return "PASSED";
        return switch (st) {
            case FAIL -> "FAILED";
            case SKIP -> "SKIPPED";
            default   -> "PASSED";
        };
    }
}