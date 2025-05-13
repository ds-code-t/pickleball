// LinkedMultiMap.java
package io.pickleball.mapandStateutilities;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.io.File;
import java.util.*;

import static io.pickleball.configs.Constants.sFlag2;
import static io.pickleball.datafunctions.FileAndDataParsing.*;


public class LinkedMultiMap<K, V> extends HashMap<K, V> {

    public static final String configFlag = sFlag2 + "~mapConfig~";

    LinkedListMultimap<String, Object> multimap = LinkedListMultimap.create();

    public ObjectNode multiMapNode = JSON_MAPPER.createObjectNode();
    public ObjectNode mapNode = JSON_MAPPER.createObjectNode();

    public LinkedMultiMap() {

    }

    @SuppressWarnings("unchecked")
    public LinkedMultiMap(File... filesOrDirs) {
        for(File file: filesOrDirs){
            put((K) file.getName(), (V) buildJsonFromPath(file));
        }
    }

    public LinkedMultiMap(List<K> keys, List<V> values) {
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

    @SuppressWarnings("unchecked")
    public void parseDirectoriesContents(File... filesOrDirs) {
        for(File file: filesOrDirs) {
            if (file.isDirectory()) {
                File[] children = file.listFiles();
                if (children != null) {  // Always check for null as listFiles() may return null
                    for (File child : children) {
                        put((K) child.getName(), (V) buildJsonFromPath(child));
                    }
                }
            } else if (file.isFile()) {
                JsonNode node = buildJsonFromPath(file);
                Iterator<Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String fieldName = field.getKey();
                    JsonNode childNode = field.getValue();
                    put((K) fieldName, (V) childNode);
                }
            }
        }
    }



    public Object getConfig(String key) {
        return get(configFlag + key);
    }

    public Object putConfig(String key, Object value) {
        return put((K) (configFlag + key), (V) value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public V put(K key, V value) {
        String sKey = String.valueOf(key);
        if(sKey.startsWith(configFlag))
        {
            return super.put((K) sKey, value);
        }
        ArrayNode arrayNode = (ArrayNode) multiMapNode.putIfAbsent(sKey , JSON_MAPPER.createArrayNode());
        if (arrayNode == null)
            arrayNode = (ArrayNode) multiMapNode.get(sKey);
//        String numberedKey = "<" + key + " #" + (arrayNode.size() + 1) + ">";
        String numberedKey =  key + " #" + (arrayNode.size() + 1);
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
        return super.put((K) numberedKey, value);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        V returnObj = get(key);
        if(returnObj == null)
            return defaultValue;
        return returnObj;
    }


    @SuppressWarnings("unchecked")
    @Override
    public V get(Object key) {
        System.out.println("@@getOverRide: " + key);
        String sKey = String.valueOf(key);
        Object returnVal = super.get(key);
        if (returnVal != null)
            return (V) returnVal;
        List<Object> returnList = multimap.get(sKey);


        String nodeKey = sKey.replaceAll("\\s+", "_");
        if (!returnList.isEmpty())
            return (V) returnList.get(returnList.size() - 1);
        returnVal = getLastVal(multiMapNode, nodeKey);

        if (returnVal != null)
            return (V) returnVal;
        return (V) getLastVal(mapNode, nodeKey);
    }



    public static Object getLastVal(ObjectNode objectNode, String key) {
        System.out.println("@@getLastVal: " + key);
        System.out.println("@@objectNode: " + objectNode);
        DocumentContext documentContext = JsonPath.using(valueConfig).parse(objectNode);
        ArrayNode arrayNode = documentContext.read(key);
        if(arrayNode.isEmpty())
            return null;
        return arrayNode.get(arrayNode.size()-1);
    }









}