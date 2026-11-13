package dev.domengine.snapshot;

import dev.domengine.tree.DomNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Absolute index-qualified XPaths. The offline query engine rejects {@code [n]}
 * predicates, so this index is the resolver that honors them.
 */
public final class XPathIndex {
    private XPathIndex() {
    }

    public static Result assign(DomNode root) {
        Result result = new Result();
        if (root == null) return result;
        result.frames.add(new Frame("main", "", "", "", 0, 0));
        assignSiblings(root.children, "", "", "main", "", result);
        return result;
    }

    public static DomNode resolve(DomNode root, String xpath, String shadowPath) {
        return resolve(root, "", xpath, shadowPath);
    }

    public static DomNode resolve(DomNode root, String framePath, String xpath, String shadowPath) {
        if (root == null || xpath == null || xpath.isBlank()) return null;
        DomNode scope = root;
        if (framePath != null && !framePath.isBlank()) {
            for (String segment : framePath.split(" >> ")) {
                DomNode iframe = step(scope, segment.trim());
                if (iframe == null) return null;
                scope = iframe;
            }
        }
        if (shadowPath != null && !shadowPath.isBlank()) {
            for (String hostPath : shadowPath.split(" >> ")) {
                DomNode host = step(scope, hostPath.trim());
                if (host == null || host.shadowRoots.isEmpty()) return null;
                scope = host.shadowRoots.get(0);
            }
        }
        return step(scope, xpath);
    }

    private static void assignSiblings(
            List<DomNode> children,
            String parentPath,
            String shadowPath,
            String frameId,
            String framePath,
            Result result
    ) {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        if (children == null) return;
        for (DomNode child : children) {
            if (child == null) continue;
            if (child.nodeType == 9) {
                assignSiblings(child.children, parentPath, shadowPath, frameId, framePath, result);
                continue;
            }
            if (child.nodeType != 1) continue;
            String tag = tag(child);
            int index = counts.merge(tag, 1, Integer::sum);
            child.xpath = parentPath + "/" + tag + "[" + index + "]";
            child.shadowPath = shadowPath == null ? "" : shadowPath;
            child.frameId = frameId == null || frameId.isBlank() ? "main" : frameId;
            child.framePath = framePath == null ? "" : framePath;
            result.frameIds.put(child, child.frameId);
            result.framePaths.put(child, framePath == null ? "" : framePath);
            if (child.iframe) {
                String nextFrame = framePath == null || framePath.isBlank()
                        ? child.xpath
                        : framePath + " >> " + child.xpath;
                String nextId = "f" + child.backendNodeId;
                result.frames.add(new Frame(nextId, frameId, "", nextFrame, child.x, child.y));
                assignSiblings(child.children, "", "", nextId, nextFrame, result);
                continue;
            }
            assignSiblings(child.children, child.xpath, child.shadowPath, child.frameId, framePath, result);
            for (DomNode shadow : child.shadowRoots) {
                String nextShadow = child.shadowPath == null || child.shadowPath.isBlank()
                        ? child.xpath
                        : child.shadowPath + " >> " + child.xpath;
                assignSiblings(shadow.children, "", nextShadow, child.frameId, framePath, result);
            }
        }
    }

    private static DomNode step(DomNode scope, String xpath) {
        if (scope == null || xpath == null || xpath.isBlank()) return null;
        DomNode current = scope;
        for (String raw : xpath.split("/")) {
            if (raw.isBlank()) continue;
            Step step = Step.parse(raw);
            if (step == null) return null;
            DomNode found = null;
            int seen = 0;
            for (DomNode child : current.children) {
                if (child == null || child.nodeType != 1) continue;
                if (!tag(child).equals(step.tag)) continue;
                seen++;
                if (seen == step.index) {
                    found = child;
                    break;
                }
            }
            if (found == null) return null;
            current = found;
        }
        return current;
    }

    private static String tag(DomNode node) {
        if (node.localName != null && !node.localName.isBlank()) return node.localName.toLowerCase(Locale.ROOT);
        String name = node.nodeName == null ? "node" : node.nodeName.toLowerCase(Locale.ROOT);
        if (name.startsWith("#")) name = name.substring(1);
        return name.isBlank() ? "node" : name;
    }

    public record Frame(String id, String parentId, String url, String framePath, double x, double y) {
    }

    public static final class Result {
        private final List<Frame> frames = new ArrayList<>();
        private final Map<DomNode, String> frameIds = new IdentityHashMap<>();
        private final Map<DomNode, String> framePaths = new IdentityHashMap<>();

        public List<Frame> frames() {
            return List.copyOf(frames);
        }

        public String frameId(DomNode node) {
            if (node == null) return "main";
            return frameIds.getOrDefault(node, node.frameId == null || node.frameId.isBlank() ? "main" : node.frameId);
        }

        public String framePath(DomNode node) {
            return node == null ? "" : framePaths.getOrDefault(node, "");
        }
    }

    private record Step(String tag, int index) {
        static Step parse(String raw) {
            String text = raw.trim();
            int bracket = text.indexOf('[');
            String tag = bracket < 0 ? text : text.substring(0, bracket);
            int index = 1;
            if (bracket >= 0 && text.endsWith("]")) {
                try {
                    index = Integer.parseInt(text.substring(bracket + 1, text.length() - 1).trim());
                } catch (NumberFormatException failure) {
                    return null;
                }
            }
            if (tag.isBlank() || index < 1) return null;
            return new Step(tag.toLowerCase(Locale.ROOT), index);
        }
    }
}
