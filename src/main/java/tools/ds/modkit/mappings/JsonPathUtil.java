package tools.ds.modkit.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

public class JsonPathUtil {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** A handle to a resolved node plus the info needed to replace it in its parent. */
    public static final class NodeHandle {
        /** The resolved child node (may be null if not found and create=false). */
        public final JsonNode node;
        /** The direct parent container: either ObjectNode or ArrayNode (may be null if unresolved). */
        public final JsonNode parent;
        /** The key under which this child lives, if parent is an ObjectNode (else null). */
        public final String fieldName;
        /** The index under which this child lives, if parent is an ArrayNode (else null). */
        public final Integer index;

        public NodeHandle(JsonNode node, JsonNode parent, String fieldName, Integer index) {
            this.node = node;
            this.parent = parent;
            this.fieldName = fieldName;
            this.index = index;
        }

        /** Convenience factory for object field children. */
        public static NodeHandle ofObject(JsonNode node, ObjectNode parent, String fieldName) {
            return new NodeHandle(node, parent, fieldName, null);
        }

        /** Convenience factory for array element children. */
        public static NodeHandle ofArray(JsonNode node, ArrayNode parent, int index) {
            return new NodeHandle(node, parent, null, index);
        }

        /** Replace this child with a new value (mutates the parent). */
        public void replace(JsonNode newValue) {
            if (parent instanceof ObjectNode obj && fieldName != null) {
                obj.set(fieldName, newValue);
            } else if (parent instanceof ArrayNode arr && index != null) {
                arr.set(index, newValue);
            } else {
                throw new IllegalStateException("Parent type not compatible for replace()");
            }
        }
    }

    /**
     * Retrieve or create the final node addressed by a simple JSONPath-like query.
     * - Supports: "$.a.b[2].c", "settings.options[2]", "logs[]"
     * - Disallows wildcards/filters/recursion.
     *
     * Behavior:
     * - If a segment is "field[]" and it's the last segment:
     *     * Ensures an ArrayNode at that field if create=true; returns a handle to that array (parent=obj, fieldName="field").
     * - If a segment is "field[index]":
     *     * Pads the array with nulls when create=true; returns a handle to that element if last,
     *       or ensures the element is an ObjectNode to descend further when not last.
     * - If a segment is a plain field:
     *     * Creates an ObjectNode at that field when needed and create=true (for descent or final node).
     *
     * Failures (or create=false where something is missing) return a NodeHandle with all fields null.
     */
    public static JsonPathUtil.NodeHandle getOrCreate(ObjectNode root, String path, boolean create) {
      
        if (path.startsWith("$.")) path = path.substring(2);
        else if (path.startsWith("$")) path = path.substring(1);
      

        String[] parts = path.isEmpty() ? new String[0] : path.split("\\.");
        JsonNode current = root;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean last = (i == parts.length - 1);

            int br = part.indexOf('[');
            if (br >= 0) {
                if (!(current instanceof ObjectNode obj)) {
                    return new NodeHandle(null,null,null,null);
                }
                if (!part.endsWith("]") || br == 0) {
                  
                    return new NodeHandle(null,null,null,null);
                }
                String field = part.substring(0, br);
                String idxStr = part.substring(br + 1, part.length() - 1);
                JsonNode maybeArr = obj.get(field);
              

                if ((maybeArr == null || maybeArr.isNull())) {
                    if (!create) { System.out.println("[GOC] MISSING (create=false)"); return new NodeHandle(null,null,null,null); }
                    maybeArr = F.arrayNode();
                    obj.set(field, maybeArr);
                  
                }
                if (!maybeArr.isArray()) {
                  
                    return new NodeHandle(null,null,null,null);
                }
                ArrayNode arr = (ArrayNode) maybeArr;

                if (idxStr.isEmpty()) {
                    if (!last) { System.out.println("[GOC] FAIL: cannot descend past []"); return new NodeHandle(null,null,null,null); }
                    return NodeHandle.ofObject(arr, obj, field);
                }

                int idx;
                try { idx = Integer.parseInt(idxStr); } catch (NumberFormatException e) {
                  
                    return new NodeHandle(null,null,null,null);
                }
                while (arr.size() <= idx) {
                    if (!create) { System.out.println("[GOC] MISSING index (create=false)"); return new NodeHandle(null,null,null,null); }
                    arr.add(NullNode.getInstance());
                }
                JsonNode elem = arr.get(idx);
              

                if (last) return NodeHandle.ofArray(elem, arr, idx);

                if (elem == null || elem.isNull()) {
                    if (!create) { System.out.println("[GOC] MISSING elem (create=false)"); return new NodeHandle(null,null,null,null); }
                    elem = F.objectNode();
                    arr.set(idx, elem);
                  
                }
                if (!elem.isObject()) { System.out.println("[GOC] FAIL: elem not object"); return new NodeHandle(null,null,null,null); }
                current = elem;
            } else {
                if (!(current instanceof ObjectNode obj)) {
                  
                    return new NodeHandle(null,null,null,null);
                }
                JsonNode next = obj.get(part);
              

                if (last) {
                    if (next == null && create) {
                        next = F.objectNode();
                        obj.set(part, next);
                      
                    }
                    return NodeHandle.ofObject(next, obj, part);
                }
                if (next == null || next.isNull()) {
                    if (!create) { System.out.println("[GOC] MISSING next (create=false)"); return new NodeHandle(null,null,null,null); }
                    next = F.objectNode();
                    obj.set(part, next);
                  
                }
                if (!next.isObject()) { System.out.println("[GOC] FAIL: next not object"); return new NodeHandle(null,null,null,null); }
                current = next;
            }
        }
      
        return new NodeHandle(current, null, null, null);
    }

}
