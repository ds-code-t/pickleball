// file: tools/dscode/common/reporting/logging/ReportPortalBridgeConverter.java
package tools.dscode.common.reporting.logging.reportportal;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tools.dscode.common.reporting.logging.Attachment;
import tools.dscode.common.reporting.logging.BaseConverter;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.reporting.logging.Status;

public final class ReportPortalBridgeConverter extends BaseConverter {

    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    @Override
    public void onStart(Entry scope, Entry entry) {
        ReportPortalBridge.startLaunchIfNeeded(null, null);

        // root span = TEST, nested = STEP
        String type = (entry.parent == null) ? "TEST" : "STEP";
        ReportPortalBridge.startItem(entry.text, type, null);
    }

    @Override
    public void onTimestamp(Entry scope, Entry entry) {
        ReportPortalBridge.log(level(entry.level), entry.text);

        for (int i = 0; i < entry.attachments.size(); i++) {
            String key = entry.id + ":" + i;
            if (!sent.add(key)) continue;

            Attachment a = entry.attachments.get(i);

            byte[] bytes;
            try {
                bytes = Files.readAllBytes(Path.of(a.path()));
            } catch (Exception e) {
                continue;
            }

            String filename = (a.name() == null || a.name().isBlank())
                    ? Path.of(a.path()).getFileName().toString()
                    : a.name();

            ReportPortalBridge.logAttachment(
                    level(entry.level),
                    entry.text,
                    bytes,
                    filename
            );
        }
    }


    @Override
    public void onStop(Entry scope, Entry entry) {
        ReportPortalBridge.finishCurrentItem(status(entry.status));
    }

    @Override
    protected void onClose() {
        ReportPortalBridge.finishAllOpenItems("PASSED");
        ReportPortalBridge.finishLaunch("PASSED");
    }

    @Override
    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        String b64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);

        String filename = (name == null || name.isBlank()) ? "screenshot.png"
                : (name.endsWith(".png") ? name : name + ".png");

        ReportPortalBridge.logAttachment("INFO", "Screenshot", Base64.getDecoder().decode(b64), filename);
        return entry.attach(name, "image/png;base64", b64);
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
