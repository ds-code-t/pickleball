// LinkedMultiMap.java
package io.pickleball.mapandStateutilities;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
            System.out.println("@@file.getName(): " + file.getName());
            put((K) file.getName(), (V) buildJsonFromPath(file));
        }
    }

    public LinkedMultiMap(List<K> keys, List<V> values) {
        for (int n = 0; n < keys.size(); n++) {
            System.out.println("@@#key#" + n + " : "+ keys.get(n));
            System.out.println("@@#value#" + n + " : "+ values.get(n));
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


    @SuppressWarnings("unchecked")
    @Override
    public V put(K key, V value) {
        System.out.println("@@---put :: " +key + " ; " + value);
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
        mapNode.set(sKey, node);
        multimap.put(sKey, value);
        System.out.println("@@---mapNode :: " +mapNode);
        System.out.println("@@---multimap :: " +multimap);
        return super.put((K) numberedKey, value);
    }


    @SuppressWarnings("unchecked")
    @Override
    public V get(Object key) {
        System.out.println("@@---mapNode get: :: " +mapNode);
        System.out.println("@@---multimap get: :: " +multimap);
        String sKey = String.valueOf(key);
        Object returnVal = super.get(key);
        System.out.println("@@returnVal: " + returnVal);
        if (returnVal != null)
            return (V) returnVal;
        List<Object> returnList = multimap.get(sKey);
        System.out.println("@@returnList: " + returnList);
        if (!returnList.isEmpty())
            System.out.println("@@returnList.get(returnList.size() - 1): " + returnList.get(returnList.size() - 1));

        if (!returnList.isEmpty())
            return (V) returnList.get(returnList.size() - 1);
        returnVal = getLastVal(multiMapNode, sKey);
        System.out.println("@@returnVal2: " + returnVal);

        if (returnVal != null)
            return (V) returnVal;
        System.out.println("@@getLastVal(mapNode, sKey): " + getLastVal(mapNode, sKey));
        return (V) getLastVal(mapNode, sKey);
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