package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.domcomposite.StaleException;
import org.openqa.selenium.*;
import tools.dscode.common.composite.CompositeWebElement;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.assertions.ValueWrapper;

import java.time.Duration;
import java.util.*;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForElementReady;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.safeDomProperty;
import static tools.dscode.common.domoperations.elementstates.BinaryStateConditions.isCheckedSelectedOrOn;
import static tools.dscode.common.domoperations.elementstates.CollapsedExpandedConditions.isCollapsedState;
import static tools.dscode.common.domoperations.elementstates.CollapsedExpandedConditions.isExpandedState;
import static tools.dscode.common.domoperations.elementstates.RequiredInputConditions.isElementRequired;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.parsedComponents.ElementMatch.ELEMENT_RETURN_VALUE;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.util.debug.DebugUtils.debugFlags;

/**
 * One live element and one {@link ElementSnapshot}.
 *
 * <p>The snapshot is taken once, when the wrapper is built. Reads
 * ({@link #snapshot()}, {@link #getTagName()}, {@link #getIndex()},
 * {@link #getSameTagIndex()}, {@link #getSnapshotValue(String)}, and the
 * deprecated {@link #getAttributeSnapshot()}) do not take another snapshot.
 * {@link #refreshSnapshot()} is the only refresh. {@link #takeSnapshot()}
 * calls it. A {@link org.openqa.selenium.StaleElementReferenceException} or a
 * dom-composite {@code StaleException} also calls it once, then retries the
 * operation on the replacement element.
 *
 * <p>Refresh finds exactly one node by {@link ElementSnapshot#stableSelector()}.
 * There is no second XPath and no overlap list. The node id is stable only
 * until the element is re-rendered. Zero matches or many matches fail with
 * {@code error:} and {@code fix:}. {@code siblingIndex} and {@code sameTagIndex}
 * are real positions, not hard-coded zeroes.
 *
 * <p>{@code pkb_composite=off} uses the same record and the same script as
 * composite mode. Selenium leaves {@code nodeId} and {@code bounds} null.
 */
public class ElementWrapper implements WebElement, WrapsElement {

    private static final String CENTER_SCROLL_SCRIPT =
            "arguments[0].scrollIntoView({block:'center',inline:'center'});";

    private final WebDriver driver;
    public WebElement element;
    private ElementSnapshot elementSnapshot;
    /**
     * Computed by {@link #getElementReturnValue()} and kept off the snapshot.
     * Null means not computed yet. An empty string is a computed blank.
     */
    private String elementReturnValue;
    private final Map<String, String> extraSnapshotValues = new HashMap<>();
    public final ElementMatch elementMatch;
    public final Integer matchIndex;
    public final boolean foreignContext;

    private static final Set<String> DOM_PROPERTY_FALLBACK_KEYS = Set.of(
            "innerHTML",
            "outerHTML",
            "innerText",
            "outerText",
            "textContent"
    );

    public static List<ElementWrapper> getWrappedElements(ElementMatch elementMatch) {
        if (elementMatch.searchesAllContexts()) {
            return AllContextsScan.find(elementMatch);
        }
        if (elementMatch.parentPhrase.contextElement != null)
            return Collections.singletonList(elementMatch.parentPhrase.contextElement);
        // A partner phrase has not executed yet, so it has no context wrapper.
        if (elementMatch.contextWrapper == null
                && elementMatch.elementTypes.contains(ElementType.HTML_TYPE)) {
            elementMatch.contextWrapper = new ContextWrapper(elementMatch);
        }
        SearchContext searchContext = elementMatch.contextWrapper.getFinalSearchContext();
        List<ElementWrapper> elementWrappers = new ArrayList<>();
        List<WebElement> elements = elementMatch.contextWrapper.getElements(searchContext);
        boolean singleElement = elementMatch.selectionType.isBlank();
        logTrace("getWrappedElements-elements: " + elements.size());
        int index = 0;
        for (WebElement element : elements) {
            ElementWrapper ew = new ElementWrapper(element, elementMatch, ++index);
            elementWrappers.add(ew);
            if (singleElement) break;
        }
        logTrace("getWrappedElements-elementWrappers: " + elementWrappers.size());
        return elementWrappers;
    }

    ElementWrapper(WebElement element, ElementMatch elementMatch, Integer matchIndex) {
        this(elementMatch.parentPhrase.getDriver(), element, elementMatch, matchIndex);
    }

    /**
     * Snapshot one element the caller already resolved.
     * Phrase search still goes through {@link #getWrappedElements(ElementMatch)}.
     */
    public static ElementWrapper forResolvedElement(WebDriver driver, WebElement element) {
        Objects.requireNonNull(driver, "driver");
        Objects.requireNonNull(element, "element");
        return new ElementWrapper(driver, element, ElementMatch.resolved("Text"), 1);
    }

    private ElementWrapper(WebDriver driver, WebElement element, ElementMatch elementMatch, Integer matchIndex) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.matchIndex = matchIndex;
        this.elementMatch = elementMatch;
        this.foreignContext = false;
        this.element = Objects.requireNonNull(element, "element must not be null");

        assignSnapshot(this.element, "create");
    }

    /**
     * A displayed match from another frame. It counts for a presence or wait check and
     * is not a live element.
     */
    public static ElementWrapper foreignPresence(ElementMatch elementMatch) {
        return new ElementWrapper(elementMatch);
    }

    private ElementWrapper(ElementMatch elementMatch) {
        this.driver = elementMatch.parentPhrase.getDriver();
        this.matchIndex = 0;
        this.elementMatch = elementMatch;
        this.foreignContext = true;
        this.element = null;
        this.elementSnapshot = ElementSnapshot.empty();
    }

    public static String foreignContextMessage(String category) {
        String name = category == null || category.isBlank() ? "Element" : category;
        return name + " matched in another frame and cannot be clicked, entered, saved, read, or used as a context.";
    }

    public void rejectIfForeignContext() {
        if (foreignContext) {
            String category = elementMatch == null ? null : elementMatch.category;
            throw new IllegalStateException(foreignContextMessage(category));
        }
    }

    /**
     * Explicit refresh. The same path stale recovery uses.
     * Reads do not call this.
     */
    public void takeSnapshot() {
        refreshSnapshot();
    }

    /**
     * Find the same node again by {@code stableSelector} and replace the
     * element and the snapshot. One lookup. Zero or many matches fail.
     * A node id is stable only until the element is re-rendered.
     */
    public void refreshSnapshot() {
        rejectIfForeignContext();
        if (element instanceof CompositeWebElement composite
                && tools.dscode.common.composite.ElementOperations.primary()
                && tools.dscode.common.composite.DomCompositeAdapter.get().live(composite)) {
            assignSnapshot(element, "refresh");
            return;
        }
        String selector = snapshot().stableSelector();
        String refreshMode = mode();
        String nodeId = snapshot().nodeId();
        if (selector == null || selector.isBlank()) {
            throw new IllegalStateException(refreshError(
                    refreshMode, selector, nodeId, "stableSelector was empty"));
        }
        safeWaitForPageReady(driver, Duration.ofSeconds(60));
        By by = locatorForStableSelector(selector);
        List<WebElement> found;
        try {
            found = withoutImplicitWait(
                    driver,
                    () -> tools.dscode.common.composite.ElementOperations.findElements(driver, by));
        } catch (RuntimeException failure) {
            throw new IllegalStateException(
                    refreshError(refreshMode, selector, nodeId, failure.getMessage()),
                    failure);
        }
        if (found.size() != 1) {
            throw new IllegalStateException(refreshError(
                    refreshMode,
                    selector,
                    nodeId,
                    "stableSelector matched " + found.size() + " nodes"));
        }
        WebElement refreshed = found.getFirst();
        safeWaitForElementReady(driver, refreshed, Duration.ofSeconds(60));
        this.element = refreshed;
        assignSnapshot(refreshed, "refresh");
    }

    private String mode() {
        if (element instanceof CompositeWebElement
                || tools.dscode.common.composite.ElementOperations.primary()) {
            return "composite";
        }
        return "selenium";
    }

    private static By locatorForStableSelector(String selector) {
        String trimmed = selector.trim();
        if (trimmed.startsWith("/") || trimmed.startsWith("(")) {
            return By.xpath(trimmed);
        }
        return By.cssSelector(trimmed);
    }

    private static String refreshError(String mode, String selector, String nodeId, String detail) {
        return "error: element snapshot could not be refreshed."
                + " mode=" + mode
                + " selector=" + (selector == null ? "" : selector)
                + " nodeId=" + (nodeId == null ? "" : nodeId)
                + " tried=stableSelector"
                + " detail=" + (detail == null ? "" : detail)
                + " fix: the node id is stable only until the element is re-rendered."
                + " Refresh looked up that stable selector once."
                + " Give the element a unique id, data-testid, data-test, data-user-id, name, title, or aria-label"
                + " so the selector still matches one node after re-render.";
    }

    /**
     * The snapshot taken at construction, or the last refresh. Never null
     * after construction. Reads do not take a new snapshot.
     */
    public ElementSnapshot snapshot() {
        return elementSnapshot == null ? ElementSnapshot.empty() : elementSnapshot;
    }

    private void assignSnapshot(WebElement snapshotElement, String event) {
        this.elementSnapshot = SnapshotProducers.capture(driver, snapshotElement, event);
        this.elementReturnValue = null;
        this.extraSnapshotValues.clear();
        if (debugFlags.contains("elementsnapshot")) {
            logTrace(attributeView().toPrettyString());
        }
    }

    /**
     * JSON view of the snapshot, rebuilt on each call. The return-value cache
     * is included when it has been computed. Prefer {@link #snapshot()}.
     *
     * @deprecated Callers should read {@link #snapshot()}. This view stays so
     * older JSON field names ({@code tagName}, {@code textContent},
     * {@code childValue}) keep working.
     */
    @Deprecated
    public ObjectNode getAttributeSnapshot() {
        return attributeView();
    }

    private ObjectNode attributeView() {
        ElementSnapshot shot = snapshot();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("tagName", shot.tag());
        node.put("textContent", shot.text());
        if (shot.selectedValue() != null) node.put("childValue", shot.selectedValue());
        ObjectNode attributes = node.putObject("attributes");
        shot.attributes().forEach(attributes::put);
        node.put("siblingIndex", shot.siblingIndex());
        node.put("sameTagIndex", shot.sameTagIndex());
        if (shot.nodeId() != null) node.put("nodeId", shot.nodeId());
        if (!shot.stableSelector().isEmpty()) node.put("stableSelector", shot.stableSelector());
        if (elementReturnValue != null) node.put(ELEMENT_RETURN_VALUE, elementReturnValue);
        extraSnapshotValues.forEach((key, value) -> {
            if (key.startsWith("attributes.")) attributes.put(key.substring("attributes.".length()), value);
            else node.put(key, value);
        });
        return node;
    }

    public ValueWrapper getElementReturnValue() {
        rejectIfForeignContext();
        if (elementReturnValue != null) return createValueWrapper(elementReturnValue);

        scrollIntoView();
        if (elementMatch != null && "Field".equals(elementMatch.category)) {
            List<WebElement> valueElements = withoutImplicitWait(
                    driver,
                    () -> findElements(By.xpath("descendant::*[contains(@class,'Read')]"))
            );
            if (!valueElements.isEmpty()) {
                return cacheReturn(tools.dscode.common.composite.ElementOperations.text(valueElements.getLast()));
            }
            if (getTagName().equals("td")) {
                return cacheReturn(getText());
            }
            valueElements = withoutImplicitWait(
                    driver,
                    () -> findElements(By.xpath("*[normalize-space(.)][1]/following-sibling::*[descendant-or-self::*[text()]]"))
            );
            if (valueElements.size() == 1) {
                return cacheReturn(tools.dscode.common.composite.ElementOperations.text(valueElements.getLast()));
            }
        }

        List<String> keys = elementMatch == null || elementMatch.defaultValueKeys == null
                ? List.of()
                : elementMatch.defaultValueKeys;
        for (String key : keys) {
            if (key == null || ELEMENT_RETURN_VALUE.equals(key)) continue;
            String found = lookupDefaultKey(key);
            if (found != null) return cacheReturn(found);
        }
        return cacheReturn("");
    }

    private ValueWrapper cacheReturn(String returnVal) {
        elementReturnValue = returnVal == null ? "" : returnVal;
        return createValueWrapper(elementReturnValue);
    }

    private String lookupDefaultKey(String key) {
        if (key.startsWith("attributes.")) {
            String name = key.substring("attributes.".length());
            if (snapshot().hasAttribute(name)) return snapshot().attribute(name);
            if (DOM_PROPERTY_FALLBACK_KEYS.contains(name)) return propertyFallback(name, true);
            return null;
        }
        if ("childValue".equals(key) || "selectedValue".equals(key)) return snapshot().selectedValue();
        if ("textContent".equals(key) || "text".equals(key)) return snapshot().text();
        if ("tagName".equals(key) || "tag".equals(key)) return snapshot().tag();
        if ("siblingIndex".equals(key)) return Integer.toString(snapshot().siblingIndex());
        if ("sameTagIndex".equals(key)) return Integer.toString(snapshot().sameTagIndex());
        if ("stableSelector".equals(key)) return snapshot().stableSelector();
        if ("nodeId".equals(key)) return snapshot().nodeId();
        if (DOM_PROPERTY_FALLBACK_KEYS.contains(key)) return propertyFallback(key, false);
        return null;
    }

    private String propertyFallback(String key, boolean attribute) {
        String returnVal = executeWithStaleRetry(currentElement -> safeDomProperty(currentElement, key));
        if (returnVal == null) return null;
        extraSnapshotValues.put(attribute ? "attributes." + key : key, returnVal);
        return returnVal;
    }

    private static <T> T withoutImplicitWait(WebDriver driver,
                                             java.util.function.Supplier<T> action) {
        Duration original = driver.manage().timeouts().getImplicitWaitTimeout();
        try {
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);
            return action.get();
        } finally {
            driver.manage().timeouts().implicitlyWait(original);
        }
    }

    private <T> T executeWithStaleRetry(java.util.function.Function<WebElement, T> operation) {
        rejectIfForeignContext();
        WebElement currentElement = this.element;
        try {
            return operation.apply(currentElement);
        } catch (StaleElementReferenceException | StaleException stale) {
            refreshSnapshot();
            return operation.apply(this.element);
        }
    }

    private void executeVoidWithStaleRetry(java.util.function.Consumer<WebElement> operation) {
        executeWithStaleRetry(currentElement -> {
            operation.accept(currentElement);
            return null;
        });
    }

    private static Object[] prependElement(WebElement currentElement, Object[] arguments) {
        Object[] additionalArguments = arguments == null ? new Object[0] : arguments;
        Object[] combinedArguments = new Object[additionalArguments.length + 1];
        combinedArguments[0] = currentElement;
        System.arraycopy(additionalArguments, 0, combinedArguments, 1, additionalArguments.length);
        return combinedArguments;
    }

    @Override
    public WebElement getWrappedElement() {
        return element;
    }

    /**
     * Compatibility alias for callers using the original ElementWrapper API.
     * New framework code should normally use the wrapper directly.
     */
    public WebElement getElement() {
        return getWrappedElement();
    }

    /**
     * Executes JavaScript with this wrapper's current element supplied as arguments[0].
     * Any additional arguments begin at arguments[1].
     */
    public Object executeScript(String script, Object... arguments) {
        Objects.requireNonNull(script, "script must not be null");
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.script(driver, script, prependElement(currentElement, arguments)));
    }

    /**
     * Executes asynchronous JavaScript with this wrapper's current element supplied as arguments[0].
     * Any additional arguments begin at arguments[1].
     */
    public Object executeAsyncScript(String script, Object... arguments) {
        Objects.requireNonNull(script, "script must not be null");
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.asyncScript(driver, script, prependElement(currentElement, arguments)));
    }

    @Override
    public void click() {
        executeVoidWithStaleRetry(current ->
                tools.dscode.common.composite.ElementOperations.click(driver, current));
    }

    @Override
    public void submit() {
        executeVoidWithStaleRetry(current ->
                tools.dscode.common.composite.ElementOperations.submit(driver, current));
    }

    @Override
    public void sendKeys(CharSequence... keysToSend) {
        executeVoidWithStaleRetry(current ->
                tools.dscode.common.composite.ElementOperations.sendKeys(driver, current, keysToSend));
    }

    @Override
    public void clear() {
        executeVoidWithStaleRetry(current ->
                tools.dscode.common.composite.ElementOperations.clear(driver, current));
    }

    @Override
    public String getDomProperty(String name) {
        if ("value".equals(name)) return snapshot().value();
        if (snapshot().hasAttribute(name)) return snapshot().attribute(name);
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.domProperty(currentElement, name));
    }

    @Override
    public String getDomAttribute(String name) {
        if (snapshot().hasAttribute(name)) return snapshot().attribute(name);
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.domAttribute(currentElement, name));
    }

    @Override
    public String getAttribute(String name) {
        if (snapshot().hasAttribute(name)) return snapshot().attribute(name);
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.attribute(currentElement, name));
    }

    @Override
    public String getAriaRole() {
        return executeWithStaleRetry(WebElement::getAriaRole);
    }

    @Override
    public String getAccessibleName() {
        return executeWithStaleRetry(WebElement::getAccessibleName);
    }

    @Override
    public boolean isSelected() {
        return executeWithStaleRetry(current ->
                tools.dscode.common.composite.ElementOperations.selected(current));
    }

    @Override
    public String getText() {
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.text(currentElement));
    }

    @Override
    public List<WebElement> findElements(By by) {
        Objects.requireNonNull(by, "by must not be null");
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.findElements(currentElement, by));
    }

    @Override
    public WebElement findElement(By by) {
        Objects.requireNonNull(by, "by must not be null");
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.findElement(currentElement, by));
    }

    @Override
    public SearchContext getShadowRoot() {
        if (tools.dscode.common.composite.ElementOperations.primary()) {
            return tools.dscode.common.composite.ElementOperations.shadowRoot(element);
        }
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.shadowRoot(currentElement));
    }

    @Override
    public Point getLocation() {
        if (element instanceof tools.dscode.common.composite.CompositeWebElement) {
            return tools.dscode.common.composite.ElementOperations.location(element);
        }
        return executeWithStaleRetry(current -> tools.dscode.common.composite.ElementOperations.location(current));
    }

    @Override
    public Dimension getSize() {
        if (element instanceof tools.dscode.common.composite.CompositeWebElement) {
            return tools.dscode.common.composite.ElementOperations.size(element);
        }
        return executeWithStaleRetry(current -> tools.dscode.common.composite.ElementOperations.size(current));
    }

    @Override
    public Rectangle getRect() {
        if (element instanceof tools.dscode.common.composite.CompositeWebElement) {
            return tools.dscode.common.composite.ElementOperations.rect(element);
        }
        return executeWithStaleRetry(current -> tools.dscode.common.composite.ElementOperations.rect(current));
    }

    @Override
    public String getCssValue(String propertyName) {
        return executeWithStaleRetry(currentElement ->
                tools.dscode.common.composite.ElementOperations.css(currentElement, propertyName));
    }

    @Override
    public <X> X getScreenshotAs(OutputType<X> target) throws WebDriverException {
        return executeWithStaleRetry(currentElement -> currentElement.getScreenshotAs(target));
    }

    /**
     * Best-effort element-scoped center scrolling. Stale-element failures are relocated and the
     * complete script is retried once by executeScript. A second stale failure is propagated;
     * other WebDriver scrolling failures remain non-fatal.
     */
    public void scrollIntoView() {
        if (element instanceof tools.dscode.common.composite.CompositeWebElement) {
            try {
                tools.dscode.common.composite.ElementOperations.scroll(driver, element, 0);
            } catch (RuntimeException ignored) {
                // The composite node is already a live hit. A scroll miss must not fail presence.
            }
            return;
        }
        try {
            executeScript(CENTER_SCROLL_SCRIPT);
        } catch (StaleElementReferenceException stale) {
            throw stale;
        } catch (WebDriverException ignored) {
            // Preserve best-effort scrolling for non-stale WebDriver failures.
        }
    }

    @Override
    public boolean isDisplayed() {
        if (foreignContext) {
            return true;
        }
        if (elementMatch.categoryFlags.contains(
                ExecutionDictionary.CategoryFlags.NON_DISPLAY_ELEMENT))
            return true;
        scrollIntoView();
        return executeWithStaleRetry(current -> tools.dscode.common.composite.ElementOperations.displayed(current));
    }

    public boolean screenReaderOnlyCheck() {
        if (possibleScreenReaderElement()) {
            return !executeWithStaleRetry(current -> tools.dscode.common.composite.ElementOperations.displayed(current));
        }
        return false;
    }

    public boolean possibleScreenReaderElement() {
        if (snapshotContainsAnyAttribute("aria-live", "aria-atomic", "aria-relevant")) {
            return true;
        }
        return snapshotAttributeEqualsIgnoreCase(
                "role",
                "status",
                "alert",
                "log",
                "timer",
                "marquee"
        );
    }

    @Override
    public boolean isEnabled() {
        return executeWithStaleRetry(current -> tools.dscode.common.composite.ElementOperations.enabled(current));
    }

    private static boolean isRequiredElement(WebElement currentElement) {
        return isElementRequired(currentElement);
    }

    private static boolean isExpandedElement(WebElement currentElement) {
        return isExpandedState(currentElement);
    }

    private static boolean isCollapsedElement(WebElement currentElement) {
        return isCollapsedState(currentElement);
    }

    private static boolean isOnElement(WebElement currentElement) {
        return isCheckedSelectedOrOn(currentElement);
    }

    public boolean isRequired() {
        return executeWithStaleRetry(ElementWrapper::isRequiredElement);
    }

    public boolean isExpanded() {
        return executeWithStaleRetry(ElementWrapper::isExpandedElement);
    }

    public boolean isCollapsed() {
        return executeWithStaleRetry(ElementWrapper::isCollapsedElement);
    }

    public boolean isOn() {
        return executeWithStaleRetry(ElementWrapper::isOnElement);
    }

    public boolean isOff() {
        return !executeWithStaleRetry(ElementWrapper::isOnElement);
    }

    public boolean isBlank() {
        return getElementReturnValue().isBlank();
    }

    public boolean hasValue() {
        return !getElementReturnValue().isBlank();
    }

    public void close() {
        String closeXpath = getExecutionDictionary()
                .getCategoryXPathy("Close Button")
                .getXpath()
                .replaceFirst("^//\\*", "descendant-or-self::*");
        executeVoidWithStaleRetry(currentElement -> {
            WebElement closeButton = tools.dscode.common.composite.ElementOperations.findElement(currentElement, new By.ByXPath(closeXpath));
            tools.dscode.common.composite.ElementOperations.click(driver, closeButton);
        });
    }

    public String getSnapshotAttribute(String name) {
        if (name == null) return null;
        if (snapshot().hasAttribute(name)) return snapshot().attribute(name);
        return extraSnapshotValues.get("attributes." + name);
    }

    public boolean snapshotAttributeEqualsIgnoreCase(String name, String... candidates) {
        if (candidates == null) return false;
        String value = getSnapshotAttribute(name);
        if (value == null) return false;
        for (String candidate : candidates) {
            if (candidate != null && value.trim().equalsIgnoreCase(candidate.trim())) return true;
        }
        return false;
    }

    public boolean snapshotContainsAnyAttribute(String... keys) {
        if (keys == null || keys.length == 0) return false;
        for (String key : keys) {
            if (key != null && (snapshot().hasAttribute(key) || extraSnapshotValues.containsKey("attributes." + key))) {
                return true;
            }
        }
        return false;
    }

    public String getSnapshotValue(String key) {
        if (key == null) return null;
        ElementSnapshot shot = snapshot();
        switch (key) {
            case "tagName":
            case "tag":
                return shot.tag();
            case "textContent":
            case "text":
                return shot.text();
            case "childValue":
            case "selectedValue":
                return shot.selectedValue();
            case "siblingIndex":
                return Integer.toString(shot.siblingIndex());
            case "sameTagIndex":
                return Integer.toString(shot.sameTagIndex());
            case "stableSelector":
                return shot.stableSelector();
            case "nodeId":
                return shot.nodeId();
            default:
                break;
        }
        if (extraSnapshotValues.containsKey(key)) return extraSnapshotValues.get(key);
        if (shot.hasAttribute(key)) return shot.attribute(key);
        return extraSnapshotValues.get("attributes." + key);
    }

    @Override
    public String getTagName() {
        return snapshot().tag();
    }

    public int getIndex() {
        return snapshot().siblingIndex();
    }

    public int getSameTagIndex() {
        return snapshot().sameTagIndex();
    }
}
