package tools.dscode.common.seleniumextensions;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Builds an {@link ElementSnapshot} from a live Selenium element.
 * One call to {@link ElementSnapshotScript#ON_ELEMENT}. No page snapshot.
 * {@code nodeId} and {@code bounds} stay null.
 */
public final class SeleniumSnapshotProducer {
    public static final SeleniumSnapshotProducer INSTANCE = new SeleniumSnapshotProducer();

    private SeleniumSnapshotProducer() {
    }

    public ElementSnapshot snapshot(WebDriver driver, WebElement element) {
        if (element == null) {
            throw new IllegalArgumentException("element must not be null");
        }
        return ElementSnapshotScript.onElement(driver, element);
    }
}
