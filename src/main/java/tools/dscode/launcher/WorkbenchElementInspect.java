package tools.dscode.launcher;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.domoperations.ExecutionDictionary;
import tools.dscode.common.treeparsing.DefinitionContext;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only page inspection. XPath comes from the existing execution dictionary.
 * Nothing is clicked, typed, or saved.
 */
public final class WorkbenchElementInspect {
    private static final Set<String> INTERACTIVE = Set.of(
            "a", "button", "input", "select", "textarea"
    );

    private WorkbenchElementInspect() {
    }

    public record Hit(String phrase, String xpath, int rawMatchCount, int visibleCount) {
    }

    public static List<Hit> dump(String html) {
        Document document = parse(html);
        List<Hit> hits = new ArrayList<>();
        walk(document.getDocumentElement(), hits, document);
        return List.copyOf(hits);
    }

    public static Hit find(String category, String text, String html) {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Element category must not be blank.");
        }
        ExecutionDictionary dictionary = DefinitionContext.getExecutionDictionary();
        ValueWrapper value = text == null || text.isBlank() ? null : ValueWrapper.createValueWrapper(text);
        ExecutionDictionary.CategoryResolution resolution =
                dictionary.getFinalCategoryResolution(category.trim(), value, null);
        String xpath = resolution.xpath() == null || resolution.xpath().getXpath() == null
                ? ""
                : resolution.xpath().getXpath();
        Document document = html == null || html.isBlank() ? null : parse(html);
        int raw = document == null ? 0 : count(document, xpath, false);
        int visible = document == null ? 0 : count(document, xpath, true);
        return new Hit(phrase(category.trim(), text), xpath, raw, visible);
    }

    private static void walk(Element element, List<Hit> hits, Document document) {
        if (element == null) return;
        if (interactive(element)) {
            String text = element.getTextContent() == null ? "" : element.getTextContent().trim();
            if (text.isEmpty()) text = first(element, "aria-label", "placeholder", "name", "id");
            String category = category(element);
            if (!text.isEmpty() && !category.isEmpty()) {
                Hit built = find(category, text, null);
                hits.add(withCounts(built, document));
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element next) walk(next, hits, document);
        }
    }

    private static Hit withCounts(Hit built, Document document) {
        int raw = count(document, built.xpath(), false);
        int visible = count(document, built.xpath(), true);
        return new Hit(built.phrase(), built.xpath(), raw, visible);
    }

    private static boolean interactive(Element element) {
        String tag = element.getTagName() == null ? "" : element.getTagName().toLowerCase(Locale.ROOT);
        if (INTERACTIVE.contains(tag)) return true;
        String role = element.getAttribute("role");
        return role != null && Set.of("button", "link", "textbox").contains(role.toLowerCase(Locale.ROOT));
    }

    private static String category(Element element) {
        String tag = element.getTagName() == null ? "" : element.getTagName().toLowerCase(Locale.ROOT);
        String role = element.getAttribute("role") == null ? "" : element.getAttribute("role").toLowerCase(Locale.ROOT);
        if ("a".equals(tag) || "link".equals(role)) return "Link";
        if ("textarea".equals(tag) || "input".equals(tag) || "textbox".equals(role)) return "Textbox";
        if ("select".equals(tag)) return "Dropdown";
        return "Button";
    }

    private static String phrase(String category, String text) {
        if (text == null || text.isBlank()) return "the " + category;
        return "the \"" + text.trim() + "\" " + category;
    }

    private static String first(Element element, String... names) {
        for (String name : names) {
            String value = element.getAttribute(name);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static int count(Document document, String xpath, boolean visibleOnly) {
        if (document == null || xpath == null || xpath.isBlank()) return 0;
        try {
            NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath()
                    .evaluate(xpath, document, XPathConstants.NODESET);
            int count = 0;
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element element)) {
                    count++;
                    continue;
                }
                if (!visibleOnly || visible(element)) count++;
            }
            return count;
        } catch (RuntimeException | javax.xml.xpath.XPathExpressionException failure) {
            return 0;
        }
    }

    private static boolean visible(Element element) {
        if (element.hasAttribute("hidden")) return false;
        String aria = element.getAttribute("aria-hidden");
        if ("true".equalsIgnoreCase(aria)) return false;
        String style = element.getAttribute("style");
        return style == null || !style.toLowerCase(Locale.ROOT).contains("display:none");
    }

    private static Document parse(String html) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8))
            );
            document.getDocumentElement().normalize();
            return document;
        } catch (Exception failure) {
            throw new IllegalArgumentException("Could not read the page HTML: " + failure.getMessage(), failure);
        }
    }
}
