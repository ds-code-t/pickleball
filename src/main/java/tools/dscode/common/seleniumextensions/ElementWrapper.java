package tools.dscode.common.seleniumextensions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openqa.selenium.*;
import org.openqa.selenium.NoSuchElementException;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.assertions.ValueWrapper;

import java.time.Duration;
import java.util.*;

import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForElementReady;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.domoperations.SeleniumUtils.intersection;
import static tools.dscode.common.domoperations.SeleniumUtils.union;
import static tools.dscode.common.treeparsing.parsedComponents.ElementMatch.ELEMENT_RETURN_VALUE;


public class ElementWrapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebDriver driver;
    public WebElement element;
    public ObjectNode attributeSnapshot;
    private final String xpath1;
    private final String xpath2;
    public final ElementMatch elementMatch;
    public final Integer matchIndex;


    public static List<ElementWrapper> getWrappedElements(ElementMatch elementMatch) {

        List<ElementWrapper> elementWrappers = new ArrayList<>();
        List<WebElement> elements = elementMatch.contextWrapper.getElements();
        int index = 0;
        for (WebElement element : elements) {
            elementWrappers.add(new ElementWrapper(element, elementMatch, ++index));
        }
        return elementWrappers;
    }

    public ElementWrapper(WebDriver driver, WebElement element, ElementMatch elementMatch) {
        this(element, elementMatch, null);
    }


    private ElementWrapper(WebElement element, ElementMatch elementMatch, Integer matchIndex) {
        this.driver = elementMatch.parentPhrase.webDriver;
        this.matchIndex = matchIndex;
        this.elementMatch = elementMatch;
        this.element = Objects.requireNonNull(element, "element must not be null");

        takeSnapshot();


        // Build the persistent locating XPath (no JS).
        // Uses default attribute priority when varargs are omitted.
        this.xpath1 = buildXPathForElement(element, "id", "data-user-id", "name", "title", "role", "aria-label", "class");
        this.xpath2 = buildXPathForElement(element, "href", "target", "src", "onclick", "type", "index");


    }

    public void takeSnapshot() {
        this.attributeSnapshot = MAPPER.createObjectNode();
        if (!(driver instanceof JavascriptExecutor js)) {
            throw new IllegalArgumentException(
                    "WebDriver must implement JavascriptExecutor to use ElementWrapper");
        }
        // tagName
        String tagName = safeTagName(element);
        attributeSnapshot.put("tagName", tagName);

        // textContent (DOM text, not just visible text)
        String textContent = (String) js.executeScript(
                "return arguments[0].textContent;", element
        );
        attributeSnapshot.put("textContent", textContent == null ? "" : textContent);

        // all attributes as name:value
        Map<String, String> attrs = (Map<String, String>) js.executeScript(
                "var el = arguments[0];" +
                        "var out = {}; " +
                        "for (var i = 0; i < el.attributes.length; i++) {" +
                        "  var a = el.attributes[i];" +
                        "  out[a.name] = a.value;" +
                        "}" +
                        "return out;",
                element
        );

        ObjectNode attrNode = attributeSnapshot.putObject("attributes");
        attrs.forEach(attrNode::put);

    }

    // -------------------------
    // Public API
    // -------------------------

    public WebElement getElement() {
        if (isStale(this.element)) {
            this.element = refindElement();
        }
        return this.element;
    }

    public ObjectNode getAttributeSnapshot() {
        return attributeSnapshot;
    }

    public ValueWrapper getElementReturnValue() {
        if (attributeSnapshot.has(ELEMENT_RETURN_VALUE))
            return createValueWrapper(attributeSnapshot.get(ELEMENT_RETURN_VALUE).asText());

        switch (elementMatch.category) {
            case "Field":
                List<WebElement> valueElements = element.findElements(By.xpath("descendant::*[contains(@class,'Read')]"));
                if (!valueElements.isEmpty()) {
                    String returnVal = valueElements.getLast().getText();
                    attributeSnapshot.put(ELEMENT_RETURN_VALUE, returnVal);
                    return createValueWrapper(returnVal);
                }
                break;
        }
        for (String key : elementMatch.defaultValueKeys) {
            if (attributeSnapshot.has(key)) {
                String returnVal = attributeSnapshot.get(key).asText();
                attributeSnapshot.put(ELEMENT_RETURN_VALUE, returnVal);
                return createValueWrapper(returnVal);
            }
        }
        attributeSnapshot.put(ELEMENT_RETURN_VALUE, "");
        return createValueWrapper("");
    }


    // -------------------------
    // Internal helpers
    // -------------------------

    private boolean isStale(WebElement el) {
        if (el == null) return true;
        try {
            el.isEnabled(); // simple touch; throws if stale
            return false;
        } catch (WebDriverException e) {
            return true;
        }
    }

    private WebElement refindElement() {
        safeWaitForPageReady(driver, Duration.ofSeconds(60));
        element = refindUniqueElement();
        safeWaitForElementReady(driver, element, Duration.ofSeconds(60));
        takeSnapshot();
        return element;
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
                return elementList3.getFirst();
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
                return elementList7.getFirst();
            }
        }

        throw new RuntimeException("Failed to relocate " + elementMatch);


    }

    private static String buildXPathForElement(WebElement element, String... attrPriority) {

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

            // then any descendant (RELATIVE to element)
            try {
                String xpathExpr = ".//*[@" + attr + "]";

                WebElement d = element.findElement(By.xpath(xpathExpr));
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
            mainPredicate = buildChildrenShapePredicate(element);
        }

        // 3) Build ancestor-or-self predicate using closest ancestor with prioritized attributes
        // 3) Build ancestor-or-self predicate using closest ancestor with prioritized attributes
        String ancAttrName = null;
        String ancAttrValue = null;

        outerAnc:
        for (String attr : effectiveAttrs) {
            WebElement current = element;
            while (true) {
                WebElement parent;
                try {
                    // move up to the parent element first
                    parent = current.findElement(By.xpath("parent::*"));
                } catch (NoSuchElementException | InvalidSelectorException e) {
                    break; // hit the top (e.g. <html> or document boundary)
                }
                if (parent.equals(current)) {
                    break;
                }
                current = parent;

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


    // Convenience overload: uses default attr priority
    private static String buildXPathForElement(WebElement element) {
        return buildXPathForElement(element, (String[]) null);
    }

    /**
     * If we don't have any good attributes in the subtree, describe the shape of the direct
     * children: either [not(*)] for no children, or [child::tag1 and child::tag2 ...].
     */
    private static String buildChildrenShapePredicate(WebElement element) {
        List<WebElement> children = element.findElements(By.xpath("./*"));
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
            String v = el.getAttribute(name);
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

    // NOTE: You said this already exists somewhere:
    // public List<WebElement> getElementList(WebDriver driver, String XPathyWithID) { ... }
    // Here we just call it; you provide the implementation elsewhere.
    private List<WebElement> getElementList(WebDriver driver, String xpathyWithId) {
        // placeholder so this compiles – remove if you already have it in a superclass/utility
        throw new UnsupportedOperationException("getElementList must be implemented elsewhere");
    }

    public boolean isDisplayed() {
        return getElement().isDisplayed();
    }

    public boolean isEnabled() {
        return getElement().isEnabled();
    }

    public boolean isSelected() {
        return getElement().isSelected();
    }

    public boolean isBlank() {
        return getElementReturnValue().isBlank();
    }

    public boolean hasValue() {
        return !getElementReturnValue().isBlank();
    }

}
