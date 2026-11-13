package dev.domengine.query;

import dev.domengine.cdp.CdpException;
import dev.domengine.log.EngineLog;
import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Frame and shadow roots for a query. The engine does not know about Pickleball. */
public final class Frames {
    private Frames() {
    }

    public record Doc(DomNode node, String path) {
    }

    /** Applies {@code --frame} then {@code --shadow}. A missing host is {@code not found}. */
    public static DomNode scope(DomNode root, Map<String, String> flags) {
        DomNode current = root;
        String frames = flags.get("frame");
        if (frames != null && !frames.isBlank()) {
            for (String token : frames.split(",")) {
                String id = token.trim();
                if (id.isEmpty()) continue;
                DomNode frame = findFrame(current, id);
                if (frame == null) throw new CdpException("not found");
                DomNode document = content(frame);
                if (document == null) throw new CdpException("not found");
                current = document;
                EngineLog.info("frame", "frame " + id);
            }
        }
        String shadows = flags.get("shadow");
        if (shadows != null && !shadows.isBlank()) {
            for (String token : shadows.split(",")) {
                String id = token.trim();
                if (id.isEmpty()) continue;
                DomNode host = Query.findId(current, id);
                if (host == null) host = findByAttr(current, id);
                if (host == null || host.shadowRoots.isEmpty()) throw new CdpException("not found");
                current = host.shadowRoots.get(0);
                EngineLog.info("frame", "shadow " + id);
            }
        }
        return current;
    }

    /** Main document first, then frame documents depth-first. */
    public static List<Doc> documents(DomNode root) {
        List<Doc> docs = new ArrayList<>();
        if (root == null) return docs;
        docs.add(new Doc(root, ""));
        walk(root, "", docs);
        return docs;
    }

    private static void walk(DomNode node, String path, List<Doc> docs) {
        for (DomNode child : node.children) {
            if (child.iframe) {
                String next = path.isEmpty()
                        ? Long.toString(child.backendNodeId)
                        : path + "/" + child.backendNodeId;
                for (DomNode nested : child.children) {
                    if (nested.nodeType == 9) {
                        docs.add(new Doc(nested, next));
                        walk(nested, next, docs);
                    }
                }
            } else if (child.nodeType != 9) {
                walk(child, path, docs);
            }
        }
    }

    private static DomNode findFrame(DomNode node, String id) {
        if (node == null) return null;
        if (node.iframe && (id.equals(node.attributes.get("id")) || id.equals(node.id())
                || id.equals(Long.toString(node.backendNodeId)) || id.equals(node.frameId))) {
            return node;
        }
        for (DomNode child : node.children) {
            DomNode found = findFrame(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private static DomNode content(DomNode frame) {
        for (DomNode child : frame.children) {
            if (child.nodeType == 9) return child;
        }
        return null;
    }

    private static DomNode findByAttr(DomNode node, String id) {
        if (node == null) return null;
        if (id.equals(node.attributes.get("id"))) return node;
        for (DomNode child : node.children) {
            DomNode found = findByAttr(child, id);
            if (found != null) return found;
        }
        for (DomNode shadow : node.shadowRoots) {
            DomNode found = findByAttr(shadow, id);
            if (found != null) return found;
        }
        return null;
    }
}
