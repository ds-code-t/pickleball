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

    /** prevent duplicate launch/test item start for root scenario entries */
    private final Set<String> startedRootItems = ConcurrentHashMap.newKeySet();

    /** prevent duplicate root finish */
    private final Set<String> finishedRootItems = ConcurrentHashMap.newKeySet();

    /** prevent duplicate attachment sends */
    private final Set<String> sentAttachments = ConcurrentHashMap.newKeySet();

    @Override
    public void onStart(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();
        ReportPortalBridge.startLaunchIfNeeded(null, null);

        // Root RP_NEST span becomes the real RP TEST item.
        if (isRootRpSpan(entry)) {
            ensureRootRpItemStarted(entry);
            ReportPortalBridge.throwIfAsyncFailure();
        }

        // Nested RP_NEST spans are buffered only and logged once on stop().
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        Entry rootRp = nearestRootRpSpan(scope != null ? scope : entry);
        if (rootRp != null) {
            ensureRootRpItemStarted(rootRp);
        }

        // If this event lives inside a buffered span, suppress immediate emission.
        if (isInsideBufferedSpan(entry)) {
            return;
        }

        ReportPortalBridge.log(level(entry.level), entry.text);

        for (int i = 0; i < entry.attachments.size(); i++) {
            String key = entry.id + ":" + i;
            if (!sentAttachments.add(key)) continue;

            Attachment a = entry.attachments.get(i);
            byte[] bytes = tryReadAttachmentBytes(a);
            if (bytes == null) continue;

            String filename = attachmentName(a);
            ReportPortalBridge.logAttachment(level(entry.level), entry.text, bytes, filename);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        Entry rootRp = nearestRootRpSpan(scope != null ? scope : entry);
        if (rootRp != null) {
            ensureRootRpItemStarted(rootRp);
        }

        // Root RP_NEST span closes the real RP TEST item.
        if (isRootRpSpan(entry)) {
            if (entry.parent == null) {
                String rowKey = GlobalState.getCurrentScenarioState().id.toString();
                renderScenarioSummary(scope, rowKey);
            }

            if (finishedRootItems.add(entry.id)) {
                ReportPortalBridge.finishCurrentItem(status(entry.status));
                ReportPortalBridge.throwIfAsyncFailure();
            }
            return;
        }

        // Nested RP_NEST spans stay as Entry spans only.
        // Only the outermost buffered span emits, to avoid duplication.
        if (isRpNest(entry)) {
            if (!hasBufferedSpanAncestor(entry.parent)) {
                ReportPortalBridge.log(level(entry.level), flattenSpan(entry));
                ReportPortalBridge.throwIfAsyncFailure();
            }
            return;
        }

        // Same buffered behavior for non-RP spans.
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
        // no-op
        // finish the launch once per whole run, not per scenario converter
    }

    @Override
    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        Entry rootRp = nearestRootRpSpan(entry);
        if (rootRp != null) {
            ensureRootRpItemStarted(rootRp);
        }

        String b64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);

        String filename = (name == null || name.isBlank())
                ? "screenshot.png"
                : (name.endsWith(".png") ? name : name + ".png");

        // If inside a buffered span, keep it attached only to the Entry tree.
        if (!isInsideBufferedSpan(entry)) {
            ReportPortalBridge.logAttachment("INFO", "Screenshot", Base64.getDecoder().decode(b64), filename);
            ReportPortalBridge.throwIfAsyncFailure();
        }

        return entry.attach(name, "image/png;base64", b64);
    }

    private void ensureRootRpItemStarted(Entry entry) {
        if (!isRootRpSpan(entry)) return;
        if (!startedRootItems.add(entry.id)) return;

        ReportPortalBridge.startItem(entry.text, "TEST", null);
    }

    private static Entry nearestRootRpSpan(Entry entry) {
        for (Entry e = entry; e != null; e = e.parent) {
            if (isRootRpSpan(e)) {
                return e;
            }
        }
        return null;
    }

    private static boolean isRpNest(Entry entry) {
        return hasTag(entry, RP_NEST_TAG);
    }

    private static boolean isRootRpSpan(Entry entry) {
        return entry != null && entry.parent == null && isRpNest(entry);
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

    /**
     * Buffered span ancestors are:
     * - any nested RP_NEST span
     * - any non-RP span
     *
     * Root RP_NEST is NOT buffered; it is the real RP TEST item.
     */
    private static boolean hasBufferedSpanAncestor(Entry entry) {
        for (Entry e = entry; e != null; e = e.parent) {
            if (isRootRpSpan(e)) {
                return false;
            }
            if (isRpNest(e)) {
                return true;
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

    private static byte[] tryReadAttachmentBytes(Attachment a) {
        if (a == null) return null;

        try {
            String path = a.path();
            if (path == null || path.isBlank()) return null;

            Path p = Path.of(path);
            if (!Files.exists(p) || Files.isDirectory(p)) return null;

            return Files.readAllBytes(p);
        } catch (Exception ignored) {
            // Ignore non-file attachments here.
            // Base64 screenshots are already handled directly in screenshot(...).
            return null;
        }
    }

    private static String attachmentName(Attachment a) {
        if (a == null) return "attachment";

        try {
            if (a.name() != null && !a.name().isBlank()) {
                return a.name();
            }
        } catch (Exception ignored) { }

        try {
            String path = a.path();
            if (path != null && !path.isBlank()) {
                return Path.of(path).getFileName().toString();
            }
        } catch (Exception ignored) { }

        return "attachment";
    }

    private static String flattenSpan(Entry entry) {
        StringBuilder sb = new StringBuilder(1024);

        if (isRpNest(entry)) {
            sb.append("RP_NEST SPAN: ").append(nullSafe(entry.text)).append('\n');
        } else {
            sb.append("SPAN: ").append(nullSafe(entry.text)).append('\n');
        }

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
                sb.append(indent)
                        .append("[RP_NEST SPAN] ")
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
            String name = attachmentName(a);
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