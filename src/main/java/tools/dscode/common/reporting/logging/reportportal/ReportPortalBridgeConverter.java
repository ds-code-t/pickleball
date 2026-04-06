package tools.dscode.common.reporting.logging.reportportal;

import io.cucumber.core.runner.GlobalState;
import io.reactivex.Maybe;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportPortalBridgeConverter extends BaseConverter {

    private static final String RP_NEST_TAG = "RP_NEST";

    private final Set<String> sent = ConcurrentHashMap.newKeySet();
    private final Map<String, Maybe<String>> rpItemsByEntryId = new ConcurrentHashMap<>();

    @Override
    public void onStart(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();
        ReportPortalBridge.startLaunchIfNeeded(null, null);

        if (!hasTag(entry, RP_NEST_TAG)) {
            return;
        }

        Maybe<String> parent = nearestParentItem(entry.parent);
        String type = (parent == null) ? "TEST" : "STEP";

        Maybe<String> item = ReportPortalBridge.startItem(parent, entry.text, type, null);
        rpItemsByEntryId.put(entry.id, item);

        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();
        ReportPortalBridge.log(level(entry.level), entry.text);

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

            ReportPortalBridge.logAttachment(level(entry.level), entry.text, bytes, filename);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    @Override
    public void onStop(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        if (entry.parent == null) {
            String rowKey = GlobalState.getCurrentScenarioState().id.toString();
            renderScenarioSummary(scope, rowKey);
        }

        Maybe<String> item = rpItemsByEntryId.remove(entry.id);
        if (item != null) {
            ReportPortalBridge.finishItem(item, status(entry.status));
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
        ReportPortalBridge.log("INFO", sb.toString());
    }

    @Override
    protected void onClose() {
        ReportPortalBridge.throwIfAsyncFailure();
        // Intentionally do NOT finish the launch here if this converter is per-scenario.
    }

    @Override
    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        String b64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);

        String filename = (name == null || name.isBlank()) ? "screenshot.png"
                : (name.endsWith(".png") ? name : name + ".png");

        ReportPortalBridge.logAttachment("INFO", "Screenshot", Base64.getDecoder().decode(b64), filename);
        return entry.attach(name, "image/png;base64", b64);
    }

    private Maybe<String> nearestParentItem(Entry entry) {
        for (Entry e = entry; e != null; e = e.parent) {
            Maybe<String> item = rpItemsByEntryId.get(e.id);
            if (item != null) return item;
        }
        return null;
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