package tools.ds.modkit.mappings.queries;

import com.api.jsonata4java.expressions.EvaluateException;
import com.api.jsonata4java.expressions.Expressions;
import com.api.jsonata4java.expressions.ParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import tools.ds.modkit.mappings.ParsingMap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static tools.ds.modkit.blackbox.BlackBoxBootstrap.metaFlag;
import static tools.ds.modkit.mappings.NodeMap.MAPPER;
import static tools.ds.modkit.mappings.NodeMap.MapTypeKey;

public final class Tokenized {

    public final String query;
    public final String getQuery;
    public final String suffix;
    public final String prefix;
    public List<String> mapNames;
    public final List<String> tokens;
    public final int tokenCount;

    public final boolean directPath;

    private static final Pattern INDEX_PATTERN = Pattern.compile("#(?:\\.\\.|[\\d,-]+)");
    private static final Pattern INT_PATTERN = Pattern.compile("\\d+");
    private static final Pattern SUFFIX_PATTERN = Pattern.compile("^(.*?)(?:\\s(as-[A-Z]+))?$");


    public static String topArrayFlag = metaFlag + "_topArray";

    public static final List<String> allowedMapNames =  Arrays.stream(ParsingMap.MapType.values()).map(Enum::name).toList();

    public Tokenized(String inputQuery) {
        Matcher m = SUFFIX_PATTERN.matcher(inputQuery);
        m.matches();
        String q = m.group(1).strip();
        suffix = m.group(2);
        prefix = !Character.isLetter(q.charAt(0)) && q.charAt(0) != '_' ? q.replaceFirst("^([^A-Za-z_]+).*", "$1") : null;
        if (prefix != null)
            q = q.substring(prefix.length());
        int idx = q.indexOf("::");
        if(idx >= 0) {
            q = q.substring(idx + 2);
            String mapNameString  = q.substring(0, idx);
            mapNames = Arrays.stream(mapNameString.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            List<String> invalid = new ArrayList<>();
            List<ParsingMap.MapType> result = new ArrayList<>();
            for (String n : mapNames) if (allowedMapNames.contains(n)) result.add(ParsingMap.MapType.valueOf(n)); else invalid.add(n);
            if (!invalid.isEmpty()) throw new IllegalArgumentException("Invalid: " + invalid + ", allowed: " + allowedMapNames);
        }

        if (q.contains("#"))
            q = rewrite(q);
        query = q.strip().replaceAll("^\\$\\.", "")
                .replaceAll("(^[A-Za-z0-9_]+)(\\..*|$)", "$1." + topArrayFlag + "$2")
                .replaceAll("\\s*([\\(\\){}\\[\\].#:,-])\\s*", "$1");
//                .replaceAll("\\[\\*\\]", "");

        directPath = !query.replaceAll("\\[-?\\d+\\]", "")
                .replaceAll("\\*|%|\\{|\\(|^|<|>|=|\\.\\.|,|:", "[").contains("\\[");
        tokens = Arrays.stream(query.replaceAll("(\\[[^\\[\\]]*\\])", ".$1").split("\\.")).collect(Collectors.toList());
        tokenCount = tokens.size();
        getQuery = query.replaceAll("\\." + topArrayFlag, "");
    }

    public static String rewrite(String input) {
        if (input == null) return null;
        return INDEX_PATTERN.matcher(input).replaceAll(mr -> {
            String body = mr.group().substring(1);
            String decremented = INT_PATTERN.matcher(body).replaceAll(nmr -> {
                int n = Integer.parseInt(nmr.group());
                return Integer.toString(n - 1);
            });
            return "[" + decremented + "]";
        });
    }

    public Object get(JsonNode root) {
        List<JsonNode> list = getList(root, getQuery);
        if (list == null) return null;
        if (suffix == null) {
            if (list.isEmpty())
                return null;
            return list.getLast();
        }
        if (suffix.equals("as-LIST"))
            return list;
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


//    public List<JsonNode> getParentsList(JsonNode root) {
//        return getList(root, query + ".{ 'p': % }.p");
//    }


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
        System.out.println("@@setWithPath: " + tokens + " , val: " + value);
        boolean isRootNode = root.has(MapTypeKey);
        boolean containsTopArrayFlag = tokenCount > 1 && tokens.get(1).equals(topArrayFlag);
        boolean processTopArrayFlag = (isRootNode && tokenCount == 2 && containsTopArrayFlag);
        if (containsTopArrayFlag)
            tokens.set(1, "[-1]");
        if (directPath) {

            JsonNode currentNode = root;
//            List<String> tokenList = processTopArrayFlag ? tokens : tokens.stream().filter(s -> !s.equals(topArrayFlag)).toList();
//            int tokenSize = tokenList.size();

            for (int i = 0; i < tokenCount; i++) {
                String token = tokens.get(i);
                String nextToken = i + 1 < tokenCount ? tokens.get(i + 1) : null;
                Object valueToSet = nextToken == null ? MAPPER.valueToTree(value) : processTopArrayFlag || nextToken.startsWith("[") ? ArrayNode.class : ObjectNode.class;
                if (currentNode instanceof ArrayNode arrayNode) {
                    if (i == 1 && processTopArrayFlag)
                        arrayNode.add(NullNode.instance);

                    Integer index = token.startsWith("[") ? token.equals("[]") ? arrayNode.size() : Integer.parseInt(token.substring(1, token.length() - 1)) : null;




                    if (index < 0) {
                        ensureIndex(arrayNode, Math.abs(index));
                        index = arrayNode.size() + index;
                    }
                    currentNode = setArrayNode(arrayNode, index, valueToSet);
                } else if (currentNode instanceof ObjectNode objectNode) {
                    currentNode = setProperty(objectNode, token, valueToSet);
                } else {
                    throw new RuntimeException("Cannot set '" + currentNode + "'. JsonNode needs to be ArrayNode or ObjectNode");
                }
            }
        } else {
            String lastToken = tokens.getLast();

            List<JsonNode> parentNodes = getList(root, query.replaceAll("\\.?" + Pattern.quote(lastToken) + "$", ""));
            for (JsonNode parent : parentNodes) {
                if (parent instanceof ArrayNode arrayNode) {
                    if (lastToken.startsWith("[")) {
                        if (lastToken.equals("[*]")) {
                            IntStream.range(0, arrayNode.size()).forEach(i -> arrayNode.set(i, MAPPER.valueToTree(value)));
                        } else if (lastToken.equals("[]")) {
                            arrayNode.add(MAPPER.valueToTree(value));
                        } else if (lastToken.startsWith("[")) {
                            arrayNode.set(arrayNode.size() - 1, MAPPER.valueToTree(value));
                        }
                    }
                } else if (parent instanceof ObjectNode objectNode) {
                    if (!lastToken.startsWith("[")) {
                        if (objectNode.has(lastToken)) {
                            objectNode.set(lastToken, MAPPER.valueToTree(value));
                        }
                    }
                }
            }
        }
        return root;
    }


    public static JsonNode setProperty(ObjectNode objectNode, String fieldName, Object value) {
        JsonNode valueToSet = value instanceof JsonNode valueNode ? valueNode : objectNode.get(fieldName);
        if (valueToSet == null)
            valueToSet = value.equals(ArrayNode.class) ? MAPPER.createArrayNode() : MAPPER.createObjectNode();
        objectNode.set(fieldName, valueToSet);
        return valueToSet;
    }

    public static JsonNode setArrayNode(ArrayNode arrayNode, Integer index, Object value) {
        if (index == null) {
            JsonNode valueToSet = value instanceof JsonNode valueNode ? valueNode : value.equals(ArrayNode.class) ? MAPPER.createArrayNode() : MAPPER.createObjectNode();
            arrayNode.add(valueToSet);
            return valueToSet;
        }


        JsonNode currentlySet = ensureIndex(arrayNode, index);
        if (value instanceof JsonNode valueNode) {
            arrayNode.set(index, valueNode);
            return valueNode;
        }
        if (currentlySet != null)
            return currentlySet;

        JsonNode defaultValue = value.equals(ArrayNode.class) ? MAPPER.createArrayNode() : MAPPER.createObjectNode();
        arrayNode.set(index, defaultValue);
        return defaultValue;
    }


    public static JsonNode ensureIndex(ArrayNode array, int index) {

        if (array == null || index < 0) {
            throw new IllegalArgumentException("ArrayNode is null or index < 0");
        }
        while (array.size() < index) {
            array.add(NullNode.instance);
        }
        return array.get(index);
    }

}
