// LinkedMultiMap.java
package io.pickleball.mapandStateutilities;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import io.pickleball.datafunctions.TypeConverter;
import io.pickleball.exceptions.PickleballException;

import java.io.File;
import java.util.*;

import static io.pickleball.stringutilities.Constants.sFlag2;
import static io.pickleball.datafunctions.FileAndDataParsing.*;


public class LinkedMultiMap extends HashMap<Object, Object> implements TypeConverter {

//    public static final String configFlag = sFlag2 + "~mapConfig~";
    public static final String configFlag = "CONFIG-";

    LinkedListMultimap<String, Object> multimap = LinkedListMultimap.create();

    public ObjectNode multiMapNode = JSON_MAPPER.createObjectNode();
    public ObjectNode mapNode = JSON_MAPPER.createObjectNode();

    public LinkedMultiMap() {

    }


    public static LinkedMultiMap createMapFromPath(String key, String path) {
        if(key == null || key.isBlank())
            return createMapFromPath(path);

        LinkedMultiMap map = new LinkedMultiMap();
        JsonNode jsonNode = buildJsonFromPath(path);
        if(jsonNode == null)
            return map;
        map.put(key, jsonNode);
        return map;
    }

    public static LinkedMultiMap createMapFromPath(String path) {
        LinkedMultiMap map = new LinkedMultiMap();
        JsonNode jsonNode = buildJsonFromPath(path);
        if(jsonNode == null)
            return map;
        for (Iterator<Entry<String, JsonNode>> it = jsonNode.fields(); it.hasNext(); ) {
            Entry<String, JsonNode> entry = it.next();
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }


//    public  void addConfigFile(String key, String path) {
//        if(key == null || key.isBlank())
//            addFromFile(path);
//        JsonNode jsonNode = buildJsonFromPath(path);
//        put(key, configFlag + jsonNode);
//    }

    public  void addFromFile(String key, String path) {
        if(key == null || key.isBlank())
            addFromFile(path);
        JsonNode jsonNode = buildJsonFromPath(path);
        if(jsonNode == null)
            throw new PickleballException("Resource not found at path: " + path);
        put(key, jsonNode);
    }

    public  void addFromFile(String path) {

        JsonNode jsonNode = buildJsonFromPath(path);
        if(jsonNode == null)
            return;
        for (Iterator<Entry<String, JsonNode>> it = jsonNode.fields(); it.hasNext(); ) {
            Entry<String, JsonNode> entry = it.next();
            put(entry.getKey(), entry.getValue());
        }

    }


//    public LinkedMultiMap(File... filesOrDirs) {
//        for (File file : filesOrDirs) {
//            put(removeFileExtension(file.getName()), buildJsonFromPath(file));
//        }
//    }


    public LinkedMultiMap(List<?> keys, List<?> values) {
        for (int n = 0; n < keys.size(); n++) {
            put(keys.get(n), values.get(n));
        }
    }

    public void addToLinkedListMultimap(String key, Object value) {
        multimap.put(key, value);
    }

    public void removeFromLinkedListMultimap(String key) {
        multimap.removeAll(key);
    }
//    public void parseDirectoriesContents(String key, String... filePaths) {
//        for(String path: filePaths)
//        {
//            put(key,  createMapFromPath(path));
//        }
//    }

    //    @SuppressWarnings("unchecked")
//    public void parseDirectoriesContents(String... filePaths) {
//        File[] files = Arrays.stream(filePaths)
//                .map(path -> getFile(path))
//                .toArray(File[]::new);
//        parseDirectoriesContents(files);
//    }


//    public void parseDirectoriesContents(File... filesOrDirs) {
//        for (File file : filesOrDirs) {
//            if (file.isDirectory()) {
//                File[] children = file.listFiles();
//                if (children != null) {  // Always check for null as listFiles() may return null
//                    for (File child : children) {
//                        put(child.getName(), buildJsonFromPath(child));
//                    }
//                }
//            } else if (file.isFile()) {
//                JsonNode node = buildJsonFromPath(file);
//                Iterator<Entry<String, JsonNode>> fields = node.fields();
//                while (fields.hasNext()) {
//                    Map.Entry<String, JsonNode> field = fields.next();
//                    String fieldName = field.getKey();
//                    JsonNode childNode = field.getValue();
//                    put(fieldName, childNode);
//                }
//            }
//        }
//    }


    public Object getConfig(String key) {
        return get(configFlag + key);
    }

    public Object putConfig(String key, Object value) {
        return put((configFlag + key), value);
    }


    //    @SuppressWarnings("unchecked")
//    @Override
    public Object put(Object key, Object value) {
        String sKey = String.valueOf(key);
        if (sKey.startsWith(configFlag)) {
            return super.put(sKey, value);
        }
        ArrayNode arrayNode = (ArrayNode) multiMapNode.putIfAbsent(sKey, JSON_MAPPER.createArrayNode());
        if (arrayNode == null)
            arrayNode = (ArrayNode) multiMapNode.get(sKey);
//        String numberedKey = "<" + key + " #" + (arrayNode.size() + 1) + ">";
        String numberedKey = key + " #" + (arrayNode.size() + 1);
        JsonNode node;
        try {
            node = JSON_MAPPER.valueToTree(value);
            arrayNode.add(node);
        } catch (Exception e) {
            node = JSON_MAPPER.valueToTree(numberedKey);
            arrayNode.add(node);
        }
        String nodeKey = sKey.replaceAll("\\s+", "_");
        mapNode.set(nodeKey, node);
        multimap.put(nodeKey, value);
        return super.put(numberedKey, value);
    }

    //    @Override
    public Object getOrDefault(Object key, Object defaultValue) {
        Object returnObj = get(key);
        if (returnObj == null)
            return defaultValue;
        return returnObj;
    }


    //    @SuppressWarnings("unchecked")
//    @Override
    public Object get(Object key) {
        String sKey = String.valueOf(key);
        Object returnVal = super.get(key);
        if (returnVal != null)
            return returnVal;
        List<Object> returnList = multimap.get(sKey);


        String nodeKey = sKey.replaceAll("\\s+", "_");
        if (!returnList.isEmpty())
            return returnList.get(returnList.size() - 1);
        returnVal = getLastVal(multiMapNode, nodeKey);

        if (returnVal != null)
            return returnVal;
        return getLastVal(mapNode, nodeKey);
    }


    public static Object getLastVal(ObjectNode objectNode, String key) {
        DocumentContext documentContext = JsonPath.using(valueConfig).parse(objectNode);
        ArrayNode arrayNode = documentContext.read(key);
        if (arrayNode.isEmpty())
            return null;
        return arrayNode.get(arrayNode.size() - 1);
    }


}