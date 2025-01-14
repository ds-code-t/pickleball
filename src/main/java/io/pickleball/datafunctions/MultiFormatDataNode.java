package io.pickleball.datafunctions;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jayway.jsonpath.*;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A wrapper around a Jackson JsonNode that can be created from JSON, YAML, or XML,
 * and can be converted back to any of these formats. Also integrates with JsonPath
 * to query the data.
 */
public class MultiFormatDataNode implements Map<String, Object> {

    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private static final XmlMapper XML_MAPPER = XmlMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .configure(JsonParser.Feature.IGNORE_UNDEFINED, true)
            .build();

    // The wrapped JsonNode
    private final JsonNode rootNode;


    /**
     * Constructs a MultiFormatDataNode with keys and values.
     * - Adds key-value pairs as regular properties, allowing overwriting.
     * - Additionally, creates a new array property for each unique key prefixed with '~~',
     *   retaining all associated values.
     *
     * @param keys   List of keys.
     * @param values List of values.
     */
    public MultiFormatDataNode(List<?> keys, List<?> values) {
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("Number of keys must match number of values.");
        }

        // Initialize an empty ObjectNode for this instance
        ObjectNode objectNode = JSON_MAPPER.createObjectNode();

        for (int i = 0; i < keys.size(); i++) {
            Object keyObj = keys.get(i);
            if (keyObj instanceof MultiFormatDataNode) {
                continue;
            }

            String sKey = String.valueOf(keyObj);
            Object valueObj = values.get(i);
            ObjectNode entry = JSON_MAPPER.createObjectNode();


            // Handle the array property to retain all values
            ArrayNode arrayNode = (ArrayNode) objectNode.putIfAbsent(sKey, JSON_MAPPER.createArrayNode());
            if(arrayNode == null)
                arrayNode = (ArrayNode) objectNode.get(sKey);
            arrayNode.add(entry);




            String stringKey = String.valueOf(keyObj);
            String arrayKey = "~" + stringKey; // Prefixed key for array storage

            // Initialize jsonNode to null
            JsonNode jsonNode = NullNode.instance;

            try {
                // Convert valueObj to JsonNode
                if (valueObj instanceof MultiFormatDataNode) {
                    // Directly reference the root node if value is a MultiFormatDataNode
                    jsonNode = ((MultiFormatDataNode) valueObj).getRootNode();
                } else {
                    // Convert scalar or other types to JsonNode
                    jsonNode = JSON_MAPPER.valueToTree(valueObj);
                }
            } catch (Exception e) {
                // Log the exception if necessary
                // For example: e.printStackTrace();
                // jsonNode remains as NullNode.instance
            }

            // Set the regular key (overwrites existing value if key is duplicated)
            objectNode.set(stringKey, jsonNode);


        }

        // Assign the built ObjectNode as the root node of this instance
        this.rootNode = objectNode;
    }


//    /**
//     * Constructs a MultiFormatDataNode with keys and values.
//     * - Adds key-value pairs as regular properties, allowing overwriting.
//     * - Additionally, creates a new array property for each unique key prefixed with '~~',
//     *   retaining all associated values.
//     *
//     * @param keys   List of keys.
//     * @param values List of values.
//     */
//    public MultiFormatDataNode(List<?> keys, List<?> values) {
//        if (keys.size() != values.size()) {
//            throw new IllegalArgumentException("Number of keys must match number of values.");
//        }
//
//        // Initialize an empty ObjectNode for this instance
//        ObjectNode objectNode = JSON_MAPPER.createObjectNode();
//
//        for (int i = 0; i < keys.size(); i++) {
//            Object keyObj = keys.get(i);
//            Object valueObj = values.get(i);
//
//            // Skip if key is a MultiFormatDataNode
//            if (keyObj instanceof MultiFormatDataNode) {
//                continue;
//            }
//
//            String stringKey = String.valueOf(keyObj);
//            String arrayKey = "~" + stringKey; // Prefixed key for array storage
//
//            // Initialize jsonNode to null
//            JsonNode jsonNode = NullNode.instance;
//
//            try {
//                // Convert valueObj to JsonNode
//                if (valueObj instanceof MultiFormatDataNode) {
//                    // Directly reference the root node if value is a MultiFormatDataNode
//                    jsonNode = ((MultiFormatDataNode) valueObj).getRootNode();
//                } else {
//                    // Convert scalar or other types to JsonNode
//                    jsonNode = JSON_MAPPER.valueToTree(valueObj);
//                }
//            } catch (Exception e) {
//                // Log the exception if necessary
//                // For example: e.printStackTrace();
//                // jsonNode remains as NullNode.instance
//            }
//
//            // Set the regular key (overwrites existing value if key is duplicated)
//            objectNode.set(stringKey, jsonNode);
//
//            // Handle the array property to retain all values
//            ArrayNode arrayNode;
//            JsonNode existingArrayNode = objectNode.get(arrayKey);
//            if (existingArrayNode == null || !existingArrayNode.isArray()) {
//                // Create a new ArrayNode if it doesn't exist or isn't an array
//                arrayNode = JSON_MAPPER.createArrayNode();
//                objectNode.set(arrayKey, arrayNode);
//            } else {
//                // Use the existing ArrayNode
//                arrayNode = (ArrayNode) existingArrayNode;
//            }
//
//            // Add the value to the array node
//            arrayNode.add(jsonNode);
//        }
//
//        // Assign the built ObjectNode as the root node of this instance
//        this.rootNode = objectNode;
//    }

    private MultiFormatDataNode(JsonNode rootNode) {
        this.rootNode = rootNode;
    }

    public static MultiFormatDataNode fromString(String input) {
        try {
            JsonNode json = JSON_MAPPER.readTree(input);
            return new MultiFormatDataNode(json);
        } catch (IOException ignored) {
        }
        try {
            JsonNode yaml = YAML_MAPPER.readTree(input);
            return new MultiFormatDataNode(yaml);
        } catch (IOException ignored) {
        }
        try {
            JsonNode xml = XML_MAPPER.readTree(input);
            return new MultiFormatDataNode(xml);
        } catch (IOException ignored) {
        }
        throw new IllegalArgumentException("Input data is not valid JSON, YAML, or XML.");
    }

    public MultiFormatDataNode(String path) {
        File fileOrDir = new File(path);
        if (!fileOrDir.exists()) {
            throw new IllegalArgumentException("Path does not exist: " + path);
        }
        this.rootNode = buildJsonFromPath(fileOrDir);
    }

    private static JsonNode buildJsonFromPath(File fileOrDir) {
        if (fileOrDir.isFile()) {
            return parseSingleFile(fileOrDir);
        } else if (fileOrDir.isDirectory()) {
            ObjectNode folderNode = JSON_MAPPER.createObjectNode();
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) {
                        folderNode.set(child.getName(), buildJsonFromPath(child));
                    } else {
                        JsonNode fileContents = parseSingleFile(child);
                        if (fileContents != null) {
                            String baseName = removeFileExtension(child.getName());
                            folderNode.set(baseName, fileContents);
                        }
                    }
                }
            }
            return folderNode;
        } else {
            return JSON_MAPPER.createObjectNode();
        }
    }

    private static JsonNode parseSingleFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            // Check file extension to determine format
            String fileName = file.getName().toLowerCase();

            if (fileName.endsWith(".xml")) {
                try {
                    // For XML, we need to use readValue instead of readTree
                    Object value = XML_MAPPER.readValue(content.strip(), Object.class);
                    // Convert the resulting object back to a JsonNode
                    return JSON_MAPPER.valueToTree(value);
                } catch (IOException e) {
                    System.out.println("XML parsing failed: " + e.getMessage());
                }
            }

            // Try JSON
            try {
                return JSON_MAPPER.readTree(content);
            } catch (IOException ignored) {
            }
            // Try YAML
            try {
                return YAML_MAPPER.readTree(content);
            } catch (IOException ignored) {
            }
            return null;
        } catch (IOException e) {
            System.out.println("File reading failed: " + e.getMessage());
            return null;
        }
    }

    private static void debugPrintStructure(JsonNode node, String prefix) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                System.out.println(prefix + entry.getKey() + ":");
                debugPrintStructure(entry.getValue(), prefix + "  ");
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                System.out.println(prefix + "[" + i + "]:");
                debugPrintStructure(node.get(i), prefix + "  ");
            }
        } else {
            System.out.println(prefix + node.toString());
        }
    }


    /**
     * Helper to remove the trailing file extension, if any.
     * e.g. "app.yaml" -> "app"
     */
    private static String removeFileExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            return fileName.substring(0, dotIdx);
        }
        return fileName;
    }

    // ------------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------------

    /**
     * Converts the wrapped data to JSON format (as a String).
     */
    public String asJson() {
        try {
            return JSON_MAPPER.writeValueAsString(rootNode);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize as JSON", e);
        }
    }

    /**
     * Converts the wrapped data to YAML format (as a String).
     */
    public String asYaml() {
        try {
            return YAML_MAPPER.writeValueAsString(rootNode);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize as YAML", e);
        }
    }

    /**
     * Converts the wrapped data to XML format (as a String).
     */
    public String asXml() {
        try {
            return XML_MAPPER.writeValueAsString(rootNode);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize as XML", e);
        }
    }

    // ------------------------------------------------------------------------
    // JsonPath queries
    // ------------------------------------------------------------------------

    /**
     * Returns the first match for the given JsonPath expression, or null if none is found.
     * Uses ALWAYS_RETURN_LIST so single-match scalars don't break Jackson mapping.
     */
    public Object getFirstByPath(String jsonPath) {
        Configuration jacksonConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                // Force results to always be in a list
                .options(Option.ALWAYS_RETURN_LIST)
                .build();

        ReadContext ctx = JsonPath.using(jacksonConfig).parse(rootNode.toString());
        List<Object> results;
        try {
            results = ctx.read(jsonPath);
        } catch (PathNotFoundException e) {
            return null;
        }
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Returns all matched items for the given JsonPath expression as a List<Object>.
     * If none are found, returns an empty list. Also uses ALWAYS_RETURN_LIST to be safe.
     */
    public List<Object> getAllByPath(String jsonPath) {
        Configuration jacksonConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                .options(Option.ALWAYS_RETURN_LIST)
                .build();

        ReadContext ctx = JsonPath.using(jacksonConfig).parse(rootNode.toString());
        try {
            return ctx.read(jsonPath);
        } catch (PathNotFoundException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Returns all matched items as a Map of path => value.
     * The key is the full JSON path for each matched element,
     * and the value is the actual Java object (e.g. a Double for prices).
     */
    public Map<String, Object> getAllByPathAsMap(String jsonPath) {
        // 1) Normal config for reading actual values
        Configuration normalConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                // We'll skip ALWAYS_RETURN_LIST here because we want the path list to match
                .build();

        // parse once to get the actual objects
        ReadContext normalCtx = JsonPath.using(normalConfig).parse(rootNode.toString());
        List<Object> objectResults;
        try {
            objectResults = normalCtx.read(jsonPath, new TypeRef<List<Object>>() {
            });
        } catch (PathNotFoundException e) {
            return Collections.emptyMap();
        }

        // 2) Path-list config to get the matched JSON paths
        Configuration pathListConfig = Configuration.builder()
                .jsonProvider(new JacksonJsonProvider())
                .mappingProvider(new JacksonMappingProvider())
                .options(Option.AS_PATH_LIST, Option.SUPPRESS_EXCEPTIONS)
                .build();

        // parse again to get the *paths*
        ReadContext pathCtx = JsonPath.using(pathListConfig).parse(rootNode.toString());
        List<String> matchedPaths;
        try {
            matchedPaths = pathCtx.read(jsonPath, new TypeRef<List<String>>() {
            });
        } catch (PathNotFoundException e) {
            return Collections.emptyMap();
        }

        // 3) Zip the two lists together (path => object)
        Map<String, Object> resultMap = new LinkedHashMap<>();
        int size = Math.min(matchedPaths.size(), objectResults.size());
        for (int i = 0; i < size; i++) {
            String path = matchedPaths.get(i);
            Object value = objectResults.get(i);
            resultMap.put(path, value);
        }
        return resultMap;
    }

    /**
     * Get the underlying JsonNode for any advanced usage.
     */
    public JsonNode getRootNode() {
        return rootNode;
    }

    // ------------------------------------------------------------------------
    // Implementation of Map<String, Object>
    // (exposing the wrapped JsonNode as if it were a Map)
    // ------------------------------------------------------------------------

    private boolean isObjectNode() {
        return rootNode != null && rootNode.isObject();
    }

    @Override
    public int size() {
        return isObjectNode() ? rootNode.size() : 0;
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        if (!isObjectNode()) return false;
        if (!(key instanceof String)) return false;
        return rootNode.has((String) key);
    }

    @Override
    public boolean containsValue(Object value) {
        if (!isObjectNode()) return false;
        for (JsonNode child : rootNode) {
            Object childValue = JSON_MAPPER.convertValue(child, Object.class);
            if (Objects.equals(childValue, value)) {
                return true;
            }
        }
        return false;
    }


    @Override
    public Object get(Object key) {
        if (!isObjectNode()) return null;
        if (!(key instanceof String)) return null;
        return getFirstByPath("$." + key);
    }

    @Override
    public Object put(String key, Object value) {
        if (!isObjectNode()) {
            // If rootNode is not an object, do nothing or throw exception
            return null;
        }
        JsonNode oldNode = ((ObjectNode) rootNode).get(key);
        Object oldValue = oldNode == null ? null : JSON_MAPPER.convertValue(oldNode, Object.class);
        JsonNode newValueNode = JSON_MAPPER.valueToTree(value);
        ((ObjectNode) rootNode).set(key, newValueNode);
        return oldValue;
    }

    @Override
    public Object remove(Object key) {
        if (!isObjectNode()) return null;
        if (!(key instanceof String)) return null;
        JsonNode removed = ((ObjectNode) rootNode).remove((String) key);
        return (removed == null) ? null : JSON_MAPPER.convertValue(removed, Object.class);
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {
        if (!isObjectNode()) return;
        for (Entry<? extends String, ?> entry : m.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public void clear() {
        if (!isObjectNode()) return;
        Iterator<String> fieldNames = rootNode.fieldNames();
        List<String> toRemove = new ArrayList<>();
        while (fieldNames.hasNext()) {
            toRemove.add(fieldNames.next());
        }
        for (String fieldName : toRemove) {
            ((ObjectNode) rootNode).remove(fieldName);
        }
    }

    @Override
    public Set<String> keySet() {
        if (!isObjectNode()) return Collections.emptySet();
        Iterator<String> iter = rootNode.fieldNames();
        Set<String> keys = new LinkedHashSet<>();
        while (iter.hasNext()) {
            keys.add(iter.next());
        }
        return keys;
    }

    @Override
    public Collection<Object> values() {
        if (!isObjectNode()) return Collections.emptyList();
        List<Object> vals = new ArrayList<>();
        for (JsonNode child : rootNode) {
            vals.add(JSON_MAPPER.convertValue(child, Object.class));
        }
        return vals;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        if (!isObjectNode()) return Collections.emptySet();
        Set<Entry<String, Object>> entries = new LinkedHashSet<>();
        for (String fieldName : keySet()) {
            entries.add(new AbstractMap.SimpleEntry<>(
                    fieldName,
                    JSON_MAPPER.convertValue(rootNode.get(fieldName), Object.class)
            ));
        }
        return entries;
    }


    public static void main(String[] args) {
        String configsPath = "src/main/resources/configs";
        MultiFormatDataNode node2 = new MultiFormatDataNode(configsPath);

        // Print the full structure to debug
        System.out.println("Full JSON structure:");
        System.out.println(node2.asJson());

        // Add this to your main method to debug:
        System.out.println("Detailed structure:");
        debugPrintStructure(node2.getRootNode(), "");
        ;

        Object nestedObj = node2.get("nested");
        if (nestedObj instanceof Map) {
            Map<?, ?> nestedMap = (Map<?, ?>) nestedObj;
            Object networkObj = nestedMap.get("network");
            System.out.println("Network object class: " + networkObj.getClass());
            System.out.println("Network object content: " + networkObj);

            if (networkObj instanceof Map) {
                Map<?, ?> networkMap = (Map<?, ?>) networkObj;
                System.out.println("Interface value: " + networkMap.get("interface"));
                System.out.println("Enabled value: " + networkMap.get("enabled"));
            }
        }


        String jsonData = """
                  {
                    "store": {
                      "book": [
                        { "category": "fiction", "price": 8.95 },
                        { "category": "tech",    "price": 29.99 }
                      ],
                        "zz": [
                        { "category": "fiction", "price": 9991.11 },
                        { "category": "tech",    "price": 99911.11 }
                      ]
                      ,
                      "Q":    { "www": [
                        { "category": "fiction", "price": 55555.55 },
                        { "category": "tech",    "price": 5.55 }
                      ]
                      }
                    }
                  }
                """;

        // Parse from JSON
        MultiFormatDataNode multiNode = MultiFormatDataNode.fromString(jsonData);

        // 1) Access data using JsonPath (first match)
        Object firstMatch = multiNode.getFirstByPath("$.store.book[?(@.price < 10)]");
        System.out.println("First match: " + firstMatch);

        // 2) Convert to YAML
        String yaml = multiNode.asYaml();
        System.out.println("As YAML:\n" + yaml);

        // 3) Convert to XML
        String xml = multiNode.asXml();
        System.out.println("As XML:\n" + xml);

        // 4) Retrieve all matches as a List<Object>
        List<Object> allMatches = multiNode.getAllByPath("$.store.book[*]");
        System.out.println("All books: " + allMatches);
        System.out.println("All prices map: " + multiNode.getAllByPathAsMap("$..price"));

        // 5) Retrieve path-value map
        Map<String, Object> pathValueMap = multiNode.getAllByPathAsMap("$.store.book[*].price");
        System.out.println("Path-value map: " + pathValueMap);


    }

}