package tools.dscode.common.seleniumextensions;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.composite.CompositeWebElement;

/**
 * Builds an {@link ElementSnapshot} from a {@link CompositeWebElement}.
 *
 * <p>dom-composite 2.4.0 cannot eval JavaScript on a stored node. This
 * producer runs {@link ElementSnapshotScript#FUNCTION} through the driver's
 * {@link org.openqa.selenium.JavascriptExecutor}, the same function
 * {@link SeleniumSnapshotProducer} runs with the element as {@code arguments[0]}.
 * The script locates the live node from {@link SnapshotLookup} candidates
 * (including same-origin frames). That is one script call. {@code nodeId} and
 * {@code bounds} are copied from the composite hit. Tag, text, value,
 * selected child, attributes, positions, and the stable selector come only
 * from the script. A miss throws. Positions are not invented.
 */
public final class CompositeSnapshotProducer {
    public static final CompositeSnapshotProducer INSTANCE = new CompositeSnapshotProducer();

    private CompositeSnapshotProducer() {
    }

    public ElementSnapshot snapshot(WebDriver driver, WebElement element) {
        if (!(element instanceof CompositeWebElement composite)) {
            throw new IllegalArgumentException(
                    "error: composite snapshot producer requires a CompositeWebElement. fix: use SeleniumSnapshotProducer for a Selenium element.");
        }
        return ElementSnapshotScript.onComposite(driver, composite);
    }
}
