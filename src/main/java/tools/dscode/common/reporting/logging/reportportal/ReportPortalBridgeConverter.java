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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ReportPortalBridgeConverter extends BaseConverter {

    private static final String RP_NEST_TAG = "RP_NEST";

    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    /** entry.id -> RP item uuid promise */
    private final Map<String, Maybe<String>> rpItemsByEntryId = new ConcurrentHashMap<>();

    /** entry.id -> original Entry, only for cleanup ordering */
    private final Map<String, Entry> rpEntriesById = new ConcurrentHashMap<>();

    @Override
    public void onStart(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();
        ReportPortalBridge.startLaunchIfNeeded(null, null);

        Maybe<String> rpItem = null;

        if (entry.parent == null) {
            rpItem = ReportPortalBridge.startRootItem(entry.text, "TEST", null);
        } else if (hasTag(entry, RP_NEST_TAG)) {
            Maybe<String> parentRpItem = nearestRpItem(entry.parent);
            rpItem = ReportPortalBridge.startChildItem(parentRpItem, entry.text, "STEP", null);
        }

        if (rpItem != null) {
            rpItemsByEntryId.put(entry.id, rpItem);
            rpEntriesById.put(entry.id, entry);
            ReportPortalBridge.throwIfAsyncFailure();
            return;
        }

        // Untagged span: do not create a nested RP item, but still show something.
        // Avoid duplicate output when this entry was already timestamped earlier.
        if (entry.timestampedAt == null) {
            ReportPortalBridge.logToItem(nearestRpItem(entry), level(entry.level), entry.text);
            ReportPortalBridge.throwIfAsyncFailure();
        }
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.throwIfAsyncFailure();

        Maybe<String> rpItem = nearestRpItem(entry);
        ReportPortalBridge.logToItem(rpItem, level(entry.level), entry.text);

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

            ReportPortalBridge.logAttachmentToItem(rpItem, level(entry.level), entry.text, bytes, filename);
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

        Maybe<String> rpItem = rpItemsByEntryId.remove(entry.id);
        rpEntriesById.remove(entry.id);

        if (rpItem != null) {
            ReportPortalBridge.finishItem(rpItem, status(entry.status));
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

        ReportPortalBridge.logToItem(rpItemsByEntryId.get(scope.id), "INFO", sb.toString());
    }

    @Override
    protected void onClose() {
        ReportPortalBridge.throwIfAsyncFailure();

        List<Entry> open = new ArrayList<>(rpEntriesById.values());
        open.sort(Comparator.comparingInt(this::depth).reversed());

        for (Entry e : open) {
            Maybe<String> rpItem = rpItemsByEntryId.remove(e.id);
            rpEntriesById.remove(e.id);
            if (rpItem != null) {
                ReportPortalBridge.finishItem(rpItem, "PASSED");
            }
        }

        ReportPortalBridge.finishLaunch("PASSED");
        ReportPortalBridge.throwIfAsyncFailure();
    }

    @Override
    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        String b64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);

        String filename = (name == null || name.isBlank()) ? "screenshot.png"
                : (name.endsWith(".png") ? name : name + ".png");

        ReportPortalBridge.logAttachmentToItem(
                nearestRpItem(entry),
                "INFO",
                "Screenshot",
                Base64.getDecoder().decode(b64),
                filename
        );

        return entry.attach(name, "image/png;base64", b64);
    }

    private Maybe<String> nearestRpItem(Entry entry) {
        for (Entry n = entry; n != null; n = n.parent) {
            Maybe<String> rpItem = rpItemsByEntryId.get(n.id);
            if (rpItem != null) return rpItem;
        }
        return null;
    }

    private int depth(Entry entry) {
        int d = 0;
        for (Entry n = entry; n != null; n = n.parent) d++;
        return d;
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