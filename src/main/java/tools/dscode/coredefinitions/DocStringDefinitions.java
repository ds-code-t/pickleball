package tools.dscode.coredefinitions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.cucumber.docstring.DocString;
import io.cucumber.java.DocStringType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.cucumber.core.runner.CurrentScenarioState.getScenarioObject;

@SuppressWarnings("unused")
public class DocStringDefinitions {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder().build();
    private static final XmlMapper XML_MAPPER = XmlMapper.builder().build();

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<ArrayList<Object>> LIST_TYPE = new TypeReference<>() {};



    @DocStringType(contentType = "registry")
    public Object registryObject(String pathKey) {
        String key = pathKey == null ? null : pathKey.trim();
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Registry docstring pathKey cannot be null or blank");
        }

        Object value = getScenarioObject(key);
        if (value == null) {
            throw new RuntimeException("No scenario object found for registry pathKey '" + key + "'");
        }

        return value;
    }

    @DocStringType(contentType = "json")
    public ObjectNode jsonObjectNode(String docString) throws JsonProcessingException {
        return toObjectNode(JSON_MAPPER.readTree(docString), "json");
    }

    @DocStringType(contentType = "yaml")
    public ObjectNode yamlObjectNode(String docString) throws JsonProcessingException {
        return toObjectNode(YAML_MAPPER.readTree(docString), "yaml");
    }

    @DocStringType(contentType = "xml")
    public ObjectNode xmlObjectNode(String docString) throws JsonProcessingException {
        return toObjectNode(XML_MAPPER.readTree(docString), "xml");
    }

    private static ObjectNode toObjectNode(JsonNode node, String contentType) {
        if (!(node instanceof ObjectNode objectNode)) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType
                            + "' must parse to an object node to be converted to ObjectNode, but got: "
                            + describeNode(node)
            );
        }
        return objectNode;
    }

    // =========================================================
    // JSON
    // =========================================================

    @DocStringType(contentType = "json")
    public JsonNode jsonNode(String docString) throws JsonProcessingException {
        return parseJson(docString);
    }

    @DocStringType(contentType = "json")
    public Map<String, Object> jsonMap(String docString) throws JsonProcessingException {
        return toMap(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public Map<String, String> jsonStringMap(String docString) throws JsonProcessingException {
        return toStringMap(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public Map<String, Integer> jsonIntegerMap(String docString) throws JsonProcessingException {
        return toIntegerMap(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public List<Object> jsonList(String docString) throws JsonProcessingException {
        return toList(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public List<String> jsonStringList(String docString) throws JsonProcessingException {
        return toStringList(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public List<Integer> jsonIntegerList(String docString) throws JsonProcessingException {
        return toIntegerList(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public Set<Object> jsonSet(String docString) throws JsonProcessingException {
        return toSet(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public Set<String> jsonStringSet(String docString) throws JsonProcessingException {
        return toStringSet(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public Set<Integer> jsonIntegerSet(String docString) throws JsonProcessingException {
        return toIntegerSet(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public Object jsonObject(String docString) throws JsonProcessingException {
        return toJavaObject(parseJson(docString));
    }

    // =========================================================
    // YAML
    // =========================================================

    @DocStringType(contentType = "yaml")
    public JsonNode yamlNode(String docString) throws JsonProcessingException {
        return parseYaml(docString);
    }

    @DocStringType(contentType = "yaml")
    public Map<String, Object> yamlMap(String docString) throws JsonProcessingException {
        return toMap(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public Map<String, String> yamlStringMap(String docString) throws JsonProcessingException {
        return toStringMap(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public Map<String, Integer> yamlIntegerMap(String docString) throws JsonProcessingException {
        return toIntegerMap(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public List<Object> yamlList(String docString) throws JsonProcessingException {
        return toList(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public List<String> yamlStringList(String docString) throws JsonProcessingException {
        return toStringList(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public List<Integer> yamlIntegerList(String docString) throws JsonProcessingException {
        return toIntegerList(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public Set<Object> yamlSet(String docString) throws JsonProcessingException {
        return toSet(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public Set<String> yamlStringSet(String docString) throws JsonProcessingException {
        return toStringSet(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public Set<Integer> yamlIntegerSet(String docString) throws JsonProcessingException {
        return toIntegerSet(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public Object yamlObject(String docString) throws JsonProcessingException {
        return toJavaObject(parseYaml(docString));
    }

    // =========================================================
    // XML
    // =========================================================

    @DocStringType(contentType = "xml")
    public JsonNode xmlNode(String docString) throws JsonProcessingException {
        return parseXml(docString);
    }

    @DocStringType(contentType = "xml")
    public Map<String, Object> xmlMap(String docString) throws JsonProcessingException {
        return toMap(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Map<String, String> xmlStringMap(String docString) throws JsonProcessingException {
        return toStringMap(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Map<String, Integer> xmlIntegerMap(String docString) throws JsonProcessingException {
        return toIntegerMap(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public List<Object> xmlList(String docString) throws JsonProcessingException {
        return toList(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public List<String> xmlStringList(String docString) throws JsonProcessingException {
        return toStringList(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public List<Integer> xmlIntegerList(String docString) throws JsonProcessingException {
        return toIntegerList(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Set<Object> xmlSet(String docString) throws JsonProcessingException {
        return toSet(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Set<String> xmlStringSet(String docString) throws JsonProcessingException {
        return toStringSet(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Set<Integer> xmlIntegerSet(String docString) throws JsonProcessingException {
        return toIntegerSet(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Object xmlObject(String docString) throws JsonProcessingException {
        return toJavaObject(parseXml(docString));
    }

    public static JsonNode docStringtoJsonNode(DocString docString) throws JsonProcessingException {
        if (docString == null) {
            throw new IllegalArgumentException("DocString cannot be null");
        }

        String contentType = docString.getContentType();
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("DocString contentType is required to convert to JsonNode");
        }

        String content = docString.getContent();
        return switch (contentType.trim().toLowerCase()) {
            case "json" -> parseJson(content);
            case "yaml" -> parseYaml(content);
            case "xml" -> parseXml(content);
            default -> throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' cannot be converted to JsonNode; expected json, yaml, or xml"
            );
        };
    }

    // =========================================================
    // Parsing helpers
    // =========================================================

    private static JsonNode parseJson(String docString) throws JsonProcessingException {
        return JSON_MAPPER.readTree(docString);
    }

    private static JsonNode parseYaml(String docString) throws JsonProcessingException {
        return YAML_MAPPER.readTree(docString);
    }

    private static JsonNode parseXml(String docString) throws JsonProcessingException {
        return XML_MAPPER.readTree(docString);
    }

    // =========================================================
    // Conversion helpers
    // =========================================================

    private static Map<String, Object> toMap(JsonNode node, String contentType) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' must parse to an object node to be converted to Map, but got: "
                            + describeNode(node));
        }
        return JSON_MAPPER.convertValue(node, MAP_TYPE);
    }

    private static Map<String, String> toStringMap(JsonNode node, String contentType) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' must parse to an object node to be converted to Map, but got: "
                            + describeNode(node));
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            result.put(entry.getKey(), toStringValue(entry.getValue(), contentType, "map value for key '" + entry.getKey() + "'"));
        }
        return result;
    }

    private static Map<String, Integer> toIntegerMap(JsonNode node, String contentType) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' must parse to an object node to be converted to Map, but got: "
                            + describeNode(node));
        }

        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            result.put(entry.getKey(), toIntegerValue(entry.getValue(), contentType, "map value for key '" + entry.getKey() + "'"));
        }
        return result;
    }

    private static List<Object> toList(JsonNode node, String contentType) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' must parse to an array node to be converted to List, but got: "
                            + describeNode(node));
        }
        return JSON_MAPPER.convertValue(node, LIST_TYPE);
    }

    private static List<String> toStringList(JsonNode node, String contentType) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' must parse to an array node to be converted to List, but got: "
                            + describeNode(node));
        }

        List<String> result = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            result.add(toStringValue(node.get(i), contentType, "list value at index " + i));
        }
        return result;
    }

    private static List<Integer> toIntegerList(JsonNode node, String contentType) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' must parse to an array node to be converted to List, but got: "
                            + describeNode(node));
        }

        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            result.add(toIntegerValue(node.get(i), contentType, "list value at index " + i));
        }
        return result;
    }

    private static Set<Object> toSet(JsonNode node, String contentType) {
        return new LinkedHashSet<>(toList(node, contentType));
    }

    private static Set<String> toStringSet(JsonNode node, String contentType) {
        return new LinkedHashSet<>(toStringList(node, contentType));
    }

    private static Set<Integer> toIntegerSet(JsonNode node, String contentType) {
        return new LinkedHashSet<>(toIntegerList(node, contentType));
    }

    private static Object toJavaObject(JsonNode node) throws JsonProcessingException {
        return JSON_MAPPER.treeToValue(node, Object.class);
    }

    private static String toStringValue(JsonNode node, String contentType, String location) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isContainerNode()) {
            throw scalarConversionException(contentType, location, "String", node);
        }
        return node.asText();
    }

    private static Integer toIntegerValue(JsonNode node, String contentType, String location) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            try {
                return Integer.valueOf(node.asText().trim());
            } catch (NumberFormatException ignored) {
                throw scalarConversionException(contentType, location, "Integer", node);
            }
        }
        throw scalarConversionException(contentType, location, "Integer", node);
    }

    private static IllegalArgumentException scalarConversionException(
            String contentType,
            String location,
            String targetType,
            JsonNode node
    ) {
        return new IllegalArgumentException(
                "DocString contentType '" + contentType + "' " + location
                        + " cannot be converted to " + targetType + ", got: " + describeNode(node)
        );
    }

    private static String describeNode(JsonNode node) {
        return node == null ? "null" : node.getNodeType().name();
    }
}
