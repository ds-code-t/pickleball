package tools.dscode.common.mappings;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static tools.dscode.common.mappings.NodeMap.MAPPER;
import static tools.dscode.common.mappings.NodeMap.toSafeJsonNode;

public class QueryOperations {

    public static boolean setValue(ObjectNode root, String jsonataExpr, Object value)
            throws ParseException, IOException, EvaluateException {
        boolean valueSet = false;

        // normalize: drop leading "$."
        jsonataExpr = jsonataExpr.strip().replaceAll("^\\$\\.", "");

        // detect tail [index] (optional)
        boolean isArrayNode = jsonataExpr.matches(".*\\[\\s*(?:\\d+)?\\s*\\]$");
        String mainPath = jsonataExpr;
        Integer arrayIndex = null;
        if (isArrayNode) {
            mainPath = jsonataExpr.substring(0, jsonataExpr.lastIndexOf("["));
            String arrayIndexString = jsonataExpr.substring(jsonataExpr.lastIndexOf("[") + 1,
                jsonataExpr.lastIndexOf("]"));
            if (!arrayIndexString.isBlank()) {
                try {
                    arrayIndex = Integer.parseInt(arrayIndexString.strip());
                } catch (Exception e) {
                    throw new RuntimeException("invalid array syntax '" + arrayIndexString + "'");
                }
            }
        }

        // parent path + final field name (quoted names allowed with '.'
        // separators; no brackets for names)
        int dot = mainPath.lastIndexOf('.');
        boolean singleProperty = (dot < 0);

        String parentPath = singleProperty ? "$" : mainPath.substring(0, dot);
        isArrayNode = isArrayNode || singleProperty; // (intentional) treat
                                                     // top-level field as array
                                                     // when creating

        String seg = singleProperty ? mainPath : mainPath.substring(dot + 1);
        // final String fieldName = seg.strip();
        seg = seg.strip();
        // unwrap a single pair of surrounding quotes if present
        if (seg.length() >= 2 &&
                ((seg.charAt(0) == '\'' && seg.charAt(seg.length() - 1) == '\'') ||
                        (seg.charAt(0) == '\"' && seg.charAt(seg.length() - 1) == '\"'))) {
            seg = seg.substring(1, seg.length() - 1);
        }
        final String fieldName = seg;

        // "directness" heuristic: allow [digits], disallow
        // wildcards/slices/[*], no trailing parent operator
        String normalized = jsonataExpr.replaceAll("\\s+", "");
        String s = normalized.replaceAll("\\[\\d+\\]", ""); // strip allowed
                                                            // [123]
        s = s.replaceAll("(\\*\\*|(?<=\\.)\\*|\\[\\*\\])", ""); // remove **,
                                                                // .*, [*]
        final boolean isDirect = !s.contains("[") && !s.endsWith("^");

        if (isDirect) {
            // DIRECT: parent must be an ObjectNode (or ArrayNode if parentPath
            // points to array and you intend to append)
            Expressions e = Expressions.parse(parentPath);
            JsonNode parent = e.evaluate(root);

            if (parent instanceof ObjectNode parentObject) {
                JsonNode child = parentObject.get(fieldName);

                if (child == null && isArrayNode) {
                    child = MAPPER.createArrayNode();
                    parentObject.set(fieldName, child);
                }

                if (child instanceof ArrayNode childArray) {
                    if (arrayIndex == null) {
                        childArray.add(toSafeJsonNode(value));
                        valueSet = true;
                    } else {
                        ensureIndex(childArray, arrayIndex, toSafeJsonNode(value));
                        valueSet = true;
                    }
                } else {
                    if (isArrayNode) {
                        throw new RuntimeException(
                            "'" + fieldName + "' is not an Array but is trying to be set as an Array");
                    }
                    parentObject.set(fieldName, toSafeJsonNode(value));
                    valueSet = true;
                }

            } else if (parent instanceof ArrayNode parentArray) {
                // (intentional) if parent resolves to an array directly, just
                // append
                parentArray.add(toSafeJsonNode(value));
                valueSet = true;

            } else if (parent == null) {
                throw new RuntimeException("parent object of '" + fieldName + "' is not defined");

            } else {
                throw new RuntimeException("parent object of '" + fieldName + "' must be an ObjectNode or ArrayNode");
            }

        } else {
            // NON-DIRECT: build (parent, currentChild) pairs only for
            // ObjectNode parents (intentional)
            List<JsonNode> parentNodes = evalToList(root, parentPath);
            List<Pair> pairs = parentNodes.stream()
                    .filter(n -> n instanceof ObjectNode)
                    .map(n -> (ObjectNode) n)
                    .map(po -> new Pair(po, po.get(fieldName)))
                    .filter(p -> p.current() != null)
                    .toList();

            for (Pair pair : pairs) {
                JsonNode obj = pair.current();
                if (obj instanceof ArrayNode childArray) {
                    if (arrayIndex == null) {
                        childArray.add(toSafeJsonNode(value));
                        valueSet = true;
                    } else {
                        ensureIndex(childArray, arrayIndex, toSafeJsonNode(value));
                        valueSet = true;
                    }
                } else {
                    pair.parent().set(fieldName, toSafeJsonNode(value));
                    valueSet = true;
                }
            }
        }

        return valueSet;
    }

    record Pair(ObjectNode parent, JsonNode current) {
    }

    /**
     * Evaluate JSONata; always return a List. Prints parse/eval errors and
     * returns [] on failure.
     */
    public static List<JsonNode> evalToList(JsonNode input, String jsonataExpr) {
        try {
            Expressions e = Expressions.parse(jsonataExpr);
            JsonNode out = e.evaluate(input);
            if (out == null || out.isNull())
                return List.of();
            if (out.isArray()) {
                List<JsonNode> list = new ArrayList<>();
                out.forEach(list::add);
                return list;
            }
            return List.of(out);
        } catch (ParseException pe) {
            System.err.println("JSONata syntax error: " + pe.getMessage());
            return List.of();
        } catch (Exception ex) {
            System.err.println("JSONata evaluation error: " + ex.getMessage());
            return List.of();
        }
    }

    public static void ensureIndex(ArrayNode array, int index, JsonNode value) {
        if (array == null || index < 0) {
            throw new IllegalArgumentException("ArrayNode is null or index < 0");
        }
        while (array.size() <= index) {
            array.add(NullNode.instance);
        }
        array.set(index, value == null ? NullNode.instance : value);
    }


}
