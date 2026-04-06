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
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportPortalBridgeConverter extends BaseConverter {

    private static final String RP_NEST = "RP_NEST";

    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    @Override
    public void onStart(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();
        ReportPortalBridge.startLaunchIfNeeded(null, null);

        if (shouldOpenRpItem(scope, entry)) {
            String type = (entry.parent == null) ? "TEST" : "STEP";
            ReportPortalBridge.startItem(entry.text, type, null, entry.startedAt);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        ReportPortalBridge.log(level(entry.level), entry.text, entry.timestampedAt);
        flushAttachments(entry, entry.timestampedAt, level(entry.level), entry.text);

        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        if (entry.parent == null) {
            String rowKey = GlobalState.getCurrentScenarioState().id.toString();
            renderScenarioSummary(scope, rowKey);
        }

        if (shouldOpenRpItem(scope, entry)) {
            flushAttachments(entry, entry.stoppedAt, levelForStop(entry), entry.text);
            ReportPortalBridge.finishCurrentItem(status(entry.status), entry.stoppedAt);
        } else {
            flushAttachments(entry, entry.stoppedAt, levelForStop(entry), entry.text);

            if (shouldEmitSpanStopSummary(entry)) {
                ReportPortalBridge.log(levelForStop(entry), formatSpanStop(entry), entry.stoppedAt);
            }
        }

        ReportPortalBridge.throwIfAsyncFailure();
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

        ReportPortalBridge.log("INFO", sb.toString(), Instant.now());
    }

    @Override
    protected void onClose() {
        ReportPortalBridge.throwIfAsyncFailure();
        ReportPortalBridge.finishAllOpenItems("PASSED");
        ReportPortalBridge.finishLaunch("PASSED");
        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        String b64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);

        String filename = (name == null || name.isBlank())
                ? "screenshot.png"
                : (name.endsWith(".png") ? name : name + ".png");

        ReportPortalBridge.logAttachment(
                "INFO",
                "Screenshot",
                Base64.getDecoder().decode(b64),
                filename,
                Instant.now()
        );
        ReportPortalBridge.throwIfAsyncFailure();

        Entry out = entry.attach(filename, "image/png;base64", b64);

        // Mark this just-added attachment as already sent so a later timestamp()/stop()
        // does not try to re-read the base64 string as if it were a file path.
        int idx = out.attachments.size() - 1;
        if (idx >= 0) {
            sent.add(out.id + ":" + idx);
        }

        return out;
    }

    private void flushAttachments(Entry entry, Instant when, String level, String message) {
        for (int i = 0; i < entry.attachments.size(); i++) {
            String key = entry.id + ":" + i;
            if (!sent.add(key)) continue;

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

            ReportPortalBridge.logAttachment(level, message, bytes, filename, when);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    /**
     * Open an RP item only when:
     * - the entry itself is tagged RP_NEST, OR
     * - we are not inside an RP_NEST ancestor scope at all (preserves old behavior outside RP_NEST usage).
     *
     * Effect:
     * - scenario/root tagged RP_NEST becomes the ReportPortal test item
     * - ordinary descendants inside that RP_NEST scope are logged inside the test, not created as nested tests
     * - if you intentionally tag a child span with RP_NEST, that child will open its own RP item
     */
    private static boolean shouldOpenRpItem(Entry scope, Entry entry) {
        return isRpNest(entry) || !isDescendantOfRpNestScope(scope, entry);
    }

    private static boolean isDescendantOfRpNestScope(Entry scope, Entry entry) {
        return scope != null && entry != null && scope != entry && isRpNest(scope);
    }

    private static boolean isRpNest(Entry entry) {
        if (entry == null) return false;
        for (String tag : entry.tags) {
            if (tag != null && RP_NEST.equalsIgnoreCase(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For descendant spans inside an RP_NEST scenario, avoid noisy duplicate logs for the common pattern:
     * scenarioLog.logWithType(...).start() ... stop()
     *
     * That pattern already emitted a timestamp log before start(), so on stop() we only add another log when
     * there is useful extra information to preserve.
     */
    private static boolean shouldEmitSpanStopSummary(Entry entry) {
        return entry.timestampedAt == null
                || entry.status == Status.FAIL
                || entry.status == Status.SKIP
                || !entry.fields.isEmpty();
    }

    private static String formatSpanStop(Entry entry) {
        StringBuilder sb = new StringBuilder(256);

        sb.append("SPAN");
        sb.append(" [").append(status(entry.status)).append("] ");
        sb.append(entry.text == null ? "" : entry.text);

        if (entry.startedAt != null) {
            sb.append("\nstart: ").append(entry.startedAt);
        }
        if (entry.stoppedAt != null) {
            sb.append("\nstop: ").append(entry.stoppedAt);
        }
        if (entry.startedAt != null && entry.stoppedAt != null) {
            sb.append("\nduration: ").append(entry.durationFormatted());
        }

        if (!entry.fields.isEmpty()) {
            sb.append("\nfields:");
            entry.fields.forEach((k, v) -> sb.append("\n- ").append(k).append(": ").append(v));
        }

        return sb.toString();
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

    private static String levelForStop(Entry entry) {
        if (entry != null) {
            if (entry.status == Status.FAIL) return "ERROR";
            if (entry.status == Status.SKIP) return "WARN";
        }
        return level(entry == null ? null : entry.level);
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