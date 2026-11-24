package dev.domengine.snapshot;

import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Serializes a pierced browser tree into the v2 canonical HTML document per frame.
 * Shadow roots are inlined. Iframe documents become their own frames.
 */
public final class CanonicalHtml {
    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"
    );

    private CanonicalHtml() {
    }

    public static CanonicalDocument capture(DomNode root) {
        if (root == null) {
            return CanonicalDocument.html(new CanonicalDocument.Frame("main", "", "", "", 0, 0, "", false));
        }
        List<DomNode> iframes = new ArrayList<>();
        collectIframes(root, iframes);
        Map<DomNode, String> frameIds = new IdentityHashMap<>();
        for (DomNode iframe : iframes) frameIds.put(iframe, frameIdOf(iframe));
        boolean[] shadow = {false};
        String mainId = root.frameId == null || root.frameId.isBlank() ? "main" : root.frameId;
        List<CanonicalDocument.Frame> frames = new ArrayList<>();
        frames.add(new CanonicalDocument.Frame(
                mainId, "", "", "", 0, 0, writeDocument(root, frameIds, shadow), shadow[0]));
        for (DomNode iframe : iframes) {
            boolean[] frameShadow = {false};
            DomNode document = contentDocument(iframe);
            String html = document == null ? "" : writeDocument(document, frameIds, frameShadow);
            String parent = iframe.frameId == null || iframe.frameId.isBlank() ? mainId : iframe.frameId;
            String path = framePath(iframe);
            double x = iframe.hasBox ? iframe.x : 0;
            double y = iframe.hasBox ? iframe.y : 0;
            frames.add(new CanonicalDocument.Frame(
                    frameIds.get(iframe), parent, "", path, x, y, html, frameShadow[0]));
        }
        return new CanonicalDocument("html", frames, null);
    }

    private static String frameIdOf(DomNode iframe) {
        String own = iframe.frameId == null ? "" : iframe.frameId;
        for (DomNode child : iframe.children) {
            if (child.nodeType != 9) continue;
            String id = child.frameId == null ? "" : child.frameId;
            if (!id.isBlank() && !id.equals(own)) return id;
        }
        if (iframe.ownerFrameId != null && !iframe.ownerFrameId.isBlank() && !iframe.ownerFrameId.equals(own)) {
            return iframe.ownerFrameId;
        }
        return "f" + iframe.backendNodeId;
    }

    private static String framePath(DomNode iframe) {
        String xpath = iframe.xpath == null ? "" : iframe.xpath;
        String parent = iframe.framePath == null ? "" : iframe.framePath;
        if (xpath.isBlank()) return parent;
        if (parent.isBlank()) return xpath;
        return parent + " >> " + xpath;
    }

    private static DomNode contentDocument(DomNode iframe) {
        for (DomNode child : iframe.children) {
            if (child.nodeType == 9) return child;
        }
        return null;
    }

    private static void collectIframes(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.iframe && node.nodeType == 1) into.add(node);
        for (DomNode child : node.children) collectIframes(child, into);
        for (DomNode shadow : node.shadowRoots) collectIframes(shadow, into);
    }

    private static String writeDocument(DomNode document, Map<DomNode, String> frameIds, boolean[] shadow) {
        StringBuilder out = new StringBuilder();
        if (document == null) return "";
        if (document.nodeType == 9 || document.nodeType == 11 || document.shadowRoot) {
            for (DomNode child : document.children) writeNode(child, frameIds, out, shadow);
            return out.toString();
        }
        writeNode(document, frameIds, out, shadow);
        return out.toString();
    }

    private static void writeNode(DomNode node, Map<DomNode, String> frameIds, StringBuilder out, boolean[] shadow) {
        if (node == null || node.nodeType == 9) return;
        if (node.nodeType == 3) {
            out.append(escapeText(node.nodeValue));
            return;
        }
        if (node.nodeType == 8) {
            String text = node.nodeValue == null ? "" : node.nodeValue.replace("--", "");
            out.append("<!--").append(text).append("-->");
            return;
        }
        if (node.nodeType != 1) {
            for (DomNode child : node.children) writeNode(child, frameIds, out, shadow);
            return;
        }
        String tag = node.localName == null || node.localName.isBlank()
                ? "div"
                : node.localName.toLowerCase(Locale.ROOT);
        String frameId = node.iframe ? frameIds.get(node) : null;
        out.append('<').append(tag);
        node.attributes.forEach((name, value) -> {
            if (name == null || name.isBlank()) return;
            if (frameId != null && "data-pkb-frame".equalsIgnoreCase(name)) return;
            out.append(' ').append(name).append("=\"").append(escapeAttr(value)).append('"');
        });
        if (frameId != null) out.append(" data-pkb-frame=\"").append(escapeAttr(frameId)).append('"');
        boolean empty = VOID.contains(tag) && node.shadowRoots.isEmpty() && !hasContent(node, frameId != null);
        if (empty) {
            out.append('>');
            return;
        }
        out.append('>');
        for (DomNode child : node.children) {
            if (frameId != null && child.nodeType == 9) continue;
            writeNode(child, frameIds, out, shadow);
        }
        for (DomNode root : node.shadowRoots) {
            shadow[0] = true;
            String mode = root.shadowMode == null ? "" : root.shadowMode.toLowerCase(Locale.ROOT);
            if (!"open".equals(mode) && !"closed".equals(mode)) mode = "open";
            out.append("<template data-pkb-shadow=\"").append(mode).append("\">");
            for (DomNode child : root.children) writeNode(child, frameIds, out, shadow);
            out.append("</template>");
        }
        out.append("</").append(tag).append('>');
    }

    private static boolean hasContent(DomNode node, boolean iframe) {
        for (DomNode child : node.children) {
            if (iframe && child.nodeType == 9) continue;
            return true;
        }
        return false;
    }

    private static String escapeText(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String escapeAttr(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }
}
