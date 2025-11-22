package tools.dscode.common.domoperations;

import org.openqa.selenium.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Snapshot-oriented WebElement wrapper.
 *
 * - On construction, it caches major passive data from the delegate:
 *   tagName, text, isDisplayed, isEnabled, isSelected, common attributes,
 *   basic geometry, and some CSS properties.
 *
 * - All passive WebElement methods (that only return information) are served
 *   from this snapshot and DO NOT touch the live delegate:
 *     getTagName, getText, getAttribute, isDisplayed, isEnabled, isSelected,
 *     getLocation, getSize, getRect, getCssValue.
 *
 * - Active operations (click, sendKeys, clear, submit, findElement(s),
 *   getScreenshotAs) still use the live delegate via withFreshElement(...),
 *   which may trigger a full refresh on the parent and retry.
 */
public final class WrappedWebElement implements WebElement, WrapsElement {

    @Override
    public WebElement getWrappedElement() {
        // Return the live delegate for JS/Actions use
        WrappedWebElement currentWrapper = resolveCurrent();
        if (currentWrapper == null) {
            throw new NoSuchElementException(
                    "Element at index " + index + " no longer exists after refresh"
            );
        }
        return parent.getDelegate(currentWrapper.index);
    }

    private final XPathChainResult parent;
    private final int index;

    // linked list within one generation (optional but handy)
    private WrappedWebElement previous;
    private WrappedWebElement next;

    // updated by parent on refresh:
    //  - latest generation: current == this
    //  - older: current == new wrapper at same index or null if index no longer exists
    private WrappedWebElement current;

    // ---- snapshot fields ----
    private final String snapshotTagName;
    private final String snapshotText;
    private final boolean snapshotDisplayed;
    private final boolean snapshotEnabled;
    private final boolean snapshotSelected;

    private final Map<String, String> attributeSnapshot = new HashMap<>();
    private final Map<String, String> cssSnapshot       = new HashMap<>();

    private final Point snapshotLocation;
    private final Dimension snapshotSize;
    private final Rectangle snapshotRect;

    // a finite list of attributes we consider "major" for caching
    private static final List<String> COMMON_ATTRIBUTES = List.of(
            "id", "name", "class", "value", "type",
            "href", "src", "alt", "title", "style",
            "role", "data-test", "data-testid", "aria-label", "aria-hidden"
    );

    // a finite list of CSS properties we snapshot up front
    private static final List<String> COMMON_CSS_PROPS = List.of(
            "display", "visibility", "opacity", "pointer-events",
            "position", "z-index"
    );

    WrappedWebElement(XPathChainResult parent, int index, WebElement delegate) {
        this.parent = parent;
        this.index  = index;
        this.current = this;

        // basic passive data
        this.snapshotTagName   = safeString(delegate::getTagName);
        this.snapshotText      = safeString(delegate::getText);
        this.snapshotDisplayed = safeBoolean(delegate::isDisplayed);
        this.snapshotEnabled   = safeBoolean(delegate::isEnabled);
        this.snapshotSelected  = safeBoolean(delegate::isSelected);

        // common attributes
        for (String name : COMMON_ATTRIBUTES) {
            String value = safeString(() -> delegate.getAttribute(name));
            if (!value.isEmpty()) {
                attributeSnapshot.put(name, value);
            }
        }

        // allow callers to see the full map if needed
        // (they can still ask getAttribute("foo") for keys we didn't pre-fill; that returns "")

        // snapshot some basic geometry
        this.snapshotLocation = safePoint(delegate::getLocation);
        this.snapshotSize     = safeDimension(delegate::getSize);
        this.snapshotRect     = safeRect(delegate::getRect);

        // a small set of CSS properties
        for (String prop : COMMON_CSS_PROPS) {
            String value = safeString(() -> delegate.getCssValue(prop));
            if (!value.isEmpty()) {
                cssSnapshot.put(prop, value);
            }
        }
    }

    void setNeighbors(WrappedWebElement previous, WrappedWebElement next) {
        this.previous = previous;
        this.next = next;
    }

    void setCurrent(WrappedWebElement current) {
        this.current = current;
    }

    public WrappedWebElement getPrevious() {
        return previous;
    }

    public WrappedWebElement getNext() {
        return next;
    }

    public Map<String, String> getAttributeSnapshot() {
        return Map.copyOf(attributeSnapshot);
    }

    public Map<String, String> getCssSnapshot() {
        return Map.copyOf(cssSnapshot);
    }

    public String getSnapshotText() {
        return snapshotText;
    }

    // ---------------------------------------------------------------------
    // internal helpers
    // ---------------------------------------------------------------------

    /** Follow current → latest generation wrapper; null if index no longer exists. */
    private WrappedWebElement resolveCurrent() {
        WrappedWebElement w = this;
        while (w.current != null && w.current != w) {
            w = w.current;
        }
        return (w.current == null) ? null : w;
    }

    /** Core helper for active operations: run with live delegate; heal on stale. */
    private <R> R withFreshElement(ElementOp<R> op) {
        WrappedWebElement currentWrapper = resolveCurrent();
        if (currentWrapper == null) {
            throw new NoSuchElementException(
                    "Element at index " + index + " no longer exists after refresh");
        }

        WebElement delegate = parent.getDelegate(currentWrapper.index);
        try {
            return op.apply(delegate);
        } catch (StaleElementReferenceException | NoSuchElementException e) {
            // auto refresh whole chain; retry once
            parent.refreshAllWithRetry(3);
            currentWrapper = resolveCurrent();
            if (currentWrapper == null) {
                throw new NoSuchElementException(
                        "Element at index " + index + " no longer exists after refresh");
            }
            delegate = parent.getDelegate(currentWrapper.index);
            return op.apply(delegate);
        }
    }

    private String safeString(Supplier<String> supplier) {
        try {
            String s = supplier.get();
            return (s == null) ? "" : s;
        } catch (WebDriverException e) {
            return "";
        }
    }

    private boolean safeBoolean(Supplier<Boolean> supplier) {
        try {
            Boolean b = supplier.get();
            return Boolean.TRUE.equals(b);
        } catch (WebDriverException e) {
            return false;
        }
    }

    private Point safePoint(Supplier<Point> supplier) {
        try {
            Point p = supplier.get();
            return (p == null) ? new Point(0, 0) : p;
        } catch (WebDriverException e) {
            return new Point(0, 0);
        }
    }

    private Dimension safeDimension(Supplier<Dimension> supplier) {
        try {
            Dimension d = supplier.get();
            return (d == null) ? new Dimension(0, 0) : d;
        } catch (WebDriverException e) {
            return new Dimension(0, 0);
        }
    }

    private Rectangle safeRect(Supplier<Rectangle> supplier) {
        try {
            Rectangle r = supplier.get();
            return (r == null) ? new Rectangle(0, 0, 0, 0) : r;
        } catch (WebDriverException e) {
            return new Rectangle(0, 0, 0, 0);
        }
    }

    @FunctionalInterface
    private interface ElementOp<R> {
        R apply(WebElement element);
    }

    // ---------------------------------------------------------------------
    // WebElement implementation – passive methods use snapshot only
    // ---------------------------------------------------------------------

    @Override
    public String getTagName() {
        return snapshotTagName;
    }

    @Override
    public String getText() {
        return snapshotText;
    }

    @Override
    public boolean isDisplayed() {
        return snapshotDisplayed;
    }

    @Override
    public boolean isEnabled() {
        return snapshotEnabled;
    }

    @Override
    public boolean isSelected() {
        return snapshotSelected;
    }

    @Override
    public String getAttribute(String name) {
        if (name == null) return "";
        // try snapshot first
        String val = attributeSnapshot.get(name);
        return (val == null) ? "" : val;
    }

    @Override
    public Point getLocation() {
        return snapshotLocation;
    }

    @Override
    public Dimension getSize() {
        return snapshotSize;
    }

    @Override
    public Rectangle getRect() {
        return snapshotRect;
    }

    @Override
    public String getCssValue(String propertyName) {
        if (propertyName == null) return "";
        String val = cssSnapshot.get(propertyName);
        return (val == null) ? "" : val;
    }

    // ---------------------------------------------------------------------
    // WebElement implementation – active operations use live delegate
    // ---------------------------------------------------------------------

    @Override
    public void click() {
        withFreshElement(el -> { el.click(); return null; });
    }

    @Override
    public void submit() {
        withFreshElement(el -> { el.submit(); return null; });
    }

    @Override
    public void sendKeys(CharSequence... keysToSend) {
        withFreshElement(el -> { el.sendKeys(keysToSend); return null; });
    }

    @Override
    public void clear() {
        withFreshElement(el -> { el.clear(); return null; });
    }

    @Override
    public java.util.List<WebElement> findElements(By by) {
        return withFreshElement(el -> el.findElements(by));
    }

    @Override
    public WebElement findElement(By by) {
        return withFreshElement(el -> el.findElement(by));
    }

    @Override
    public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
        // treated as "active": this still talks to the browser directly
        return withFreshElement(el -> el.getScreenshotAs(target));
    }
}
