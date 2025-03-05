//package io.pickleball.datafunctions;
//
//import com.fasterxml.jackson.core.JsonParser;
//import com.fasterxml.jackson.databind.*;
//import com.fasterxml.jackson.databind.json.JsonMapper;
//import com.fasterxml.jackson.databind.node.ArrayNode;
//import com.fasterxml.jackson.databind.node.ObjectNode;
//import com.fasterxml.jackson.dataformat.xml.XmlMapper;
//import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
//
//import java.io.File;
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.util.*;
//
//
///**
// * A wrapper around a Jackson JsonNode that can be created from JSON, YAML, or XML,
// * and can be converted back to any of these formats. Uses JsonPathFunctions for querying.
// */
//public class MultiFormatDataNode2 implements Map<String, Object> {
////public class MultiFormatDataNode extends HashMap<String, Object> {
//
//
//    private static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
//            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
//            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
//            .build();
//
//    private static final ObjectMapper YAML_MAPPER = YAMLMapper.builder()
//            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
//            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
//            .build();
//
//    private static final XmlMapper XML_MAPPER = XmlMapper.builder()
//            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
//            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
//            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
//            .configure(JsonParser.Feature.IGNORE_UNDEFINED, true)
//            .build();
//
//    private final ObjectNode rootNode;
//
////    private Map<?,?> altResolutionMap;
////
////    public void setAltResolutionMap(Map<?, ?> altResolutionMap) {
////        this.altResolutionMap = altResolutionMap;
////    }
//
//    public MultiFormatDataNode2() {
//        this.rootNode = JSON_MAPPER.createObjectNode();
//    }
//
//    public MultiFormatDataNode2(List<?> keys, List<?> values) {
//        if (keys.size() != values.size()) {
//            throw new IllegalArgumentException("Number of keys must match number of values.");
//        }
//        this.rootNode = JSON_MAPPER.createObjectNode();
//        for (int i = 0; i < keys.size(); i++) {
//            Object keyObj = keys.get(i);
//            if (keyObj instanceof MultiFormatDataNode2) {
//                continue;
//            }
//            put(String.valueOf(keyObj), values.get(i));
//        }
//    }
//
//
//    private MultiFormatDataNode2(ObjectNode rootNode) {
//        this.rootNode = rootNode;
//    }
//
//    public static MultiFormatDataNode2 fromString(String input) {
//        try {
//            ObjectNode json = (ObjectNode) JSON_MAPPER.readTree(input);
//            return new MultiFormatDataNode2(json);
//        } catch (IOException ignored) {
//        }
//        try {
//            ObjectNode yaml = (ObjectNode) YAML_MAPPER.readTree(input);
//            return new MultiFormatDataNode2(yaml);
//        } catch (IOException ignored) {
//        }
//        try {
//            ObjectNode xml = (ObjectNode) XML_MAPPER.readTree(input);
//            return new MultiFormatDataNode2(xml);
//        } catch (IOException ignored) {
//        }
//        throw new IllegalArgumentException("Input data is not valid JSON, YAML, or XML.");
//    }
//
//    public MultiFormatDataNode2(String path) {
//        File fileOrDir = new File(path);
//        if (!fileOrDir.exists()) {
//            throw new IllegalArgumentException("Path does not exist: " + path);
//        }
//        this.rootNode = (ObjectNode) buildJsonFromPath(fileOrDir);
//    }
//
//    private static JsonNode buildJsonFromPath(File fileOrDir) {
//        if (fileOrDir.isFile()) {
//            return parseSingleFile(fileOrDir);
//        } else if (fileOrDir.isDirectory()) {
//            ObjectNode folderNode = JSON_MAPPER.createObjectNode();
//            File[] children = fileOrDir.listFiles();
//            if (children != null) {
//                for (File child : children) {
//                    if (child.isDirectory()) {
//                        folderNode.set(child.getName(), buildJsonFromPath(child));
//                    } else {
//                        JsonNode fileContents = parseSingleFile(child);
//                        if (fileContents != null) {
//                            String baseName = removeFileExtension(child.getName());
//                            folderNode.set(baseName, fileContents);
//                        }
//                    }
//                }
//            }
//            return folderNode;
//        } else {
//            return JSON_MAPPER.createObjectNode();
//        }
//    }
//
//    private static JsonNode parseSingleFile(File file) {
//        if (!file.isFile()) {
//            return null;
//        }
//        try {
//            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
//            String fileName = file.getName().toLowerCase();
//
//            if (fileName.endsWith(".xml")) {
//                try {
//                    Object value = XML_MAPPER.readValue(content.strip(), Object.class);
//                    return JSON_MAPPER.valueToTree(value);
//                } catch (IOException e) {
//                    System.out.println("XML parsing failed: " + e.getMessage());
//                }
//            }
//
//            try {
//                return JSON_MAPPER.readTree(content);
//            } catch (IOException ignored) {
//            }
//            try {
//                return YAML_MAPPER.readTree(content);
//            } catch (IOException ignored) {
//            }
//            return null;
//        } catch (IOException e) {
//            System.out.println("File reading failed: " + e.getMessage());
//            return null;
//        }
//    }
//
//    private static String removeFileExtension(String fileName) {
//        int dotIdx = fileName.lastIndexOf('.');
//        if (dotIdx > 0) {
//            return fileName.substring(0, dotIdx);
//        }
//        return fileName;
//    }
//
//    public String asJson() {
//        try {
//            return JSON_MAPPER.writeValueAsString(rootNode);
//        } catch (IOException e) {
//            throw new RuntimeException("Failed to serialize as JSON", e);
//        }
//    }
//
//    public String asYaml() {
//        try {
//            return YAML_MAPPER.writeValueAsString(rootNode);
//        } catch (IOException e) {
//            throw new RuntimeException("Failed to serialize as YAML", e);
//        }
//    }
//
//    public String asXml() {
//        try {
//            return XML_MAPPER.writeValueAsString(rootNode);
//        } catch (IOException e) {
//            throw new RuntimeException("Failed to serialize as XML", e);
//        }
//    }
//
//    public Object getFirstByPath(String jsonPath) {
//        if (!(rootNode instanceof ObjectNode)) {
//            return null;
//        }
//        Map<String, Object> results = JsonPathFunctions.processAndCompare((ObjectNode) rootNode, jsonPath);
//        if (results.isEmpty()) {
//            return null;
//        }
//        Object value = results.values().iterator().next();
//        return JSON_MAPPER.convertValue(value, Object.class);
//    }
//
//    public Object getLastByPath(String jsonPath) {
//        if (!(rootNode instanceof ObjectNode)) {
//            return null;
//        }
//        Map<String, Object> results = JsonPathFunctions.processAndCompare((ObjectNode) rootNode, jsonPath);
//        if (results.isEmpty()) {
//            return null;
//        }
//        List<Object> values = new ArrayList<>(results.values());
//        Object value = values.get(values.size() - 1);
//        return JSON_MAPPER.convertValue(value, Object.class);
//    }
//
//    public List<Object> getAllByPath(String jsonPath) {
//        if (!(rootNode instanceof ObjectNode)) {
//            return Collections.emptyList();
//        }
//        Map<String, Object> results = JsonPathFunctions.processAndCompare((ObjectNode) rootNode, jsonPath);
//        // Return just the values from the map
//        return new ArrayList<>(results.values());
//    }
//
//    public Map<String, Object> getAllByPathAsMap(String jsonPath) {
//        if (!(rootNode instanceof ObjectNode)) {
//            return Collections.emptyMap();
//        }
//        // Return the map directly from processAndCompare
//        return JsonPathFunctions.processAndCompare((ObjectNode) rootNode, jsonPath);
//    }
//
//    public JsonNode getRootNode() {
//        return rootNode;
//    }
//
//    private boolean isObjectNode() {
//        return rootNode != null && rootNode.isObject();
//    }
//
//    @Override
//    public int size() {
//        return isObjectNode() ? rootNode.size() : 0;
//    }
//
//    @Override
//    public boolean isEmpty() {
//        return size() == 0;
//    }
//
//    @Override
//    public boolean containsKey(Object key) {
//        if (!isObjectNode()) return false;
//        if (!(key instanceof String)) return false;
//        return rootNode.has((String) key);
//    }
//
//    @Override
//    public boolean containsValue(Object value) {
//        if (!isObjectNode()) return false;
//        for (JsonNode child : rootNode) {
//            Object childValue = JSON_MAPPER.convertValue(child, Object.class);
//            if (Objects.equals(childValue, value)) {
//                return true;
//            }
//        }
//        return false;
//    }
//
//    @Override
//    public Object get(Object key) {
//        if (!isObjectNode()) return null;
//        return getFirstByPath("$." + String.valueOf(key));
//    }
//
//    @Override
//    public Object put(String key, Object value) {
//        Object oldValue = null;
//        ArrayNode arrayNode = (ArrayNode) rootNode.putIfAbsent(key, JSON_MAPPER.createArrayNode());
//        if (arrayNode == null)
//            arrayNode = (ArrayNode) rootNode.get(key);
//        else
//            oldValue = arrayNode.get(arrayNode.size()-1);
//        try {
//            JsonNode node = JSON_MAPPER.valueToTree(value);
//            arrayNode.add(node);
//        } catch (Exception e) {
//            // Fallback when value cannot be converted.
////            arrayNode.add((JsonNode) null);
//            arrayNode.add("<" + key + " #" +(arrayNode.size() + 1)+">");
//        }
//        return oldValue;
//    }
//
//    @Override
//    public Object remove(Object key) {
//        if (!isObjectNode()) return null;
//        if (!(key instanceof String)) return null;
//        JsonNode removed = ((ObjectNode) rootNode).remove((String) key);
//        return (removed == null) ? null : JSON_MAPPER.convertValue(removed, Object.class);
//    }
//
//    @Override
//    public void putAll(Map<? extends String, ?> m) {
//        if (!isObjectNode()) return;
//        for (Entry<? extends String, ?> entry : m.entrySet()) {
//            put(entry.getKey(), entry.getValue());
//        }
//    }
//
//    @Override
//    public void clear() {
//        if (!isObjectNode()) return;
//        Iterator<String> fieldNames = rootNode.fieldNames();
//        List<String> toRemove = new ArrayList<>();
//        while (fieldNames.hasNext()) {
//            toRemove.add(fieldNames.next());
//        }
//        for (String fieldName : toRemove) {
//            ((ObjectNode) rootNode).remove(fieldName);
//        }
//    }
//
//    @Override
//    public Set<String> keySet() {
//        if (!isObjectNode()) return Collections.emptySet();
//        Iterator<String> iter = rootNode.fieldNames();
//        Set<String> keys = new LinkedHashSet<>();
//        while (iter.hasNext()) {
//            keys.add(iter.next());
//        }
//        return keys;
//    }
//
//    @Override
//    public Collection<Object> values() {
//        if (!isObjectNode()) return Collections.emptyList();
//        List<Object> vals = new ArrayList<>();
//        for (JsonNode child : rootNode) {
//            vals.add(JSON_MAPPER.convertValue(child, Object.class));
//        }
//        return vals;
//    }
//
//    @Override
//    public Set<Entry<String, Object>> entrySet() {
//        if (!isObjectNode()) return Collections.emptySet();
//        Set<Entry<String, Object>> entries = new LinkedHashSet<>();
//        for (String fieldName : keySet()) {
//            entries.add(new AbstractMap.SimpleEntry<>(
//                    fieldName,
//                    JSON_MAPPER.convertValue(rootNode.get(fieldName), Object.class)
//            ));
//        }
//        return entries;
//    }
//
//    public static void main(String[] args) {
//        // Test various scenarios to ensure functionality remains intact
//        testBasicJsonPathOperations();
//        testFileOperations();
//        testFormatConversions();
//        testMapInterface();
//        testComplexQueries();
//    }
//
//    private static void testBasicJsonPathOperations() {
//        System.out.println("\n=== Testing Basic JsonPath Operations ===");
//        String jsonData = """
//                {
//                    "store": {
//                        "book": [
//                            { "category": "fiction", "price": 8.95 },
//                            { "category": "tech", "price": 29.99 }
//                        ]
//                    }
//                }
//                """;
//
//        MultiFormatDataNode2 node = MultiFormatDataNode2.fromString(jsonData);
//
//        // Test getFirstByPath
//        System.out.println("\nTesting getFirstByPath:");
//        Object firstMatch = node.getFirstByPath("$.store.book[?(@.price < 10)]");
//        System.out.println("Expected: Book with price < 10");
//        System.out.println("Actual: " + firstMatch);
//        assert firstMatch != null : "First match should not be null";
//
//        // Test getAllByPath
//        System.out.println("\nTesting getAllByPath:");
//        List<Object> allMatches = node.getAllByPath("$.store.book[*]");
//        System.out.println("Expected size: 2");
//        System.out.println("Actual size: " + allMatches.size());
//        System.out.println("Actual content: " + allMatches);
//        assert allMatches.size() == 2 : "Should find 2 books";
//
//        // Test getAllByPathAsMap
//        System.out.println("\nTesting getAllByPathAsMap:");
//        Map<String, Object> pathValueMap = node.getAllByPathAsMap("$.store.book[*].price");
//        System.out.println("Expected size: 2");
//        System.out.println("Actual size: " + pathValueMap.size());
//        System.out.println("Actual content: " + pathValueMap);
//        assert pathValueMap.size() == 2 : "Should find 2 prices";
//
//        System.out.println("\nBasic JsonPath operations tests passed");
//    }
//
//    private static void testFileOperations() {
//        System.out.println("\n=== Testing File Operations ===");
//        try {
//            // Create and write to temp file
//            File tempFile = File.createTempFile("test", ".json");
//            String testContent = "{\"test\": \"value\", \"number\": 42}";
//            Files.write(tempFile.toPath(), testContent.getBytes());
//
//            System.out.println("\nTesting file reading:");
//            System.out.println("Input file content: " + testContent);
//
//            MultiFormatDataNode2 node = new MultiFormatDataNode2(tempFile.getAbsolutePath());
//
//            // Test string value
//            System.out.println("\nTesting string value reading:");
//            System.out.println("Expected: value");
//            System.out.println("Actual: " + node.get("test"));
//            assert node.get("test").equals("value") : "String value reading failed";
//
//            // Test number value
//            System.out.println("\nTesting number value reading:");
//            System.out.println("Expected: 42");
//            System.out.println("Actual: " + node.get("number"));
//            assert node.get("number").equals(42) : "Number value reading failed";
//
//            tempFile.delete();
//            System.out.println("\nFile operations tests passed");
//        } catch (IOException e) {
//            System.err.println("File operations tests failed: " + e.getMessage());
//        }
//    }
//
//    private static void testFormatConversions() {
//        System.out.println("\n=== Testing Format Conversions ===");
//        String jsonData = "{\"test\": \"value\", \"nested\": {\"key\": \"nestedValue\"}}";
//        System.out.println("\nInput JSON:");
//        System.out.println(jsonData);
//
//        MultiFormatDataNode2 node = MultiFormatDataNode2.fromString(jsonData);
//
//        // Test JSON conversion
//        System.out.println("\nTesting JSON conversion:");
//        String json = node.asJson();
//        System.out.println("Expected to contain: \"test\" and \"nested\"");
//        System.out.println("Actual JSON output:");
//        System.out.println(json);
//        assert json.contains("\"test\"") && json.contains("\"nested\"") : "JSON conversion failed";
//
//        // Test YAML conversion
//        System.out.println("\nTesting YAML conversion:");
//        String yaml = node.asYaml();
//        System.out.println("Expected to contain: 'test:' and 'nested:'");
//        System.out.println("Actual YAML output:");
//        System.out.println(yaml);
//        assert yaml.contains("test:") && yaml.contains("nested:") : "YAML conversion failed";
//
//        // Test XML conversion
//        System.out.println("\nTesting XML conversion:");
//        String xml = node.asXml();
//        System.out.println("Expected to contain: <test> and <nested>");
//        System.out.println("Actual XML output:");
//        System.out.println(xml);
//        assert xml.contains("<test>") && xml.contains("<nested>") : "XML conversion failed";
//
//        System.out.println("\nFormat conversion tests passed");
//    }
//
//    private static void testMapInterface() {
//        System.out.println("\n=== Testing Map Interface ===");
//        String initialJson = """
//                {
//                    "key1": "value1",
//                    "key2": "value2",
//                    "nested": {
//                        "inner": "value3"
//                    },
//                    "array": [1, 2, 3]
//                }""";
//        System.out.println("\nInitial JSON:");
//        System.out.println(initialJson);
//
//        MultiFormatDataNode2 node = MultiFormatDataNode2.fromString(initialJson);
//
//        // Test size
//        System.out.println("\nTesting size:");
//        System.out.println("Expected: 4");
//        System.out.println("Actual: " + node.size());
//        assert node.size() == 4 : "Size should be 4";
//
//        // Test containsKey
//        System.out.println("\nTesting containsKey:");
//        System.out.println("Expected key1 exists: true");
//        System.out.println("Actual: " + node.containsKey("key1"));
//        assert node.containsKey("key1") : "Should contain key1";
//
//        // Test containsValue
//        System.out.println("\nTesting containsValue:");
//        System.out.println("Expected contains 'value1': true");
//        System.out.println("Actual: " + node.containsValue("value1"));
//        assert node.containsValue("value1") : "Should contain value1";
//
//        // Test get
//        System.out.println("\nTesting get:");
//        System.out.println("Expected value for key1: value1");
//        System.out.println("Actual: " + node.get("key1"));
//        Object value1 = node.get("key1");
//        assert "value1".equals(value1) : "Should get correct value";
//
//        // Test put
//        System.out.println("\nTesting put:");
//        Object oldValue = node.put("key3", "value3");
//        System.out.println("Expected size after put: 5");
//        System.out.println("Actual size: " + node.size());
//        System.out.println("New value for key3: " + node.get("key3"));
//        System.out.println("Old value returned: " + oldValue);
//        assert node.size() == 5 : "Size should be 5 after put";
//        assert "value3".equals(node.get("key3")) : "New value should be set";
//
//        // Test remove
//        System.out.println("\nTesting remove:");
//        Object removedValue = node.remove("key1");
//        System.out.println("Expected key1 exists after remove: false");
//        System.out.println("Actual: " + node.containsKey("key1"));
//        System.out.println("Removed value: " + removedValue);
//        assert !node.containsKey("key1") : "Key1 should be removed";
//        assert "value1".equals(removedValue) : "Correct value should be returned on remove";
//
//        // Test keySet
//        System.out.println("\nTesting keySet:");
//        Set<String> keys = node.keySet();
//        System.out.println("Expected keys to contain: key2, key3, nested, array");
//        System.out.println("Actual keys: " + keys);
//        assert keys.contains("key2") && keys.contains("nested") : "KeySet should contain expected keys";
//
//        // Test values
//        System.out.println("\nTesting values:");
//        Collection<Object> values = node.values();
//        System.out.println("Expected values size: " + node.size());
//        System.out.println("Actual values: " + values);
//        assert values.contains("value2") : "Values should contain expected values";
//
//        // Test entrySet
//        System.out.println("\nTesting entrySet:");
//        Set<Entry<String, Object>> entries = node.entrySet();
//        System.out.println("Expected entries size: " + node.size());
//        System.out.println("Actual entries: " + entries);
//        assert entries.size() == node.size() : "EntrySet size should match map size";
//
//        // Test clear
//        System.out.println("\nTesting clear:");
//        node.clear();
//        System.out.println("Expected size after clear: 0");
//        System.out.println("Actual size: " + node.size());
//        assert node.isEmpty() : "Map should be empty after clear";
//
//        System.out.println("\nMap interface tests passed");
//    }
//
//    private static void testComplexQueries() {
//        System.out.println("\n=== Testing Complex JsonPath Queries ===");
//        String complexJson = """
//                {
//                    "store": {
//                        "book": [
//                            {
//                                "category": "fiction",
//                                "author": "J.R.R. Tolkien",
//                                "title": "The Lord of the Rings",
//                                "price": 22.99,
//                                "years": [1954, 1955]
//                            },
//                            {
//                                "category": "fiction",
//                                "author": "George R.R. Martin",
//                                "title": "A Game of Thrones",
//                                "price": 18.99,
//                                "years": [1996]
//                            },
//                            {
//                                "category": "technical",
//                                "author": "Douglas Crockford",
//                                "title": "JavaScript: The Good Parts",
//                                "price": 29.99,
//                                "years": [2008]
//                            }
//                        ],
//                        "bicycle": {
//                            "color": "red",
//                            "price": 399.99
//                        }
//                    }
//                }""";
//        System.out.println("\nInput complex JSON:");
//        System.out.println(complexJson);
//
//        MultiFormatDataNode2 node = MultiFormatDataNode2.fromString(complexJson);
//
//        // Test filtering with multiple conditions
//        System.out.println("\nTesting complex filtering - fiction books over $20:");
//        String filterPath = "$.store.book[?(@.category == 'fiction' && @.price > 20)]";
//        Object result = node.getFirstByPath(filterPath);
//        System.out.println("Expected: Book by Tolkien");
//        System.out.println("Actual: " + result);
//        assert result != null : "Should find expensive fiction book";
//        Map<String, Object> bookMap = (Map<String, Object>) result;
//        assert "fiction".equals(bookMap.get("category")) : "Should be fiction category";
//        assert "J.R.R. Tolkien".equals(bookMap.get("author")) : "Should be Tolkien book";
//
//        // Test deep array filtering
//        System.out.println("\nTesting deep array filtering - books published before 2000:");
//        String arrayPath = "$.store.book[?(@.years[0] < 2000)]";
//        List<Object> oldBooks = node.getAllByPath(arrayPath);
//        System.out.println("Expected count: 2");
//        System.out.println("Actual books found: " + oldBooks);
//        assert oldBooks.size() == 2 : "Should find two books before 2000";
//
//        // Test path-based queries
//        System.out.println("\nTesting path-based queries - all prices:");
//        Map<String, Object> priceMap = node.getAllByPathAsMap("$..price");
//        System.out.println("Expected: 4 prices (3 books + 1 bicycle)");
//        System.out.println("Actual paths and prices: " + priceMap);
//        assert priceMap.size() == 4 : "Should find 4 prices";
//
//        // Test array operations
//        System.out.println("\nTesting array operations - all book titles:");
//        List<Object> titles = node.getAllByPath("$.store.book[*].title");
//        System.out.println("Expected: 3 titles");
//        System.out.println("Actual titles: " + titles);
//        assert titles.size() == 3 : "Should find 3 titles";
//
//        // Test nested property access
//        System.out.println("\nTesting nested property access - bicycle details:");
//        Object bicycle = node.getFirstByPath("$.store.bicycle");
//        System.out.println("Expected: Bicycle object with color and price");
//        System.out.println("Actual: " + bicycle);
//        assert bicycle != null : "Should find bicycle";
//        Map<String, Object> bikeMap = (Map<String, Object>) bicycle;
//        assert "red".equals(bikeMap.get("color")) : "Bicycle should be red";
//
//        System.out.println("\nComplex query tests passed");
//    }
//
//
//}