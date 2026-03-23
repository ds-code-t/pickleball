package tools.dscode.coredefinitions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.cucumber.java.DocStringType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("unused")
public class DocStringDefinitions {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder().build();
    private static final XmlMapper XML_MAPPER = XmlMapper.builder().build();

    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<ArrayList<Object>> LIST_TYPE = new TypeReference<>() {};

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
    public List<Object> jsonList(String docString) throws JsonProcessingException {
        return toList(parseJson(docString), "json");
    }

    @DocStringType(contentType = "json")
    public Set<Object> jsonSet(String docString) throws JsonProcessingException {
        return toSet(parseJson(docString), "json");
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
    public List<Object> yamlList(String docString) throws JsonProcessingException {
        return toList(parseYaml(docString), "yaml");
    }

    @DocStringType(contentType = "yaml")
    public Set<Object> yamlSet(String docString) throws JsonProcessingException {
        return toSet(parseYaml(docString), "yaml");
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
    public List<Object> xmlList(String docString) throws JsonProcessingException {
        return toList(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Set<Object> xmlSet(String docString) throws JsonProcessingException {
        return toSet(parseXml(docString), "xml");
    }

    @DocStringType(contentType = "xml")
    public Object xmlObject(String docString) throws JsonProcessingException {
        return toJavaObject(parseXml(docString));
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

    private static List<Object> toList(JsonNode node, String contentType) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(
                    "DocString contentType '" + contentType + "' must parse to an array node to be converted to List, but got: "
                            + describeNode(node));
        }
        return JSON_MAPPER.convertValue(node, LIST_TYPE);
    }

    private static Set<Object> toSet(JsonNode node, String contentType) {
        return new LinkedHashSet<>(toList(node, contentType));
    }

    private static Object toJavaObject(JsonNode node) throws JsonProcessingException {
        return JSON_MAPPER.treeToValue(node, Object.class);
    }

    private static String describeNode(JsonNode node) {
        return node == null ? "null" : node.getNodeType().name();
    }
}