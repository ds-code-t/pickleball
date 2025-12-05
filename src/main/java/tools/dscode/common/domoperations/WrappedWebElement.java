//package tools.dscode.common.domoperations;
//
//import org.jspecify.annotations.Nullable;
//import org.openqa.selenium.*;
//import org.openqa.selenium.interactions.Interactive;
//import org.openqa.selenium.interactions.Sequence;
//
//import java.util.Collection;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.function.Supplier;
//
//
//public class WrappedWebElement extends WrappedWebDriver  {
//
//    // ---------------------------------------------------------------------
//    // Core linkage
//    // ---------------------------------------------------------------------
//
//    private int index;
//
//    /** Cached live delegate. Prefer this for direct DOM interactions. */
//    private WebElement delegate;
//
//    // linked list within one generation (optional but handy)
//    private WrappedWebElement previous;
//    private WrappedWebElement next;
//
//    // updated by parent on refresh:
//    //  - latest generation: current == this
//    //  - older: current == new wrapper at same index or null if index no longer exists
//    private WrappedWebElement current;
//
//    // ---- snapshot fields ----
//    private String snapshotTagName;
//    private String snapshotText;
//    private boolean snapshotDisplayed;
//    private boolean snapshotEnabled;
//    private boolean snapshotSelected;
//
//    private final Map<String, String> attributeSnapshot = new HashMap<>();
//    private final Map<String, String> cssSnapshot       = new HashMap<>();
//
//    private Point snapshotLocation;
//    private Dimension snapshotSize;
//    private Rectangle snapshotRect;
//
//    // a finite list of attributes we consider "major" for caching
//    private static final List<String> COMMON_ATTRIBUTES = List.of(
//            "id", "name", "class", "value", "type",
//            "href", "src", "alt", "title", "style",
//            "role", "data-test", "data-testid", "aria-label", "aria-hidden"
//    );
//
//    // a finite list of CSS properties we snapshot up front
//    private static final List<String> COMMON_CSS_PROPS = List.of(
//            "display", "visibility", "opacity", "pointer-events",
//            "position", "z-index"
//    );
//
//
//
//    public WrappedWebElement( SearchContext searchContext) {
//        super(searchContext);
//    }
//
//    public WrappedWebElement(XPathChainResult parent, int index, WebElement delegate) {
//        super(parent.searchContext);
//        this.parent  = parent;
//        this.index   = index;
//        this.current = this;
//        this.delegate = delegate;
//
//
//        // one place that populates all snapshot state
//        captureSnapshotFrom(delegate);
//    }
//
//    // ---------------------------------------------------------------------
//    // Neighbor & generation wiring
//    // ---------------------------------------------------------------------
//
//    public void setNeighbors(WrappedWebElement previous, WrappedWebElement next) {
//        this.previous = previous;
//        this.next = next;
//    }
//
//    public void setCurrent(WrappedWebElement current) {
//        this.current = current;
//    }
//
//    public WrappedWebElement getPrevious() {
//        return previous;
//    }
//
//    public WrappedWebElement getNext() {
//        return next;
//    }
//
//    public Map<String, String> getAttributeSnapshot() {
//        return Map.copyOf(attributeSnapshot);
//    }
//
//    public Map<String, String> getCssSnapshot() {
//        return Map.copyOf(cssSnapshot);
//    }
//
//    public String getSnapshotText() {
//        return snapshotText;
//    }
//
//    // ---------------------------------------------------------------------
//    // WrapsElement
//    // ---------------------------------------------------------------------
//
//    @Override
//    public WebElement getWrappedElement() {
//        // Return the live delegate for JS/Actions use
//        WrappedWebElement currentWrapper = resolveCurrent();
//        if (currentWrapper == null) {
//            throw new NoSuchElementException(
//                    "Element at index " + index + " no longer exists after refresh"
//            );
//        }
//
//        // Prefer the cached delegate if present; otherwise lazily obtain one
//        if (currentWrapper.delegate == null) {
//            WebElement fresh = parent.getDelegate(currentWrapper.index);
//            currentWrapper.delegate = fresh;
//            // Optional: you could re-snapshot here as well
//            // captureSnapshotFrom(fresh);
//        }
//
//        return currentWrapper.delegate;
//    }
//
//    /**
//     * Returns a WebElement that is guaranteed to be non-stale at the time
//     * of return (or throws if the element no longer exists).
//     *
//     * It first tries the currently wrapped element, and if that is stale
//     * or missing, it refreshes via withFreshElement(...) and returns the
//     * fresh delegate.
//     */
//    public WebElement getNonStaleWrappedElement() {
//        try {
//            WebElement el = getWrappedElement();
//            // Lightweight ping to ensure it's not stale
//            el.isEnabled();  // or el.getTagName();
//            return el;
//        } catch (StaleElementReferenceException | NoSuchElementException e) {
//            // Fallback: refresh using existing logic and return the fresh delegate
//            return withFreshElement(element -> element);
//        }
//    }
//
//
//    // ---------------------------------------------------------------------
//    // internal helpers
//    // ---------------------------------------------------------------------
//
//    /** Follow current → latest generation wrapper; null if index no longer exists. */
//    private WrappedWebElement resolveCurrent() {
//        WrappedWebElement w = this;
//        while (w.current != null && w.current != w) {
//            w = w.current;
//        }
//        return (w.current == null) ? null : w;
//    }
//
//    /**
//     * Capture all snapshot fields from the given delegate.
//     * This is used both in the constructor and after a delegate refresh.
//     */
//    private void captureSnapshotFrom(WebElement delegate) {
//        // basic passive data
//        this.snapshotTagName   = safeString(delegate::getTagName);
//        this.snapshotText      = safeString(delegate::getText);
//        this.snapshotDisplayed = safeBoolean(delegate::isDisplayed);
//        this.snapshotEnabled   = safeBoolean(delegate::isEnabled);
//        this.snapshotSelected  = safeBoolean(delegate::isSelected);
//
//        // common attributes
//        attributeSnapshot.clear();
//        for (String name : COMMON_ATTRIBUTES) {
//            String value = safeString(() -> delegate.getAttribute(name));
//            if (!value.isEmpty()) {
//                attributeSnapshot.put(name, value);
//            }
//        }
//
//        // snapshot some basic geometry
//        this.snapshotLocation = safePoint(delegate::getLocation);
//        this.snapshotSize     = safeDimension(delegate::getSize);
//        this.snapshotRect     = safeRect(delegate::getRect);
//
//        // a small set of CSS properties
//        cssSnapshot.clear();
//        for (String prop : COMMON_CSS_PROPS) {
//            String value = safeString(() -> delegate.getCssValue(prop));
//            if (!value.isEmpty()) {
//                cssSnapshot.put(prop, value);
//            }
//        }
//    }
//
//    /**
//     * Run an operation against the currently cached delegate
//     * (on the latest-generation wrapper). Does NOT refresh.
//     */
//    private <R> R withStoredElement(ElementOp<R> op) {
//        WrappedWebElement currentWrapper = resolveCurrent();
//        if (currentWrapper == null) {
//            throw new NoSuchElementException(
//                    "Element at index " + index + " no longer exists after refresh");
//        }
//
//        WebElement live = currentWrapper.delegate;
//        if (live == null) {
//            // Treat as stale / missing so caller can decide to refresh.
//            throw new StaleElementReferenceException(
//                    "No cached delegate available for element at index " + index);
//        }
//
//        return op.apply(live);
//    }
//
//    /**
//     * Core helper for active operations: get a fresh delegate from the parent,
//     * recapture snapshot from it, and run the op. If the delegate appears stale,
//     * it triggers a full refresh on the parent and retries once.
//     */
//    private <R> R withFreshElement(ElementOp<R> op) {
//        WrappedWebElement currentWrapper = resolveCurrent();
//        if (currentWrapper == null) {
//            throw new NoSuchElementException(
//                    "Element at index " + index + " no longer exists after refresh");
//        }
//
//        try {
//            WebElement live = parent.getDelegate(currentWrapper.index);
//            currentWrapper.delegate = live;
//            currentWrapper.captureSnapshotFrom(live);
//            return op.apply(live);
//        } catch (StaleElementReferenceException | NoSuchElementException e) {
//            // auto refresh whole chain; retry once
//            parent.refreshAllWithRetry(3);
//            currentWrapper = resolveCurrent();
//            if (currentWrapper == null) {
//                throw new NoSuchElementException(
//                        "Element at index " + index + " no longer exists after refresh");
//            }
//            WebElement live = parent.getDelegate(currentWrapper.index);
//            currentWrapper.delegate = live;
//            currentWrapper.captureSnapshotFrom(live);
//            return op.apply(live);
//        }
//    }
//
//    /**
//     * Wrapper for active operations: try cached delegate first, then fall back
//     * to withFreshElement on stale / no-such-element.
//     */
//    private <R> R doActiveOp(ElementOp<R> op) {
//        try {
//            return withStoredElement(op);
//        } catch (StaleElementReferenceException | NoSuchElementException e) {
//            return withFreshElement(op);
//        }
//    }
//
//    private String safeString(Supplier<String> supplier) {
//        try {
//            String s = supplier.get();
//            return (s == null) ? "" : s;
//        } catch (WebDriverException e) {
//            return "";
//        }
//    }
//
//    private boolean safeBoolean(Supplier<Boolean> supplier) {
//        try {
//            Boolean b = supplier.get();
//            return Boolean.TRUE.equals(b);
//        } catch (WebDriverException e) {
//            return false;
//        }
//    }
//
//    private Point safePoint(Supplier<Point> supplier) {
//        try {
//            Point p = supplier.get();
//            return (p == null) ? new Point(0, 0) : p;
//        } catch (WebDriverException e) {
//            return new Point(0, 0);
//        }
//    }
//
//    private Dimension safeDimension(Supplier<Dimension> supplier) {
//        try {
//            Dimension d = supplier.get();
//            return (d == null) ? new Dimension(0, 0) : d;
//        } catch (WebDriverException e) {
//            return new Dimension(0, 0);
//        }
//    }
//
//    private Rectangle safeRect(Supplier<Rectangle> supplier) {
//        try {
//            Rectangle r = supplier.get();
//            return (r == null) ? new Rectangle(0, 0, 0, 0) : r;
//        } catch (WebDriverException e) {
//            return new Rectangle(0, 0, 0, 0);
//        }
//    }
//
//    @Override
//    public void perform(Collection<Sequence> actions) {
//        doActiveOp(el ->  {
//            ((Interactive) el).perform(actions);
//            return null;
//        });
//    }
//
//    @Override
//    public void resetInputState() {
//        doActiveOp(el ->  {
//            ((Interactive) el).resetInputState();
//            return null;
//        });
//    }
//
//    @FunctionalInterface
//    private interface ElementOp<R> {
//        R apply(WebElement element);
//    }
//
//    // ---------------------------------------------------------------------
//    // WebElement implementation – passive methods use snapshot only
//    // ---------------------------------------------------------------------
//
//    @Override
//    public String getTagName() {
//        return snapshotTagName;
//    }
//
//    @Override
//    public String getText() {
//        return snapshotText;
//    }
//
//    @Override
//    public boolean isDisplayed() {
//        return snapshotDisplayed;
//    }
//
//    @Override
//    public boolean isEnabled() {
//        return snapshotEnabled;
//    }
//
//    @Override
//    public boolean isSelected() {
//        return snapshotSelected;
//    }
//
//    @Override
//    public String getAttribute(String name) {
//        if (name == null) return "";
//        // try snapshot first
//        String val = attributeSnapshot.get(name);
//        return (val == null) ? "" : val;
//    }
//
//    @Override
//    public Point getLocation() {
//        return snapshotLocation;
//    }
//
//    @Override
//    public Dimension getSize() {
//        return snapshotSize;
//    }
//
//    @Override
//    public Rectangle getRect() {
//        return snapshotRect;
//    }
//
//    @Override
//    public String getCssValue(String propertyName) {
//        if (propertyName == null) return "";
//        String val = cssSnapshot.get(propertyName);
//        return (val == null) ? "" : val;
//    }
//
//    // ---------------------------------------------------------------------
//    // WebElement implementation – active operations use cached delegate
//    // with fallback to refreshed delegate
//    // ---------------------------------------------------------------------
//
//    @Override
//    public void click() {
//        doActiveOp(el -> { el.click(); return null; });
//    }
//
//    @Override
//    public void submit() {
//        doActiveOp(el -> { el.submit(); return null; });
//    }
//
//    @Override
//    public void sendKeys(CharSequence... keysToSend) {
//        doActiveOp(el -> { el.sendKeys(keysToSend); return null; });
//    }
//
//    @Override
//    public void clear() {
//        doActiveOp(el -> { el.clear(); return null; });
//    }
//
//    @Override
//    public List<WebElement> findElements(By by) {
//        if(delegate==null) return  searchContext.findElements(by);
//        return doActiveOp(el -> el.findElements(by));
//    }
//
//    @Override
//    public WebElement findElement(By by) {
//        if(delegate==null) return  searchContext.findElement(by);
//        return doActiveOp(el -> el.findElement(by));
//    }
//
//    @Override
//    public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
//        // treated as "active": this still talks to the browser directly
//        return doActiveOp(el -> el.getScreenshotAs(target));
//    }
//
//
//    // default method Overrides
//
//    @Override
//    public  @Nullable String getDomProperty(String name) {
//        return doActiveOp(el -> el.getDomProperty(name));
//    }
//
//    @Override
//    public @Nullable String getDomAttribute(String name) {
//        return doActiveOp(el -> el.getDomAttribute(name));
//    }
//
//    @Override
//    public @Nullable String getAriaRole() {
//        return doActiveOp(WebElement::getAriaRole);
//    }
//
//
//    @Override
//    public @Nullable String getAccessibleName() {
//        return doActiveOp(WebElement::getAccessibleName);
//    }
//
//    @Override
//    public SearchContext getShadowRoot() {
//        if(delegate == null)
//            findElement(new By.ByXPath("//body")).getShadowRoot();
//        return doActiveOp(WebElement::getShadowRoot);
//    }
//
//}
