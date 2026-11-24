package dev.domengine.snapshot;

import dev.domengine.desktop.NativeDesktop;
import dev.domengine.query.HtmlTree;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Projects {@code nodes[]} and the pre-map from the canonical tree.
 * Live geometry and accessibility fields are copied onto the parsed elements
 * so a reserialize does not drop boxes or names that HTML does not carry.
 */
public final class CanonicalProjection {
    private CanonicalProjection() {
    }

    public static DomNode project(DomNode live, CanonicalDocument canonical) {
        if (canonical == null || canonical.frames().isEmpty()) return live;
        DomNode parsed = parse(canonical);
        if (live != null) {
            if ("html-ish".equals(canonical.kind())) overlayDesktop(parsed, live, canonical);
            else overlayFrames(parsed, live);
        }
        if (parsed != null) parsed.linkParents();
        return parsed == null ? live : parsed;
    }

    private static DomNode parse(CanonicalDocument canonical) {
        CanonicalDocument.Frame main = canonical.frames().get(0);
        DomNode root = HtmlTree.parse(main.html());
        stitch(root, canonical);
        return root;
    }

    private static void stitch(DomNode node, CanonicalDocument canonical) {
        if (node == null) return;
        if (node.nodeType == 1 && node.iframe) {
            String id = node.attributes.get("data-pkb-frame");
            if (id != null && !id.isBlank() && contentDocument(node) == null) {
                CanonicalDocument.Frame child = frame(canonical, id);
                if (child != null) {
                    DomNode document = HtmlTree.parse(child.html());
                    document.frameId = id;
                    node.ownerFrameId = id;
                    node.children.add(document);
                    stitch(document, canonical);
                }
            }
        }
        for (DomNode child : new ArrayList<>(node.children)) stitch(child, canonical);
        for (DomNode shadow : new ArrayList<>(node.shadowRoots)) stitch(shadow, canonical);
    }

    private static CanonicalDocument.Frame frame(CanonicalDocument canonical, String id) {
        for (CanonicalDocument.Frame frame : canonical.frames()) {
            if (id.equals(frame.id())) return frame;
        }
        return null;
    }

    private static DomNode contentDocument(DomNode iframe) {
        for (DomNode child : iframe.children) {
            if (child.nodeType == 9) return child;
        }
        return null;
    }

    private static void overlayFrames(DomNode parsed, DomNode live) {
        List<DomNode> parsedElements = new ArrayList<>();
        List<DomNode> liveElements = new ArrayList<>();
        elementsInFrame(parsed, parsedElements);
        elementsInFrame(live, liveElements);
        int count = Math.min(parsedElements.size(), liveElements.size());
        for (int i = 0; i < count; i++) copy(parsedElements.get(i), liveElements.get(i));
        List<DomNode> parsedDocs = new ArrayList<>();
        List<DomNode> liveDocs = new ArrayList<>();
        iframeDocuments(parsed, parsedDocs);
        iframeDocuments(live, liveDocs);
        int frames = Math.min(parsedDocs.size(), liveDocs.size());
        for (int i = 0; i < frames; i++) overlayFrames(parsedDocs.get(i), liveDocs.get(i));
    }

    private static void overlayDesktop(DomNode parsed, DomNode live, CanonicalDocument canonical) {
        DomNode body = elementNamed(parsed, "body");
        if (body == null) {
            overlayTree(parsed, live);
            return;
        }
        String backend = "atspi";
        if (!canonical.frames().isEmpty()) {
            String marker = canonical.frames().get(0).html();
            if (marker.contains("data-pkb-backend=\"uia\"")) backend = "uia";
            else if (marker.contains("data-pkb-backend=\"ax\"")) backend = "ax";
        }
        String role = NativeDesktop.mapRole(backend, live.role);
        List<DomNode> bodyElements = elementChildren(body);
        if ("desktop".equals(role)) {
            List<DomNode> liveChildren = elementChildren(live);
            int count = Math.min(bodyElements.size(), liveChildren.size());
            for (int i = 0; i < count; i++) overlayTree(bodyElements.get(i), liveChildren.get(i));
            return;
        }
        if (!bodyElements.isEmpty()) overlayTree(bodyElements.get(0), live);
    }

    private static void overlayTree(DomNode parsed, DomNode live) {
        if (parsed == null || live == null) return;
        copy(parsed, live);
        List<DomNode> parsedChildren = elementChildren(parsed);
        List<DomNode> liveChildren = elementChildren(live);
        int count = Math.min(parsedChildren.size(), liveChildren.size());
        for (int i = 0; i < count; i++) overlayTree(parsedChildren.get(i), liveChildren.get(i));
    }

    private static void copy(DomNode parsed, DomNode live) {
        if (live.hasBox) {
            parsed.x = live.x;
            parsed.y = live.y;
            parsed.width = live.width;
            parsed.height = live.height;
            parsed.hasBox = true;
            parsed.boxRead = live.boxRead;
            parsed.frameX = live.frameX;
            parsed.frameY = live.frameY;
        }
        parsed.hasLayoutViewport = live.hasLayoutViewport;
        parsed.layoutViewportX = live.layoutViewportX;
        parsed.layoutViewportY = live.layoutViewportY;
        parsed.layoutViewportWidth = live.layoutViewportWidth;
        parsed.layoutViewportHeight = live.layoutViewportHeight;
        parsed.hasVisual = live.hasVisual;
        parsed.visualX = live.visualX;
        parsed.visualY = live.visualY;
        parsed.visualWidth = live.visualWidth;
        parsed.visualHeight = live.visualHeight;
        parsed.hasFrameDocument = live.hasFrameDocument;
        parsed.frameDocumentX = live.frameDocumentX;
        parsed.frameDocumentY = live.frameDocumentY;
        parsed.frameDocumentWidth = live.frameDocumentWidth;
        parsed.frameDocumentHeight = live.frameDocumentHeight;
        if (live.role != null && !live.role.isBlank()) parsed.role = live.role;
        if (live.accessibleName != null && !live.accessibleName.isBlank()) parsed.accessibleName = live.accessibleName;
        if (live.nodeValue != null && !live.nodeValue.isBlank()) parsed.nodeValue = live.nodeValue;
        if (!live.states.isEmpty()) {
            parsed.states.clear();
            parsed.states.addAll(live.states);
        }
        parsed.displayed = live.displayed;
        if (live.paint != null) parsed.paint = live.paint;
        if (!live.computed.isEmpty()) parsed.computed.putAll(live.computed);
        if (live.backendNodeId > 0) {
            parsed.backendNodeId = live.backendNodeId;
            parsed.nodeId = live.nodeId;
        }
        live.attributes.forEach((key, value) -> parsed.attributes.putIfAbsent(key, value));
    }

    private static DomNode elementNamed(DomNode node, String name) {
        if (node == null) return null;
        if (node.nodeType == 1 && name.equals(node.localName)) return node;
        for (DomNode child : node.children) {
            DomNode found = elementNamed(child, name);
            if (found != null) return found;
        }
        return null;
    }

    private static List<DomNode> elementChildren(DomNode node) {
        List<DomNode> elements = new ArrayList<>();
        if (node == null) return elements;
        for (DomNode child : node.children) {
            if (child != null && child.nodeType == 1) elements.add(child);
        }
        for (DomNode shadow : node.shadowRoots) {
            for (DomNode child : shadow.children) {
                if (child != null && child.nodeType == 1) elements.add(child);
            }
        }
        return elements;
    }

    private static void elementsInFrame(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.nodeType == 1) into.add(node);
        if (node.nodeType == 1 && node.iframe) {
            for (DomNode shadow : node.shadowRoots) elementsInFrame(shadow, into);
            return;
        }
        for (DomNode child : node.children) {
            if (child.nodeType == 9) continue;
            elementsInFrame(child, into);
        }
        for (DomNode shadow : node.shadowRoots) elementsInFrame(shadow, into);
    }

    private static void iframeDocuments(DomNode node, List<DomNode> into) {
        if (node == null) return;
        if (node.nodeType == 1 && node.iframe) {
            for (DomNode child : node.children) {
                if (child.nodeType == 9) into.add(child);
            }
            return;
        }
        for (DomNode child : node.children) {
            if (child.nodeType == 9) continue;
            iframeDocuments(child, into);
        }
        for (DomNode shadow : node.shadowRoots) iframeDocuments(shadow, into);
    }
}
