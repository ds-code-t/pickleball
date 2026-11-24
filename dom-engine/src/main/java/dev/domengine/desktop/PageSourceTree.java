package dev.domengine.desktop;

import dev.domengine.tree.DomNode;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a desktop driver's XML page source into canonical desktop {@link DomNode}s (D9, D10).
 * Windows sources (FlaUI.WebDriver, NovaWindows, WinAppDriver) use control-type tags with Name, AutomationId,
 * ClassName, RuntimeId, and x/y/width/height or BoundingRectangle; Appium Mac2 uses XCUIElementType tags with
 * title, label, identifier, value, and x/y/width/height. Each node keeps its positional driver XPath in
 * {@code grid-xpath} so an action can re-find it; the canonical tree stays the only query language.
 */
public final class PageSourceTree {
    private PageSourceTree() {
    }

    public static DomNode parse(String xml, String driver) {
        if (xml == null || xml.isBlank()) throw new IllegalStateException("desktop driver returned an empty page source");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Element top = factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(xml))).getDocumentElement();
            long[] next = {1};
            return node(top, "/" + top.getTagName() + "[1]", "mac2".equals(driver), next);
        } catch (Exception failure) {
            throw new IllegalStateException("desktop driver page source is not XML: " + failure.getMessage(), failure);
        }
    }

    private static DomNode node(Element element, String xpath, boolean mac, long[] next) {
        DomNode node = new DomNode();
        node.nodeType = 1;
        String tag = element.getTagName();
        String type = mac && tag.startsWith("XCUIElementType") ? tag.substring("XCUIElementType".length()) : tag;
        String backend = mac ? "ax" : "uia";
        node.role = NativeDesktop.mapRole(backend, type);
        node.localName = node.role.isBlank() ? type.toLowerCase(Locale.ROOT) : node.role;
        node.nodeName = node.localName.toUpperCase(Locale.ROOT);
        node.accessibleName = first(attr(element, "Name"), attr(element, "title"), attr(element, "label"));
        node.nodeValue = first(attr(element, "Value.Value"), attr(element, "value"));
        node.attributes.put("platform-role", type);
        put(node, "automation-id", first(attr(element, "AutomationId"), attr(element, "identifier")));
        put(node, "class-name", attr(element, "ClassName"));
        String runtime = attr(element, "RuntimeId");
        put(node, "uia-runtime-id", runtime.isBlank() ? "" : runtime.replace('.', ','));
        node.attributes.put("desktop-backend", "grid");
        node.attributes.put("grid-xpath", xpath);
        double[] box = box(element);
        if (box != null) {
            node.x = box[0];
            node.y = box[1];
            node.frameX = box[0];
            node.frameY = box[1];
            node.width = box[2];
            node.height = box[3];
            node.hasBox = box[2] > 0 && box[3] > 0;
        }
        if ("false".equalsIgnoreCase(first(attr(element, "IsEnabled"), attr(element, "enabled")))) node.states.add("disabled");
        if ("true".equalsIgnoreCase(attr(element, "IsOffscreen")) || "false".equalsIgnoreCase(attr(element, "visible"))) {
            node.states.add("offscreen");
            node.displayed = false;
        }
        node.backendNodeId = next[0];
        node.nodeId = next[0];
        next[0]++;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (!(child instanceof Element kid)) continue;
            int index = counts.merge(kid.getTagName(), 1, Integer::sum);
            DomNode projected = node(kid, xpath + "/" + kid.getTagName() + "[" + index + "]", mac, next);
            projected.parent = node;
            node.children.add(projected);
        }
        return node;
    }

    /** Stamps the driver and its URL on every node so click and type go back through that driver. */
    static void mark(DomNode node, String driver, String url) {
        if (node == null) return;
        node.attributes.put("grid-driver", driver);
        node.attributes.put("grid-url", url);
        for (DomNode child : node.children) mark(child, driver, url);
    }

    private static double[] box(Element element) {
        String rect = attr(element, "BoundingRectangle");
        if (!rect.isBlank()) {
            String[] parts = rect.replaceAll("[^0-9.,-]", "").split(",");
            if (parts.length == 4) {
                try {
                    return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        String x = attr(element, "x");
        if (x.isBlank()) return null;
        try {
            return new double[]{Double.parseDouble(x), Double.parseDouble(attr(element, "y")),
                    Double.parseDouble(attr(element, "width")), Double.parseDouble(attr(element, "height"))};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void put(DomNode node, String key, String value) {
        if (value != null && !value.isBlank()) node.attributes.put(key, value);
    }

    private static String attr(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : "";
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}
