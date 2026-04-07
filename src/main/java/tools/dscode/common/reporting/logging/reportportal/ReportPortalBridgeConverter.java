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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportPortalBridgeConverter extends BaseConverter {

    private static final String RP_SUITE_PREFIX = "RP_SUITE:";

    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    @Override
    public void onStart(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        if (isScenarioRoot(scope, entry)) {
            ReportPortalBridge.startTest(entry.text, suiteName(scope));
        } else {
            ReportPortalBridge.log(level(entry.level), "STARTED: " + safe(entry.text));
        }

        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        ReportPortalBridge.log(level(entry.level), safe(entry.text));
        flushAttachments(entry, level(entry.level), safe(entry.text));

        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        if (isScenarioRoot(scope, entry)) {
            if (entry.parent == null) {
                String rowKey = GlobalState.getCurrentScenarioState().id.toString();
                renderScenarioSummary(scope, rowKey);
            }

            flushAttachments(entry, levelForStop(entry), safe(entry.text));
            ReportPortalBridge.finishCurrentTest(status(entry.status));
        } else {
            flushAttachments(entry, levelForStop(entry), safe(entry.text));
            ReportPortalBridge.log(levelForStop(entry), formatStoppedSpan(entry));
        }

        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    protected void onScenarioSummary(Entry scope, String rowKey, RowData data) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Scenario Summary\n");

        for (String h : data.headers()) {
            Object v = data.valuesByHeader().get(h);
            sb.append("- ")
                    .append(h)
                    .append(": ")
                    .append(v == null ? "" : String.valueOf(v))
                    .append('\n');
        }

        ReportPortalBridge.log("INFO", sb.toString());
    }

    @Override
    protected void onClose() {
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
            String payload = a.path();
            String mime = a.mime();

            byte[] bytes;
            String filename = resolveFilename(a, payload, mime);

            try {
                if (isBase64Attachment(mime)) {
                    bytes = Base64.getDecoder().decode(payload);
                } else if (isExistingPath(payload)) {
                    Path p = Path.of(payload);
                    bytes = Files.readAllBytes(p);

                    if (a.name() == null || a.name().isBlank()) {
                        filename = p.getFileName().toString();
                    }
                } else {
                    bytes = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to materialize attachment: " + describeAttachment(a), e);
            }

            ReportPortalBridge.logAttachment(level, message, bytes, filename);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    private static boolean isScenarioRoot(Entry scope, Entry entry) {
        return scope == entry && entry != null && entry.parent == null;
    }

    private static String suiteName(Entry scope) {
        if (scope == null) return null;

        for (String tag : scope.tags) {
            if (tag == null) continue;

            String t = tag.trim();
            if (t.regionMatches(true, 0, RP_SUITE_PREFIX, 0, RP_SUITE_PREFIX.length())) {
                String suite = t.substring(RP_SUITE_PREFIX.length()).trim();
                return suite.isEmpty() ? null : suite;
            }
        }

        return null;
    }

    private static boolean isBase64Attachment(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).contains("base64");
    }

    private static boolean isExistingPath(String payload) {
        if (payload == null || payload.isBlank()) return false;
        try {
            return Files.exists(Path.of(payload));
        } catch (Exception e) {
            return false;
        }
    }

    private static String resolveFilename(Attachment a, String payload, String mime) {
        if (a.name() != null && !a.name().isBlank()) {
            return a.name();
        }

        if (isExistingPath(payload)) {
            try {
                return Path.of(payload).getFileName().toString();
            } catch (Exception ignored) { }
        }

        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.startsWith("image/png")) return "attachment.png";
            if (m.startsWith("image/jpeg")) return "attachment.jpg";
            if (m.startsWith("application/json")) return "attachment.json";
            if (m.startsWith("text/plain")) return "attachment.txt";
        }

        return "attachment.bin";
    }

    private static String describeAttachment(Attachment a) {
        String payload = a.path();
        String preview = payload == null
                ? "null"
                : (payload.length() <= 32 ? payload : payload.substring(0, 32) + "...");

        return "name='" + a.name() + "', mime='" + a.mime() + "', payload='" + preview + "'";
    }

    private static String formatStoppedSpan(Entry entry) {
        StringBuilder sb = new StringBuilder(256);

        sb.append("STOPPED: ").append(safe(entry.text));
        sb.append("\nstatus: ").append(status(entry.status));

        if (entry.startedAt != null && entry.stoppedAt != null) {
            Duration d = Duration.between(entry.startedAt, entry.stoppedAt);
            sb.append("\nduration: ").append(formatDuration(d));
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

    private static String safe(String s) {
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