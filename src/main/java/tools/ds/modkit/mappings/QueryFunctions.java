package tools.ds.modkit.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.guava.GuavaModule;

import java.util.List;
import java.util.regex.Pattern;


public class QueryFunctions {

    private static final Pattern INDEX_PATTERN = Pattern.compile("#[\\d:,]+");
    private static final Pattern INT_PATTERN = Pattern.compile("\\d+");

    public static final ObjectMapper MAPPER = new ObjectMapper();



//    public List<JsonNode> getValues(JsonNode root, String input) {
//        String query = input.strip();
//        if(query.startsWith("$")
//
//
//    }


    public static String preParseKey(String key) {
        return rewrite(key.replaceAll("\\s*([{}\\[\\].#:,-])\\s*", "$1"))
                .replaceAll("(^[A-Za-z0-9_]+)([^\\[].*|$)", "$1[-1]$2")
                .replaceAll("\\[*\\]", "")
                ;
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


    private static Integer parseOrNull(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static JsonNode ensureIndex(ArrayNode array, int index, JsonNode value, boolean forceOverwrite) {
        if (array == null || index < 0) {
            throw new IllegalArgumentException("ArrayNode is null or index < 0");
        }
        while (array.size() <= index) {
            array.add(NullNode.instance);
        }
        JsonNode current = array.get(index);

        if (forceOverwrite || current == null) {
            array.set(index, value == null ? NullNode.instance : value);
            return value;
        }
        return current;
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static ArrayNode asArray(JsonNode node) {
        if (node == null) return F.arrayNode();
        if (node.isArray()) return (ArrayNode) node;
        return F.arrayNode().add(node);
    }
}
