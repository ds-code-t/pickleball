package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.assertions.ValueWrapper;

import java.time.Duration;
import java.util.*;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.domoperations.HumanInteractions.centerScroll;
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

    private final WebDriver driver;
    public WebElement element;
    public ObjectNode attributeSnapshot;
    private final String xpath1;
    private final String xpath2;
    public final ElementMatch elementMatch;
    public final Integer matchIndex;

    private static final Set<String> DOM_PROPERTY_FALLBACK_KEYS = Set.of(
            "innerHTML",
            "outerHTML",
            "innerText",
            "outerText",
            "textContent"
    );

    public static List<ElementWrapper> getWrappedElements(ElementMatch elementMatch) {
        if (elementMatch.parentPhrase.contextElement != null)
            return Collections.singletonList(elementMatch.parentPhrase.contextElement);
        SearchContext searchContext = elementMatch.contextWrapper.getFinalSearchContext();
        if (elementMatch.parentPhrase.contextElement != null)
            return Collections.singletonList(elementMatch.parentPhrase.contextElement);
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
        this.driver = elementMatch.parentPhrase.getDriver();
        this.matchIndex = matchIndex;
        this.elementMatch = elementMatch;
        this.element = Objects.requireNonNull(element, "element must not be null");

        takeSnapshot(this.element);
        if (debugFlags.contains("elementsnapshot"))
            System.out.println(attributeSnapshot.toPrettyString());

        // Build the persistent locating XPath (no JS).
        // Uses default attribute priority when varargs are omitted.
        this.xpath1 = buildXPathForElement(driver, element, 10, 30, List.of("id", "data-user-id"), List.of("name", "title"), List.of("role", "aria-label", "class"));
        this.xpath2 = buildXPathForElement(driver, element, 10, 15, List.of("href", "target", "src", "index"));


    }

    public void takeSnapshot() {
        executeWithStaleRetry(currentElement -> {
            takeSnapshot(currentElement);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private void takeSnapshot(WebElement snapshotElement) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        JavascriptExecutor js = javascriptExecutor();

        // tagName
        String tagName = safeTagName(snapshotElement);
        snapshot.put("tagName", tagName);

        // textContent (DOM text, not just visible text)
        String textContent;
        if ("textarea".equals(tagName) || "input".equals(tagName)) {
            textContent = safeDomAttribute(snapshotElement, "value");
        } else if ("select".equals(tagName)) {
            textContent = (String) js.executeScript(
                    "var sel = arguments[0]; return sel.options[sel.selectedIndex] ? sel.options[sel.selectedIndex].text : '';",
                    snapshotElement
            );
        } else {
            textContent = (String) js.executeScript(
                    "return arguments[0].innerText;",
                    snapshotElement
            );
            if (textContent == null || textContent.isEmpty()) {
                textContent = safeDomAttribute(snapshotElement, "value");
            }
        }
        snapshot.put("textContent", textContent == null ? "" : textContent);

        List<WebElement> children = withoutImplicitWait(
                driver,
                () -> snapshotElement.findElements(By.xpath("./*[@selected]"))
        );
        StringBuilder childValue = new StringBuilder();
        for (WebElement child : children) {
            String value = safeDomAttribute(child, "value");
            childValue.append(value == null ? child.getText() : value);
        }
        if (!children.isEmpty()) {
            snapshot.put("childValue", childValue.toString());
        }

        // all attributes as name:value
        Map<String, String> attrs = (Map<String, String>) js.executeScript(
                "var el = arguments[0];" +
                        "var out = {}; " +
                        "for (var i = 0; i < el.attributes.length; i++) {" +
                        "  var a = el.attributes[i];" +
                        "  out[a.name] = a.value;" +
                        "}" +
                        "return out;",
                snapshotElement
        );

        ObjectNode attrNode = snapshot.putObject("attributes");
        if (attrs != null) {
            attrs.forEach(attrNode::put);
        }

        int siblingIndex = ((Number) js.executeScript(
                "var el = arguments[0];" +
                        "var i = 0;" +
                        "while (el.previousElementSibling) {" +
                        "  el = el.previousElementSibling;" +
                        "  i++;" +
                        "}" +
                        "return i;",
                snapshotElement
        )).intValue();
        snapshot.put("siblingIndex", siblingIndex);

        int sameTagIndex = ((Number) js.executeScript(
                "var el = arguments[0];" +
                        "var tag = el.tagName;" +
                        "var i = 0;" +
                        "while (el.previousElementSibling) {" +
                        "  el = el.previousElementSibling;" +
                        "  if (el.tagName === tag) i++;" +
                        "}" +
                        "return i;",
                snapshotElement
        )).intValue();
        snapshot.put("sameTagIndex", sameTagIndex);

        this.attributeSnapshot = snapshot;
    }


    // -------------------------
    // Public API
    // -------------------------


    public ObjectNode getAttributeSnapshot() {
        return attributeSnapshot;
    }

    public ValueWrapper getElementReturnValue() {
        if (attributeSnapshot.has(ELEMENT_RETURN_VALUE))
            return createValueWrapper(attributeSnapshot.get(ELEMENT_RETURN_VALUE).asText());

        scrollIntoView();

        switch (elementMatch.category) {
            case "Field":
                List<WebElement> valueElements = withoutImplicitWait(driver, () -> findElements(By.xpath("descendant::*[contains(@class,'Read')]")));
                if (!valueElements.isEmpty()) {
                    String returnVal = valueElements.getLast().getText();
                    attributeSnapshot.put(ELEMENT_RETURN_VALUE, returnVal);
                    return createValueWrapper(returnVal);
                }
                if (getTagName().equals("td")) {
                    String returnVal = getText();
                    attributeSnapshot.put(ELEMENT_RETURN_VALUE, returnVal);
                    return createValueWrapper(returnVal);
                }
                valueElements = withoutImplicitWait(driver, () -> findElements(By.xpath("*[normalize-space(.)][1]/following-sibling::*[descendant-or-self::*[text()]]")));
                if (valueElements.size() == 1) {
                    String returnVal = valueElements.getLast().getText();
                    attributeSnapshot.put(ELEMENT_RETURN_VALUE, returnVal);
                    return createValueWrapper(returnVal);
                }
                break;
        }
        for (String key : elementMatch.defaultValueKeys) {
            ObjectNode node;
            if (key.startsWith("attributes.")) {
                node = (ObjectNode) attributeSnapshot.get("attributes");
                key = key.substring("attributes.".length());
            } else {
                node = attributeSnapshot;
            }
            if (node.has(key)) {
                String returnVal = node.get(key).asText();
                attributeSnapshot.put(ELEMENT_RETURN_VALUE, returnVal);
                return createValueWrapper(returnVal);
            } else if (DOM_PROPERTY_FALLBACK_KEYS.contains(key)) {
                String fallbackKey = key;
                String returnVal = executeWithStaleRetry(
                        currentElement -> safeDomProperty(currentElement, fallbackKey));
                if (returnVal != null) {
                    node.put(key, returnVal);
                    attributeSnapshot.put(ELEMENT_RETURN_VALUE, returnVal);
                    return createValueWrapper(returnVal);
                }
            }
        }
        attributeSnapshot.put(ELEMENT_RETURN_VALUE, "");
        return createValueWrapper("");
    }


    // -------------------------
    // Internal helpers
    // -------------------------

    /**
     * Use this only for internal/probe searches where "not found" is expected.
     * This prevents Selenium implicit-wait polling from turning optional lookups
     * into multi-second delays.
     */
    private static <T> T withoutImplicitWait(WebDriver driver, java.util.function.Supplier<T> action) {
        Duration original = driver.manage().timeouts().getImplicitWaitTimeout();
        try {
            driver.manage().timeouts().implicitlyWait(Duration.ZERO);
            return action.get();
        } finally {
            driver.manage().timeouts().implicitlyWait(original);
        }
    }

    private <T> T executeWithStaleRetry(java.util.function.Function<WebElement, T> operation) {
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
        takeSnapshot(refreshedElement);
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

        List<WebElement> elementList4 = getElementList(driver, elementMatch.contextWrapper.elementPath.getXpath());
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

        List<WebElement> elementList7 = getElementList(driver, elementMatch.contextWrapper.elementTerminalXPath.getXpath());
        if (elementList7.size() == 1) {
            return elementList7.getFirst();
        }

        if (elementList7.size() > 1 && matchIndex != null) {
            List<WebElement> elementList8 = getElementList(driver, "(" + elementMatch.contextWrapper.elementTerminalXPath.getXpath() + ")[" + matchIndex + "]");
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

        // default attribute priority if none supplied
        String[] effectiveAttrs = (attrPriority == null || attrPriority.length == 0)
                ? new String[]{"id", "data-user-id"}
                : attrPriority;

        // 1) Find closest self-or-descendant match for any of these attributes
        String descAttrName = null;
        String descAttrValue = null;

        outerDesc:
        for (String attr : effectiveAttrs) {

            // check self first
            String selfVal = getAttrOrEmpty(element, attr);
            if (!selfVal.isEmpty()) {
                descAttrName = attr;
                descAttrValue = selfVal;
                break;
            }

            // then any descendant (RELATIVE to element), limited by node-position cap if configured
            try {
                // NOTE: This is a node-count/position limit, NOT a true depth limit.
                // It caps the number of matching nodes considered in document order.
                String xpathExpr;
                if (maxDescendantNodes > 0) {
                    xpathExpr = "(.//*[@" + attr + "])[position() <= " + maxDescendantNodes + "]";
                } else {
                    xpathExpr = ".//*[@" + attr + "]";
                }

                WebElement d = withoutImplicitWait(driver, () -> element.findElement(By.xpath(xpathExpr)));
                String v = getAttrOrEmpty(d, attr);
                if (!v.isEmpty()) {
                    descAttrName = attr;
                    descAttrValue = v;
                    break outerDesc;
                }
            } catch (NoSuchElementException ignored) {
            }
        }

        // 2) Build descendant-or-self predicate OR fallback to children-shape predicate
        String mainPredicate;
        if (descAttrName != null) {
            mainPredicate = "descendant-or-self::*[@" + descAttrName + "="
                    + quoteForXPath(descAttrValue) + "]";
        } else {
            mainPredicate = buildChildrenShapePredicate(driver, element);
        }

        // 3) Build ancestor-or-self predicate using closest ancestor with prioritized attributes
        String ancAttrName = null;
        String ancAttrValue = null;

        outerAnc:
        for (String attr : effectiveAttrs) {
            WebElement current = element;

            // Walk up via parent::* but cap the number of nodes checked if configured.
            int checkedAncestors = 0;

            while (true) {
                if (maxAncestorNodes > 0 && checkedAncestors >= maxAncestorNodes) {
                    break;
                }

                WebElement parent;
                try {
                    // move up to the parent element first
                    final WebElement currentForParentLookup = current;
                    parent = withoutImplicitWait(
                            driver,
                            () -> currentForParentLookup.findElement(By.xpath("parent::*"))
                    );
                } catch (NoSuchElementException | InvalidSelectorException e) {
                    break; // hit the top (e.g. <html> or document boundary)
                }

                if (parent.equals(current)) {
                    break;
                }

                current = parent;
                checkedAncestors++;

                // NOW check the ancestor's attribute (self is never checked here)
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

        // Default attribute groups if none supplied
        List<List<String>> effectiveGroups =
                (attrPriorityGroups == null || attrPriorityGroups.length == 0)
                        ? List.of(List.of("id", "data-user-id"))
                        : Arrays.asList(attrPriorityGroups);

        String descAttrName = null;
        String descAttrValue = null;

        // ------------------------------------------------------------
        // 1) Descendant-or-self search (single XPath per group)
        // ------------------------------------------------------------
        outerDesc:
        for (List<String> group : effectiveGroups) {

            // ---- self check first (priority preserved)
            for (String attr : group) {
                String selfVal = getAttrOrEmpty(element, attr);
                if (!selfVal.isEmpty()) {
                    descAttrName = attr;
                    descAttrValue = selfVal;
                    break outerDesc;
                }
            }

            // ---- descendant search (single XPath using OR)
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

                WebElement d = withoutImplicitWait(driver, () -> element.findElement(By.xpath(xpathExpr)));

                // pick first attribute in group priority order
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

        // ------------------------------------------------------------
        // 2) Main predicate
        // ------------------------------------------------------------
        String mainPredicate;
        if (descAttrName != null) {
            mainPredicate = "descendant-or-self::*[@" + descAttrName + "="
                    + quoteForXPath(descAttrValue) + "]";
        } else {
            mainPredicate = buildChildrenShapePredicate(driver, element);
        }

        // ------------------------------------------------------------
        // 3) Ancestor search (grouped attributes, depth-limited)
        // ------------------------------------------------------------
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
                    parent = withoutImplicitWait(driver, () -> currentForParentLookup.findElement(By.xpath("parent::*")));
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

        // ------------------------------------------------------------
        // 4) Assemble final XPath
        // ------------------------------------------------------------
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


    // Convenience overload: uses default attr priority
    private static String buildXPathForElement(
            WebDriver driver,
            WebElement element,
            int maxAncestorDepth,
            int maxDescendantDepth) {
        return buildXPathForElement(driver, element, maxAncestorDepth, maxDescendantDepth, (String[]) null);
    }

    /**
     * If we don't have any good attributes in the subtree, describe the shape of the direct
     * children: either [not(*)] for no children, or [child::tag1 and child::tag2 ...].
     */
    private static String buildChildrenShapePredicate(WebDriver driver, WebElement element) {
        List<WebElement> children = withoutImplicitWait(driver, () -> element.findElements(By.xpath("./*")));
        if (children.isEmpty()) {
            return "not(*)";
        }

        // Use a LinkedHashSet to keep insertion order and avoid duplicates
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
        return withoutImplicitWait(driver, () -> elementMatch.contextWrapper.getFinalSearchContext().findElements(new By.ByXPath(xpathyWithId)));
    }

    @Override
    public WebElement getWrappedElement() {
        return element;
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
        executeVoidWithStaleRetry(WebElement::click);
    }

    @Override
    public void submit() {
        executeVoidWithStaleRetry(WebElement::submit);
    }

    @Override
    public void sendKeys(CharSequence... keysToSend) {
        executeVoidWithStaleRetry(currentElement -> currentElement.sendKeys(keysToSend));
    }

    @Override
    public void clear() {
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
        return executeWithStaleRetry(currentElement -> currentElement.findElements(by));
    }

    @Override
    public WebElement findElement(By by) {
        Objects.requireNonNull(by, "by must not be null");
        return executeWithStaleRetry(currentElement -> currentElement.findElement(by));
    }

    @Override
    public SearchContext getShadowRoot() {
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

    public void scrollIntoView() {
        try {
            executeVoidWithStaleRetry(currentElement -> centerScroll(driver, currentElement));
        } catch (RuntimeException ignored) {
            // Best-effort scroll.
        }
    }

    @Override
    public boolean isDisplayed() {
        if (elementMatch.categoryFlags.contains(ExecutionDictionary.CategoryFlags.NON_DISPLAY_ELEMENT))
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
        if (snapshotAttributeEqualsIgnoreCase("role", "status", "alert", "log", "timer", "marquee")) {
            return true;
        }
        return false;
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
        String closeXpath = getExecutionDictionary().getCategoryXPathy("Close Button").getXpath().replaceFirst("^//\\*", "descendant-or-self::*");
        executeVoidWithStaleRetry(currentElement -> {
            WebElement closeButton = currentElement.findElement(new By.ByXPath(closeXpath));
            closeButton.click();
        });
    }

    public String getSnapshotAttribute(String name) {
        if (attributeSnapshot == null) return null;
        return attributeSnapshot
                .path("attributes")
                .path(name)
                .asText(null);
    }


    public boolean snapshotAttributeEqualsIgnoreCase(String name, String... candidates) {
        if (attributeSnapshot == null || candidates == null) {
            return false;
        }
        String value = attributeSnapshot
                .path("attributes")
                .path(name)
                .asText(null);

        if (value == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (value.trim().equalsIgnoreCase(candidate.trim())) {
                return true;
            }
        }
        return false;
    }

    public boolean snapshotContainsAnyAttribute(String... keys) {
        if (attributeSnapshot == null || keys == null || keys.length == 0) {
            return false;
        }
        var attributesNode = attributeSnapshot.path("attributes");

        for (String key : keys) {
            if (key != null && attributesNode.has(key)) {
                return true;
            }
        }
        return false;
    }


    public String getSnapshotValue(String key) {
        if (attributeSnapshot == null) return null;

        if (attributeSnapshot.has(key))
            return attributeSnapshot.get(key).asText(null);

        return attributeSnapshot
                .path("attributes")
                .path(key)
                .asText(null);
    }

    @Override
    public String getTagName() {
        return attributeSnapshot.get("tagName").asText("");
    }

    public int getIndex() {
        return attributeSnapshot.get("siblingIndex").intValue();
    }

    public int getSameTagIndex() {
        return attributeSnapshot.get("sameTagIndex").intValue();
    }

}
