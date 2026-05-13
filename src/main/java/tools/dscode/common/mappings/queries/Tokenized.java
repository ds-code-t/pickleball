package tools.dscode.common.mappings.queries;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.dscode.common.mappings.MapConfigurations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static tools.dscode.common.GlobalConstants.META_FLAG;
import static tools.dscode.common.mappings.NodeMap.MapTypeKey;
import static tools.dscode.common.mappings.NodeMap.toSafeJsonNode;
import static tools.dscode.common.mappings.ValueFormatting.fromSafeJsonNode;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;

public final class Tokenized {

    public final String query;
    public final String getQuery;
    public final String suffix;
    public final String prefix;
    public List<String> mapNames;
    public final List<String> tokens;
    public final int tokenCount;

    public final boolean directPath;

    public final boolean isSingletonKey;
    public final boolean isValueAssignmentKey;

    private static final Pattern INDEX_PATTERN = Pattern.compile("#(?:\\.\\.|[\\d,-]+)");
    private static final Pattern INT_PATTERN = Pattern.compile("\\d+");
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("^(.*?)(?:\\s(as-[A-Z]+))?$");

    public static String topArrayFlag = META_FLAG + "_topArray";

    public static final List<String> allowedMapNames = Arrays.stream(MapConfigurations.MapType.values()).map(Enum::name)
            .toList();
    private static final Pattern SEGMENT_WITH_SPACES_PATTERN = Pattern.compile(
            "(?:(?<=^)|(?<=[.\\]]))" +               // left boundary: BOS or . or ]
                    "((?=[\\p{L}_][\\p{L}\\p{N}_ ]*[ ][\\p{L}\\p{N}_])[\\p{L}_][\\p{L}\\p{N}_ ]*[\\p{L}\\p{N}_])" +
                    "(?=(?:$|[.\\[]))"   // right boundary: EOS or . or [
    );

    public static String wrapSegments(String expr) {
        Matcher m = SEGMENT_WITH_SPACES_PATTERN.matcher(expr);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String segment = m.group(1);
            m.appendReplacement(sb, Matcher.quoteReplacement("`" + segment + "`"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public Tokenized(String inputQuery) {
        Matcher m = SUFFIX_PATTERN.matcher(inputQuery);
        m.matches();
        String q = m.group(1).strip();
        suffix = m.group(2);

        if (q.startsWith("[")) {
            throw new IllegalArgumentException(
                    "Leading array path is not supported for NodeMap/ObjectNode root: " + inputQuery
                            + ". Use a named root property such as 'key[0].A' instead."
            );
        }

        isSingletonKey = q.startsWith("-");
        if (isSingletonKey)
            q = q.substring(1);

        prefix = !Character.isLetter(q.charAt(0)) && q.charAt(0) != '_' ? q.replaceFirst("^([^A-Za-z_]+).*", "$1")
                : null;
        if (prefix != null)
            q = q.substring(prefix.length());
        int idx = q.indexOf("::");
        if (idx >= 0) {
            String mapNameString = q.substring(0, idx);
            q = q.substring(idx + 2);

            mapNames = Arrays.stream(mapNameString.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            List<String> invalid = new ArrayList<>();
            List<MapConfigurations.MapType> result = new ArrayList<>();

            for (String n : mapNames)
                if (allowedMapNames.contains(n))
                    result.add(MapConfigurations.MapType.valueOf(n));
                else
                    invalid.add(n);

            if (!invalid.isEmpty())
                throw new IllegalArgumentException("Invalid: " + invalid + ", allowed: " + allowedMapNames);
        }

        if (q.contains("#"))
            q = rewrite(q);
        q = q.strip().replaceAll("^\\$\\.", "").replaceAll("\\s*([\\(\\){}\\[\\].#:,-])\\s*", "$1");


        q = q.replaceAll("(^[A-Za-z0-9_\\s`]+)(\\..*|$)", "$1." + topArrayFlag + "$2");

        isValueAssignmentKey = q.endsWith("=");
        if (isValueAssignmentKey)
            q = q.substring(0, q.length() - 1);
        query = wrapSegments(q);
        directPath = !query.replaceAll("\\[-?\\d+\\]", "")
                .replaceAll("\\*|%|\\{|\\(|^|<|>|=|\\.\\.|,|:", "[").contains("\\[");
        tokens = Arrays.stream(query.replaceAll("(\\[[^\\[\\]]*\\])", ".$1").split("\\.")).collect(Collectors.toList());
        tokenCount = tokens.size();
        getQuery = query.replaceAll("\\." + topArrayFlag, "");
    }

    public static String rewrite(String input) {
        if (input == null)
            return null;
        return INDEX_PATTERN.matcher(input).replaceAll(mr -> {
            String body = mr.group().substring(1);
            String decremented = INT_PATTERN.matcher(body).replaceAll(nmr -> {
                int n = Integer.parseInt(nmr.group());
                return Integer.toString(n - 1);
            });
            return "[" + decremented + "]";
        });
    }

    public static final String AS_LIST_SUFFIX = "as-LIST";

    public Object get(JsonNode root) {

        List<JsonNode> list = getList(root, getQuery);
        if (list == null)
            return null;
        if (suffix == null) {
            if (list.isEmpty())
                return null;
            return fromSafeJsonNode(list.getLast());
        }
        if (suffix.equals(AS_LIST_SUFFIX)) {
            return fromSafeJsonNode(list);
//            return list.stream().map(ValueFormatting::fromSafeJsonNode).toList();
        }
        return null;
    }

    public List<JsonNode> getList(JsonNode root) {
        return getList(root, getQuery);
    }

    public List<JsonNode> getList(JsonNode root, String queryString) {
        JsonNode returnedNode = getWithPath(root, queryString);
        if (returnedNode == null)
            return null;
        if (returnedNode instanceof ArrayNode arrayNode)
            return arrayNode.valueStream().toList();
        List<JsonNode> nodeList = new ArrayList<>();

        nodeList.add(returnedNode);
        return nodeList;
    }

    // public List<JsonNode> getParentsList(JsonNode root) {
    // return getList(root, query + ".{ 'p': % }.p");
    // }

    public JsonNode getWithPath(JsonNode root) {
        return getWithPath(root, getQuery);
    }

    public static JsonNode getWithPath(JsonNode root, String passedQuery) {
        try {
            Expressions e = Expressions.parse(passedQuery.replaceAll("\\[\\*\\]", ""));
            return e.evaluate(root);
        } catch (ParseException | IOException | EvaluateException ex) {
            return null;
        }
    }

    public JsonNode setWithPath(ObjectNode root, Object finalValue, Tokenized... tokenizeds) {
        JsonNode currentValue = root;
        Tokenized lastTokenized = tokenizeds[tokenizeds.length - 1];
        currentValue = lastTokenized.setWithPath(finalValue);
        for (int t = tokenizeds.length - 2; t > 0; t--) {
            Tokenized tokenize = tokenizeds[t];
            currentValue = tokenize.setWithPath(currentValue);
        }
        Tokenized firstTokenized = tokenizeds[0];
        return firstTokenized.setWithPath(root, currentValue);
    }

    public JsonNode setWithPath(Object value) {
        JsonNode root = tokens.getFirst().startsWith("[") ? MAPPER.createArrayNode() : MAPPER.createObjectNode();
        setWithPath(root, value);
        return root;
    }

    public JsonNode setWithPath(JsonNode root, Object value) {
        boolean singleton = isSingletonKey || "-".equals(prefix);

        List<String> tokenList = new ArrayList<>(tokens);

        boolean containsWildcard = tokenList.stream().anyMatch(t -> t.equals("*") || t.equals("[*]"));

        if (singleton && tokenList.size() > 1 && tokenList.get(1).equals(topArrayFlag)) {
            tokenList.remove(1);
        } else {
            boolean containsTopArrayFlag = tokenList.size() > 1 && tokenList.get(1).equals(topArrayFlag);
            if (containsTopArrayFlag) {
                tokenList.set(1, containsWildcard ? "[*]" : "[]");
            }
        }

        if (containsWildcard) {
            setExistingWildcardPath(root, tokenList, 0, value);
            return root;
        }

        if (singleton && root instanceof ObjectNode objectNode && !tokenList.isEmpty()) {
            String rootField = tokenList.getFirst();
            if (rootField.startsWith("`") && rootField.endsWith("`")) {
                rootField = rootField.substring(1, rootField.length() - 1);
            }
            if (!rootField.startsWith("[")) {
                objectNode.remove(rootField);
            }
        }

        if (directPath) {
            JsonNode currentNode = root;
            int tokenSize = tokenList.size();

            for (int i = 0; i < tokenSize; i++) {
                String token = tokenList.get(i);
                String nextToken = i + 1 < tokenSize ? tokenList.get(i + 1) : null;

                Object valueToSet = nextToken == null
                        ? toSafeJsonNode(value)
                        : nextToken.startsWith("[") ? ArrayNode.class : ObjectNode.class;

                if (currentNode instanceof ArrayNode arrayNode) {
                    Integer index = token.startsWith("[")
                            ? token.equals("[]")
                            ? null
                            : Integer.parseInt(token.substring(1, token.length() - 1))
                            : null;

                    if (index != null && index < 0) {
                        ensureIndex(arrayNode, Math.abs(index));
                        index = arrayNode.size() + index;
                    }

                    currentNode = setArrayNode(arrayNode, index, valueToSet);

                } else if (currentNode instanceof ObjectNode objectNode) {
                    currentNode = setProperty(objectNode, token, valueToSet);

                } else {
                    throw new RuntimeException(
                            "Cannot set '" + currentNode + "'. JsonNode needs to be ArrayNode or ObjectNode");
                }
            }

        } else {
            String lastToken = tokenList.getLast();

            String effectiveQuery = String.join(".", tokenList);
            List<JsonNode> parentNodes = getList(root, effectiveQuery.replaceAll("\\.?" + Pattern.quote(lastToken) + "$", ""));

            for (JsonNode parent : parentNodes) {
                if (parent instanceof ArrayNode arrayNode) {
                    if (lastToken.startsWith("[")) {
                        if (lastToken.equals("[*]")) {
                            JsonNode valueNode = toSafeJsonNode(value);
                            IntStream.range(0, arrayNode.size())
                                    .forEach(i -> arrayNode.set(i, valueNode.deepCopy()));
                        } else if (lastToken.equals("[]")) {
                            arrayNode.add(toSafeJsonNode(value));
                        } else {
                            arrayNode.set(arrayNode.size() - 1, toSafeJsonNode(value));
                        }
                    }
                } else if (parent instanceof ObjectNode objectNode) {
                    if (!lastToken.startsWith("[") && objectNode.has(lastToken)) {
                        objectNode.set(lastToken, toSafeJsonNode(value));
                    }
                }
            }
        }

        return root;
    }
    private static JsonNode setProperty(ObjectNode objectNode, String fieldName, Object value) {
        if (fieldName.startsWith("`") && fieldName.endsWith("`")) {
            fieldName = fieldName.substring(1, fieldName.length() - 1);
        }

        JsonNode valueToSet;
        if (value instanceof JsonNode valueNode) {
            valueToSet = valueNode;
        } else {
            JsonNode existing = objectNode.get(fieldName);
            boolean wantsArray = value.equals(ArrayNode.class);
            // Reuse existing node only if it's the right container type
            if (wantsArray && existing instanceof ArrayNode) {
                valueToSet = existing;
            } else if (!wantsArray && existing instanceof ObjectNode) {
                valueToSet = existing;
            } else {
                // Wrong type or absent - create fresh
                valueToSet = wantsArray ? MAPPER.createArrayNode() : MAPPER.createObjectNode();
            }
        }

        objectNode.set(fieldName, valueToSet);
        return valueToSet;
    }

    public static JsonNode setArrayNode(ArrayNode arrayNode, Integer index, Object value) {
        if (index == null) {
            JsonNode valueToSet = value instanceof JsonNode valueNode
                    ? valueNode
                    : value.equals(ArrayNode.class) ? MAPPER.createArrayNode() : MAPPER.createObjectNode();

            arrayNode.add(valueToSet);
            return valueToSet;
        }

        JsonNode currentlySet = ensureIndex(arrayNode, index);

        if (value instanceof JsonNode valueNode) {
            arrayNode.set(index, valueNode);
            return valueNode;
        }

        boolean wantsArray = value.equals(ArrayNode.class);

        if (wantsArray && currentlySet instanceof ArrayNode) {
            return currentlySet;
        }

        if (!wantsArray && currentlySet instanceof ObjectNode) {
            return currentlySet;
        }

        JsonNode defaultValue = wantsArray ? MAPPER.createArrayNode() : MAPPER.createObjectNode();
        arrayNode.set(index, defaultValue);
        return defaultValue;
    }

    public static JsonNode ensureIndex(ArrayNode array, int index) {
        if (array == null || index < 0) {
            throw new IllegalArgumentException("ArrayNode is null or index < 0");
        }

        while (array.size() <= index) {
            array.add(NullNode.instance);
        }

        return array.get(index);
    }



    private static void setExistingWildcardPath(JsonNode currentNode, List<String> tokenList, int tokenIndex, Object value) {
        if (currentNode == null || currentNode.isNull() || tokenIndex >= tokenList.size()) {
            return;
        }

        String token = tokenList.get(tokenIndex);
        boolean lastToken = tokenIndex == tokenList.size() - 1;

        if (token.equals("[*]")) {
            if (!(currentNode instanceof ArrayNode arrayNode)) {
                return;
            }

            if (lastToken) {
                JsonNode valueNode = toSafeJsonNode(value);
                for (int i = 0; i < arrayNode.size(); i++) {
                    arrayNode.set(i, valueNode.deepCopy());
                }
                return;
            }

            for (JsonNode child : arrayNode) {
                setExistingWildcardPath(child, tokenList, tokenIndex + 1, value);
            }
            return;
        }

        if (token.equals("*")) {
            if (currentNode instanceof ObjectNode objectNode) {
                if (lastToken) {
                    JsonNode valueNode = toSafeJsonNode(value);
                    List<String> fieldNames = new ArrayList<>();
                    objectNode.fieldNames().forEachRemaining(fieldNames::add);

                    for (String fieldName : fieldNames) {
                        objectNode.set(fieldName, valueNode.deepCopy());
                    }
                    return;
                }

                List<JsonNode> children = new ArrayList<>();
                objectNode.elements().forEachRemaining(children::add);

                for (JsonNode child : children) {
                    setExistingWildcardPath(child, tokenList, tokenIndex + 1, value);
                }
                return;
            }

            if (currentNode instanceof ArrayNode arrayNode) {
                if (lastToken) {
                    JsonNode valueNode = toSafeJsonNode(value);
                    for (int i = 0; i < arrayNode.size(); i++) {
                        arrayNode.set(i, valueNode.deepCopy());
                    }
                    return;
                }

                for (JsonNode child : arrayNode) {
                    setExistingWildcardPath(child, tokenList, tokenIndex + 1, value);
                }
            }

            return;
        }

        if (token.startsWith("[")) {
            if (!(currentNode instanceof ArrayNode arrayNode)) {
                return;
            }

            if (token.equals("[]")) {
                return;
            }

            int index = Integer.parseInt(token.substring(1, token.length() - 1));

            if (index < 0) {
                index = arrayNode.size() + index;
            }

            if (index < 0 || index >= arrayNode.size()) {
                return;
            }

            if (lastToken) {
                arrayNode.set(index, toSafeJsonNode(value));
            } else {
                setExistingWildcardPath(arrayNode.get(index), tokenList, tokenIndex + 1, value);
            }

            return;
        }

        if (!(currentNode instanceof ObjectNode objectNode)) {
            return;
        }

        String fieldName = token;
        if (fieldName.startsWith("`") && fieldName.endsWith("`")) {
            fieldName = fieldName.substring(1, fieldName.length() - 1);
        }

        if (lastToken) {
            objectNode.set(fieldName, toSafeJsonNode(value));
            return;
        }

        JsonNode child = objectNode.get(fieldName);
        setExistingWildcardPath(child, tokenList, tokenIndex + 1, value);
    }







}
