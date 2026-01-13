// file: tools/dscode/common/reporting/logging/BaseConverter.java
package tools.dscode.common.reporting.logging;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class BaseConverter {

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public void onStart(Entry scope, Entry entry) { }
    public void onTimestamp(Entry scope, Entry entry) { }
    public void onStop(Entry scope, Entry entry) { }

    /** Override for flush / write / cleanup logic */
    protected void onClose() { }

    /** Closes this converter instance (idempotent). */
    public final BaseConverter close() {
        if (closed.compareAndSet(false, true)) {
            onClose();
        }
        return this;
    }

    public final boolean isClosed() {
        return closed.get();
    }

    public Entry screenshot(Entry entry, WebDriver driver) {
        return screenshot(entry, driver, null);
    }

    public Entry screenshot(Entry entry, WebDriver driver, String name) {
        String base64 = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64);
        return entry.attach(name, "image/png;base64", base64);
    }

}
