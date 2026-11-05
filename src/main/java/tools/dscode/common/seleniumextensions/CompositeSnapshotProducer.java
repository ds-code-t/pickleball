package tools.dscode.common.seleniumextensions;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import tools.dscode.common.composite.CompositeLog;
import tools.dscode.common.composite.CompositeWebElement;
import tools.dscode.common.composite.DomCompositeAdapter;

/**
 * Builds an {@link ElementSnapshot} from a {@link CompositeWebElement}.
 *
 * <p>When {@code capabilities.actions} lists {@code eval}, this is one
 * adapter eval of {@link ElementSnapshotScript#EVAL_SOURCE}. The engine
 * binds {@code this} to the stored node and does not search again.
 * When {@code eval} is absent, this producer runs
 * {@link ElementSnapshotScript#FUNCTION} through the driver's
 * {@link org.openqa.selenium.JavascriptExecutor}. That fallback is one
 * script call. {@code nodeId} and {@code bounds} are copied from the
 * composite hit. Tag, text, value, selected child, attributes, positions,
 * and the stable selector come only from the script. A miss throws.
 * Positions are not invented.
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
        if (DomCompositeAdapter.get().actionListed("eval")) {
            CompositeLog.info("adapter", "snapshot eval");
            return ElementSnapshotScript.onEval(composite);
        }
        CompositeLog.info("adapter", "snapshot script");
        return ElementSnapshotScript.onComposite(driver, composite);
    }
}
