package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.domoperations.NestedByLocator;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;
import tools.dscode.common.assertions.ValueWrapper;

import java.time.Duration;
import java.util.*;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForElementReady;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.intersection;
import static tools.dscode.common.domoperations.SeleniumUtils.safeDomAttribute;
import static tools.dscode.common.domoperations.SeleniumUtils.safeDomProperty;
import static tools.dscode.common.domoperations.SeleniumUtils.union;
import static tools.dscode.common.domoperations.elementstates.BinaryStateConditions.isCheckedSelectedOrOn;
import static tools.dscode.common.domoperations.elementstates.CollapsedExpandedConditions.isCollapsedState;
import static tools.dscode.common.domoperations.elementstates.CollapsedExpandedConditions.isExpandedState;
import static tools.dscode.common.domoperations.elementstates.RequiredInputConditions.isElementRequired;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.treeparsing.DefinitionContext.getExecutionDictionary;
import static tools.dscode.common.treeparsing.parsedComponents.ElementMatch.ELEMENT_RETURN_VALUE;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.util.debug.DebugUtils.debugFlags;

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
    private final String xpath1;
    private final String xpath2;
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
        this.xpath1 = buildXPathForElement(driver, element, 10, 30,
                List.of("id", "data-user-id"),
                List.of("name", "title"),
                List.of("role", "aria-label", "class"));
        this.xpath2 = buildXPathForElement(driver, element, 10, 15,
                List.of("href", "target", "src", "index"));
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
        this.xpath1 = "";
        this.xpath2 = "";
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

    public void takeSnapshot() {
        executeWithStaleRetry(currentElement -> {
            assignSnapshot(currentElement, "refresh");
            return null;
        });
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
                return cacheReturn(valueElements.getLast().getText());
            }
            if (getTagName().equals("td")) {
                return cacheReturn(getText());
            }
            valueElements = withoutImplicitWait(
                    driver,
                    () -> findElements(By.xpath("*[normalize-space(.)][1]/following-sibling::*[descendant-or-self::*[text()]]"))
            );
            if (valueElements.size() == 1) {
                return cacheReturn(valueElements.getLast().getText());
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
        } catch (StaleElementReferenceException stale) {
            WebElement refreshedElement = refindElement();
            return operation.apply(refreshedElement);
        }
    }

    private void executeVoidWithStaleRetry(java.util.function.Consumer<WebElement> operation) {
        executeWithStaleRetry(currentElement -> {
            operation.accept(currentElement);
            return null;
        });
    }

    private JavascriptExecutor javascriptExecutor() {
        if (driver instanceof JavascriptExecutor js) {
            return js;
        }
        throw new IllegalArgumentException(
                "WebDriver must implement JavascriptExecutor to use ElementWrapper");
    }

    private static Object[] prependElement(WebElement currentElement, Object[] arguments) {
        Object[] additionalArguments = arguments == null ? new Object[0] : arguments;
        Object[] combinedArguments = new Object[additionalArguments.length + 1];
        combinedArguments[0] = currentElement;
        System.arraycopy(additionalArguments, 0, combinedArguments, 1, additionalArguments.length);
        return combinedArguments;
    }

    private WebElement refindElement() {
        safeWaitForPageReady(driver, Duration.ofSeconds(60));
        WebElement refreshedElement = refindUniqueElement();
        safeWaitForElementReady(driver, refreshedElement, Duration.ofSeconds(60));
        this.element = refreshedElement;
        assignSnapshot(refreshedElement, "refresh");
        return refreshedElement;
    }

    private WebElement refindUniqueElement() {
        List<WebElement> elementList1 = getElementList(driver, xpath1);
        if (elementList1.size() == 1) {
            return elementList1.getFirst();
        }

        List<WebElement> elementList2 = getElementList(driver, xpath2);
        if (elementList2.size() == 1) {
            return elementList2.getFirst();
        }

        List<WebElement> elementList3 = intersection(elementList1, elementList2);
        if (elementList3.size() == 1) {
            return elementList3.getFirst();
        }

        List<WebElement> elementList4 = getElementList(
                driver,
                elementMatch.contextWrapper.elementPath.getXpath()
        );
        if (elementList4.size() == 1) {
            return elementList4.getFirst();
        }

        if (elementList4.isEmpty()) {
            List<WebElement> elementList5 = union(elementList1, elementList2);
            if (elementList5.size() == 1) {
                return elementList5.getFirst();
            }
        }

        List<WebElement> elementList6 = intersection(elementList1, elementList4);
        if (elementList6.size() == 1) {
            return elementList6.getFirst();
        }

        elementList6 = intersection(elementList2, elementList4);
        if (elementList6.size() == 1) {
            return elementList6.getFirst();
        }

        elementList6 = intersection(union(elementList1, elementList2), elementList4);
        if (elementList6.size() == 1) {
            return elementList6.getFirst();
        }

        List<WebElement> elementList7 = getElementList(
                driver,
                elementMatch.contextWrapper.elementTerminalXPath.getXpath()
        );
        if (elementList7.size() == 1) {
            return elementList7.getFirst();
        }
        if (elementList7.size() > 1 && matchIndex != null) {
            List<WebElement> elementList8 = getElementList(
                    driver,
                    "(" + elementMatch.contextWrapper.elementTerminalXPath.getXpath() + ")[" + matchIndex + "]"
            );
            if (elementList8.size() == 1) {
                return elementList8.getFirst();
            }
        }

        throw new RuntimeException("Failed to relocate " + elementMatch);
    }

    private static String buildXPathForElement(
            WebDriver driver,
            WebElement element,
            int maxAncestorNodes,
            int maxDescendantNodes,
            String... attrPriority
    ) {
        String tag = safeTagName(element);

        String[] effectiveAttrs = (attrPriority == null || attrPriority.length == 0)
                ? new String[]{"id", "data-user-id"}
                : attrPriority;

        String descAttrName = null;
        String descAttrValue = null;

        outerDesc:
        for (String attr : effectiveAttrs) {
            String selfVal = getAttrOrEmpty(element, attr);
            if (!selfVal.isEmpty()) {
                descAttrName = attr;
                descAttrValue = selfVal;
                break;
            }

            try {
                String xpathExpr;
                if (maxDescendantNodes > 0) {
                    xpathExpr = "(.//*[@" + attr + "])[position() <= " + maxDescendantNodes + "]";
                } else {
                    xpathExpr = ".//*[@" + attr + "]";
                }
                WebElement d = withoutImplicitWait(
                        driver,
                        () -> tools.dscode.common.composite.ElementOperations.findElement(element, By.xpath(xpathExpr))
                );
                String v = getAttrOrEmpty(d, attr);
                if (!v.isEmpty()) {
                    descAttrName = attr;
                    descAttrValue = v;
                    break outerDesc;
                }
            } catch (NoSuchElementException ignored) {
            }
        }

        String mainPredicate;
        if (descAttrName != null) {
            mainPredicate = "descendant-or-self::*[@" + descAttrName + "="
                    + quoteForXPath(descAttrValue) + "]";
        } else {
            mainPredicate = buildChildrenShapePredicate(driver, element);
        }

        String ancAttrName = null;
        String ancAttrValue = null;

        outerAnc:
        for (String attr : effectiveAttrs) {
            WebElement current = element;
            int checkedAncestors = 0;
            while (true) {
                if (maxAncestorNodes > 0 && checkedAncestors >= maxAncestorNodes) {
                    break;
                }
                WebElement parent;
                try {
                    final WebElement currentForParentLookup = current;
                    parent = withoutImplicitWait(
                            driver,
                            () -> tools.dscode.common.composite.ElementOperations.findElement(currentForParentLookup, By.xpath("parent::*"))
                    );
                } catch (NoSuchElementException | InvalidSelectorException e) {
                    break;
                }
                if (parent.equals(current)) {
                    break;
                }

                current = parent;
                checkedAncestors++;
                String v = getAttrOrEmpty(current, attr);
                if (!v.isEmpty()) {
                    ancAttrName = attr;
                    ancAttrValue = v;
                    break outerAnc;
                }
            }
        }

        StringBuilder predicateBuilder = new StringBuilder();
        predicateBuilder.append(mainPredicate);
        if (ancAttrName != null) {
            predicateBuilder.append(" and ancestor-or-self::*[@")
                    .append(ancAttrName)
                    .append("=")
                    .append(quoteForXPath(ancAttrValue))
                    .append("]");
        }

        return "//" + tag + "[" + predicateBuilder + "]";
    }

    @SafeVarargs
    private static String buildXPathForElement(
            WebDriver driver,
            WebElement element,
            int maxAncestorNodes,
            int maxDescendantNodes,
            List<String>... attrPriorityGroups
    ) {
        String tag = safeTagName(element);
        List<List<String>> effectiveGroups =
                (attrPriorityGroups == null || attrPriorityGroups.length == 0)
                        ? List.of(List.of("id", "data-user-id"))
                        : Arrays.asList(attrPriorityGroups);

        String descAttrName = null;
        String descAttrValue = null;

        outerDesc:
        for (List<String> group : effectiveGroups) {
            for (String attr : group) {
                String selfVal = getAttrOrEmpty(element, attr);
                if (!selfVal.isEmpty()) {
                    descAttrName = attr;
                    descAttrValue = selfVal;
                    break outerDesc;
                }
            }

            try {
                String orPredicate = group.stream()
                        .map(a -> "@" + a)
                        .reduce((a, b) -> a + " or " + b)
                        .orElse(null);

                if (orPredicate == null) {
                    continue;
                }

                String xpathExpr;
                if (maxDescendantNodes > 0) {
                    xpathExpr = "(.//*[" + orPredicate + "])[position() <= " + maxDescendantNodes + "]";
                } else {
                    xpathExpr = ".//*[" + orPredicate + "]";
                }

                WebElement d = withoutImplicitWait(
                        driver,
                        () -> tools.dscode.common.composite.ElementOperations.findElement(element, By.xpath(xpathExpr))
                );
                for (String attr : group) {
                    String v = getAttrOrEmpty(d, attr);
                    if (!v.isEmpty()) {
                        descAttrName = attr;
                        descAttrValue = v;
                        break outerDesc;
                    }
                }
            } catch (NoSuchElementException ignored) {
            }
        }

        String mainPredicate;
        if (descAttrName != null) {
            mainPredicate = "descendant-or-self::*[@" + descAttrName + "="
                    + quoteForXPath(descAttrValue) + "]";
        } else {
            mainPredicate = buildChildrenShapePredicate(driver, element);
        }

        String ancAttrName = null;
        String ancAttrValue = null;

        outerAnc:
        for (List<String> group : effectiveGroups) {
            WebElement current = element;
            int checkedAncestors = 0;
            while (true) {
                if (maxAncestorNodes > 0 && checkedAncestors >= maxAncestorNodes) {
                    break;
                }
                WebElement parent;
                try {
                    final WebElement currentForParentLookup = current;
                    parent = withoutImplicitWait(
                            driver,
                            () -> tools.dscode.common.composite.ElementOperations.findElement(currentForParentLookup, By.xpath("parent::*"))
                    );
                } catch (NoSuchElementException | InvalidSelectorException e) {
                    break;
                }

                if (parent.equals(current)) {
                    break;
                }
                current = parent;
                checkedAncestors++;

                for (String attr : group) {
                    String v = getAttrOrEmpty(current, attr);
                    if (!v.isEmpty()) {
                        ancAttrName = attr;
                        ancAttrValue = v;
                        break outerAnc;
                    }
                }
            }
        }

        StringBuilder predicateBuilder = new StringBuilder();
        predicateBuilder.append(mainPredicate);
        if (ancAttrName != null) {
            predicateBuilder.append(" and ancestor-or-self::*[@")
                    .append(ancAttrName)
                    .append("=")
                    .append(quoteForXPath(ancAttrValue))
                    .append("]");
        }

        return "//" + tag + "[" + predicateBuilder + "]";
    }

    private static String buildXPathForElement(
            WebDriver driver,
            WebElement element,
            int maxAncestorDepth,
            int maxDescendantDepth) {
        return buildXPathForElement(
                driver,
                element,
                maxAncestorDepth,
                maxDescendantDepth,
                (String[]) null
        );
    }

    private static String buildChildrenShapePredicate(WebDriver driver, WebElement element) {
        List<WebElement> children = withoutImplicitWait(
                driver,
                () -> tools.dscode.common.composite.ElementOperations.findElements(element, By.xpath("./*"))
        );
        if (children.isEmpty()) {
            return "not(*)";
        }

        Set<String> tags = new LinkedHashSet<>();
        for (WebElement child : children) {
            tags.add(safeTagName(child));
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String t : tags) {
            if (!first) sb.append(" and ");
            sb.append("child::").append(t);
            first = false;
        }
        return sb.toString();
    }

    private static String safeTagName(WebElement element) {
        try {
            String tag = element.getTagName();
            return (tag == null || tag.isBlank()) ? "*" : tag.toLowerCase();
        } catch (WebDriverException e) {
            return "*";
        }
    }

    private static String getAttrOrEmpty(WebElement el, String name) {
        try {
            String v = safeDomAttribute(el, name);
            return v == null ? "" : v;
        } catch (WebDriverException e) {
            return "";
        }
    }

    private static String quoteForXPath(String value) {
        if (value == null) return "''";
        if (!value.contains("'")) return "'" + value + "'";
        if (!value.contains("\"")) return "\"" + value + "\"";
        StringBuilder sb = new StringBuilder("concat(");
        boolean first = true;
        for (char c : value.toCharArray()) {
            if (!first) sb.append(", ");
            if (c == '\'') sb.append("\"'\"");
            else if (c == '"') sb.append("'\"'");
            else sb.append("'").append(c).append("'");
            first = false;
        }
        sb.append(")");
        return sb.toString();
    }

    private List<WebElement> getElementList(WebDriver driver, String xpathyWithId) {
        logTrace("getElementList.xpathyWithId: " + xpathyWithId);
        return NestedByLocator.retainFullMatches(
                withoutImplicitWait(
                        driver,
                        () -> tools.dscode.common.composite.ElementOperations.findElements(
                                elementMatch.contextWrapper.getFinalSearchContext(),
                                new By.ByXPath(xpathyWithId))
                ),
                elementMatch
        );
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
        JavascriptExecutor js = javascriptExecutor();
        return executeWithStaleRetry(currentElement ->
                js.executeScript(script, prependElement(currentElement, arguments)));
    }

    /**
     * Executes asynchronous JavaScript with this wrapper's current element supplied as arguments[0].
     * Any additional arguments begin at arguments[1].
     */
    public Object executeAsyncScript(String script, Object... arguments) {
        Objects.requireNonNull(script, "script must not be null");
        JavascriptExecutor js = javascriptExecutor();
        return executeWithStaleRetry(currentElement ->
                js.executeAsyncScript(script, prependElement(currentElement, arguments)));
    }

    @Override
    public void click() {
        if (tools.dscode.common.composite.ElementOperations.primary()) {
            tools.dscode.common.composite.ElementOperations.click(driver, element);
            return;
        }
        executeVoidWithStaleRetry(WebElement::click);
    }

    @Override
    public void submit() {
        if (tools.dscode.common.composite.ElementOperations.primary()) {
            tools.dscode.common.composite.ElementOperations.click(driver, element);
            return;
        }
        executeVoidWithStaleRetry(WebElement::submit);
    }

    @Override
    public void sendKeys(CharSequence... keysToSend) {
        if (tools.dscode.common.composite.ElementOperations.primary()) {
            tools.dscode.common.composite.ElementOperations.sendKeys(driver, element, keysToSend);
            return;
        }
        executeVoidWithStaleRetry(currentElement -> currentElement.sendKeys(keysToSend));
    }

    @Override
    public void clear() {
        if (tools.dscode.common.composite.ElementOperations.primary()) {
            tools.dscode.common.composite.ElementOperations.sendKeys(driver, element, "");
            return;
        }
        executeVoidWithStaleRetry(WebElement::clear);
    }

    @Override
    public String getDomProperty(String name) {
        return executeWithStaleRetry(currentElement -> currentElement.getDomProperty(name));
    }

    @Override
    public String getDomAttribute(String name) {
        return executeWithStaleRetry(currentElement -> currentElement.getDomAttribute(name));
    }

    @Override
    public String getAttribute(String name) {
        return executeWithStaleRetry(currentElement -> currentElement.getAttribute(name));
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
        return executeWithStaleRetry(WebElement::isSelected);
    }

    @Override
    public String getText() {
        return executeWithStaleRetry(WebElement::getText);
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
        return executeWithStaleRetry(WebElement::getShadowRoot);
    }

    @Override
    public Point getLocation() {
        return executeWithStaleRetry(WebElement::getLocation);
    }

    @Override
    public Dimension getSize() {
        return executeWithStaleRetry(WebElement::getSize);
    }

    @Override
    public Rectangle getRect() {
        return executeWithStaleRetry(WebElement::getRect);
    }

    @Override
    public String getCssValue(String propertyName) {
        return executeWithStaleRetry(currentElement -> currentElement.getCssValue(propertyName));
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
        return executeWithStaleRetry(WebElement::isDisplayed);
    }

    public boolean screenReaderOnlyCheck() {
        if (possibleScreenReaderElement()) {
            return !executeWithStaleRetry(WebElement::isDisplayed);
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
        return executeWithStaleRetry(WebElement::isEnabled);
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
