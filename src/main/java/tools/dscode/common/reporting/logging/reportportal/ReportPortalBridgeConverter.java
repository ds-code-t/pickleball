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
import java.time.Duration;
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

        if (isRpNestScope(scope)) {
            // Only the tagged scope itself owns the RP test item lifecycle.
            if (scope == entry) {
                String type = (entry.parent == null) ? "TEST" : "STEP";
                ReportPortalBridge.startItem(entry.text, type, null);
                ReportPortalBridge.throwIfAsyncFailure();
            }
            return;
        }

        // Original behavior for non-RP_NEST scopes.
        String type = (entry.parent == null) ? "TEST" : "STEP";
        ReportPortalBridge.startItem(entry.text, type, null);
        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        ReportPortalBridge.log(level(entry.level), entry.text);
        flushAttachments(entry, level(entry.level), entry.text);

        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        if (isRpNestScope(scope)) {
            if (scope == entry) {
                // Closing the real RP test item for the scenario/root scope.
                if (entry.parent == null) {
                    String rowKey = GlobalState.getCurrentScenarioState().id.toString();
                    renderScenarioSummary(scope, rowKey);
                }

                flushAttachments(entry, levelForStop(entry), entry.text);
                ReportPortalBridge.finishCurrentItem(status(entry.status));
                ReportPortalBridge.throwIfAsyncFailure();
                return;
            }

            // Descendant span inside RP_NEST scope:
            // do NOT create/finish RP child items; just log summary inside the scenario item.
            flushAttachments(entry, levelForStop(entry), entry.text);
            ReportPortalBridge.log(levelForStop(entry), formatNestedSpan(entry));
            ReportPortalBridge.throwIfAsyncFailure();
            return;
        }

        // Original behavior for non-RP_NEST scopes.
        if (entry.parent == null) {
            String rowKey = GlobalState.getCurrentScenarioState().id.toString();
            renderScenarioSummary(scope, rowKey);
        }

        ReportPortalBridge.finishCurrentItem(status(entry.status));
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

        ReportPortalBridge.log("INFO", sb.toString());
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

        ReportPortalBridge.logAttachment("INFO", "Screenshot", Base64.getDecoder().decode(b64), filename);

        Entry out = entry.attach(filename, "image/png;base64", b64);

        // Prevent later attachment flushing from trying to read the base64 payload as a filesystem path.
        int idx = out.attachments.size() - 1;
        if (idx >= 0) {
            sent.add(out.id + ":" + idx);
        }

        return out;
    }

    private void flushAttachments(Entry entry, String level, String message) {
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

            ReportPortalBridge.logAttachment(level, message, bytes, filename);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    private static boolean isRpNestScope(Entry scope) {
        if (scope == null) return false;
        for (String tag : scope.tags) {
            if (tag != null && RP_NEST.equalsIgnoreCase(tag.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String formatNestedSpan(Entry entry) {
        StringBuilder sb = new StringBuilder(256);

        sb.append(entry.text == null ? "" : entry.text);

        if (entry.startedAt != null && entry.stoppedAt != null) {
            Duration d = Duration.between(entry.startedAt, entry.stoppedAt);
            sb.append("\nstatus: ").append(status(entry.status));
            sb.append("\nduration: ").append(formatDuration(d));
        } else if (entry.stoppedAt != null) {
            sb.append("\nstatus: ").append(status(entry.status));
        }

        if (!entry.fields.isEmpty()) {
            sb.append("\nfields:");
            entry.fields.forEach((k, v) ->
                    sb.append("\n- ").append(k).append(": ").append(v == null ? "" : v));
        }

        return sb.toString();
    }

    private static String formatDuration(Duration d) {
        long millis = d.toMillis();
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        long seconds = (millis % 60_000) / 1_000;
        long ms = millis % 1_000;

        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, ms);
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