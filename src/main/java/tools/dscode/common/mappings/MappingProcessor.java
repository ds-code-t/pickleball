package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.LinkedListMultimap;
import tools.dscode.common.mappings.queries.Tokenized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static tools.dscode.common.evaluations.AviatorUtil.eval;
import static tools.dscode.common.evaluations.AviatorUtil.evalToBoolean;
import static tools.dscode.common.mappings.NodeMap.MAPPER;
import static tools.dscode.common.util.StringUtilities.decodeBackToText;
import static tools.dscode.common.util.StringUtilities.encodeToPlaceHolders;

public abstract class MappingProcessor implements Map<String, Object> {

    protected final LinkedListMultimap<MapConfigurations.MapType, NodeMap> maps = LinkedListMultimap.create();
    protected final List<MapConfigurations.MapType> keyOrder = new ArrayList<>();
    protected final List<MapConfigurations.MapType> singletonOrder = new ArrayList<>();

    public void copyParsingMap(ParsingMap parsingMap) {
        maps.clear();
        keyOrder.clear();
        maps.putAll(parsingMap.getMaps());
        keyOrder.addAll(parsingMap.keyOrder());
    }

    public MappingProcessor(ParsingMap parsingMap) {
        copyParsingMap(parsingMap);
        addMaps(new NodeMap(MapConfigurations.MapType.RUN_MAP));
        addMaps(new NodeMap(MapConfigurations.MapType.SINGLETON));
    }

    public MappingProcessor() {
        // Defensive copy to make key order immutable
        addMaps(new NodeMap(MapConfigurations.MapType.RUN_MAP));
        addMaps(new NodeMap(MapConfigurations.MapType.SINGLETON));
        this.keyOrder.addAll(Arrays.asList(MapConfigurations.MapType.OVERRIDE_MAP, MapConfigurations.MapType.STEP_MAP,
            MapConfigurations.MapType.RUN_MAP, MapConfigurations.MapType.SINGLETON,
            MapConfigurations.MapType.GLOBAL_NODE, MapConfigurations.MapType.DEFAULT));
        this.singletonOrder.addAll(Arrays.asList(MapConfigurations.MapType.OVERRIDE_MAP,
            MapConfigurations.MapType.SINGLETON, MapConfigurations.MapType.STEP_MAP, MapConfigurations.MapType.RUN_MAP,
            MapConfigurations.MapType.GLOBAL_NODE, MapConfigurations.MapType.DEFAULT));
    }

    public NodeMap getPrimaryRunMap() {
        return maps.get(MapConfigurations.MapType.RUN_MAP).getFirst();
    }

    public NodeMap getRootSingletonMap() {
        return maps.get(MapConfigurations.MapType.SINGLETON).getFirst();
    }

    protected LinkedListMultimap<MapConfigurations.MapType, NodeMap> getMaps() {
        return maps;
    }

    public void removeMaps(NodeMap... nodes) {
        removeMaps(Arrays.stream(nodes).toList());
    }

    public void removeMaps(List<NodeMap> nodes) {
        for (NodeMap node : nodes) {
            List<NodeMap> nodeList = maps.get(node.getMapType());
            nodeList.remove(node);
        }
    }

    public void replaceMaps(NodeMap... nodes) {
        replaceMaps(Arrays.stream(nodes).toList());
    }

    public void replaceMaps(List<NodeMap> nodes) {
        if (nodes != null) {
            for (NodeMap node : nodes) {
                clearMapType(node.getMapType());
            }
            for (NodeMap node : nodes) {
                maps.put(node.getMapType(), node);
            }
        }
    }

    public void addMaps(NodeMap... nodes) {
        addMaps(Arrays.stream(nodes).toList());
    }

    public void addMaps(List<NodeMap> nodes) {
        if (nodes != null) {
            for (NodeMap node : nodes) {
                maps.put(node.getMapType(), node);
            }
        }
    }

    public void addMapsToStart(NodeMap... nodes) {
        addMapsToStart(Arrays.stream(nodes).toList());
    }

    public void addMapsToStart(List<NodeMap> nodes) {
        List<List<NodeMap>> grouped = groupByMapType(nodes);
        for (List<NodeMap> list : grouped) {
            if (list.isEmpty())
                continue;
            List<NodeMap> existingNodes = maps.get(list.getFirst().getMapType());
            existingNodes.addAll(list);
        }
    }

    public static List<List<NodeMap>> groupByMapType(List<NodeMap> nodes) {
        List<List<NodeMap>> grouped = new ArrayList<>();
        for (NodeMap c : nodes) {
            if (grouped.isEmpty() ||
                    !grouped.getLast().getFirst().getMapType().equals(c.getMapType())) {
                grouped.add(new ArrayList<>());
            }
            grouped.getLast().add(c);
        }
        return grouped;
    }

    private void clearMapType(MapConfigurations.MapType key) {
        maps.removeAll(key);
    }

    /**
     * Get a flat list of values, grouped and ordered by the original key order
     */
    public List<NodeMap> getMapsForResolution() {
        List<NodeMap> out = new ArrayList<>();
        for (MapConfigurations.MapType key : keyOrder) {
            out.addAll(maps.get(key)); // maps.get() is live, but empty if
                                       // unused
        }
        return out;
    }

    public List<NodeMap> getMapsForSingletonResolution() {
        List<NodeMap> out = new ArrayList<>();
        for (MapConfigurations.MapType key : singletonOrder) {
            List<NodeMap> mapList = maps.get(key);
            out.addAll((key.equals(MapConfigurations.MapType.STEP_MAP) ? mapList.reversed() : mapList));
        }
        return out;
    }

    /**
     * Expose immutable key order (for debugging/inspection)
     */
    public List<MapConfigurations.MapType> keyOrder() {
        return keyOrder;
    }

    private static final Pattern ANGLE = Pattern.compile("<([^<>{}]+)>");
    private static final Pattern CURLY = Pattern.compile("\\{([^{}]+)\\}");

    // public void put(int index, Object key, Object value) {
    // maps.get(index).put(key, value);
    // }

    public String resolveWholeText(String input) {
        QuoteParser parsedObj = new QuoteParser(input);
        try {
            parsedObj.setMasked(resolveAll(parsedObj.masked()));
            for (var e : parsedObj.entrySetWithoutTripleSingle()) {
                parsedObj.put(e.getKey(), resolveAll(e.getValue()));
            }
            return parsedObj.restore();
        } catch (Throwable t) {
            return parsedObj.restore();
        }
    }

    public String resolveAll(String input) {
        try {
            int equalityCount = 0;
            String prev;
            do {
                prev = input;
                if (input.contains("<")) {
                    input = resolveByMap(input);
                } else {
                    break;
                }
                if (input.contains("{")) {
                    input = resolveCurly(input);
                }

                if (input.equals(prev))
                    equalityCount++;
                else
                    equalityCount = 0;
                if (input.contains("<?") && equalityCount > 1) {
                    input = input.replaceAll("<\\?[^<>{}]+>", "");
                    if (input.equals(prev))
                        equalityCount = 0;
                }
            } while (equalityCount < 2);
            return decodeBackToText(input);
        } catch (Throwable t) {
            throw new RuntimeException("Could not resolve'" + input + "'", t);
        }
    }

    private String resolveByMap(String s) {
        System.out.println("@@===resolveByMap: " + s);

        String key = null;
        try {
            Matcher m = ANGLE.matcher(s);
            StringBuffer sb = new StringBuffer();
            Object replacement = null;

            while (m.find()) {
                key = m.group(1);
                replacement = get(key);
                if (replacement != null)
                    break;
            }

            if (replacement == null)
                return s;

            String stringReplacement = getStringValue(replacement);
            if (stringReplacement.isEmpty() && key != null && !key.isBlank()) {
                stringReplacement = key.startsWith("?") ? "<" + key + ">" : "<?" + key + ">";
            }
            m.appendReplacement(sb, stringReplacement);

            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            throw new RuntimeException("Could not resolve by map '" + s + "'", t);
        }
    }

    private String resolveCurly(String s) {
        try {
            Matcher m = CURLY.matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String key = m.group(1).trim();
                key = decodeBackToText(key);
                String repl = key.endsWith("?")
                        ? String.valueOf(evalToBoolean(key.substring(0, key.length() - 1), this))
                        : String.valueOf(eval(key, this));
                m.appendReplacement(sb, repl == null ? m.group(0) : Matcher.quoteReplacement(repl));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            throw new RuntimeException("Could not resolve by curly bracket expression '" + s + "'", t);
        }
    }

    @Override
    public Object get(Object key) {
        if (key == null)
            throw new RuntimeException("key cannot be null");
        if (key instanceof String stringKey)
            return get(stringKey);
        for (NodeMap map : maps.values()) {
            Object returnObj = map.get(String.valueOf(key));
            if (returnObj != null)
                return returnObj;
        }
        return null;
    }

    public Object get(String key) {
        Tokenized tokenized = new Tokenized(key);
        for (NodeMap map : (tokenized.isSingletonKey ? getMapsForSingletonResolution() : getMapsForResolution())) {
            if (map == null)
                continue;
            Object replacement = map.get(tokenized);
            if (replacement != null) {
                return replacement;
            }
        }
        return null;
    }

    @Override
    public Object put(String key, Object value) {
        if (key == null || key.isBlank())
            throw new RuntimeException("key cannot be null or blank");
        Tokenized tokenized = new Tokenized(key);
        if (tokenized.isSingletonKey) {
            Object oldValue = getRootSingletonMap().get(tokenized);
            getRootSingletonMap().put(tokenized, value);
            return oldValue;
        }
        Object oldValue = getPrimaryRunMap().get(tokenized);
        getPrimaryRunMap().put(tokenized, value);
        return oldValue;
    }

    public Object putObject(Object key, Object value) {
        if (key instanceof String stringKey)
            return put(stringKey, value);
        if (key == null)
            throw new RuntimeException("key cannot be null");
        Object oldValue = getPrimaryRunMap().get(String.valueOf(key));
        getPrimaryRunMap().put(String.valueOf(key), value);
        return oldValue;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean containsKey(Object key) {
        return false;
    }

    @Override
    public boolean containsValue(Object value) {
        return false;
    }

    @Override
    public Object remove(Object key) {
        return null;
    }

    @Override
    public void putAll(Map<? extends String, ?> m) {

    }

    @Override
    public void clear() {

    }

    @Override
    public Set<String> keySet() {
        return Set.of();
    }

    @Override
    public Collection<Object> values() {
        return List.of();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return Set.of();
    }


    public static String getStringValue(Object obj) {
        if (obj == null)
            return "";

        if (obj instanceof JsonNode jsonNode) {
            if (jsonNode.isTextual()) {
                // avoid quotes around simple strings
                return jsonNode.textValue();
            }
            if (jsonNode.isValueNode()) {
                // numbers, booleans, null
                return jsonNode.asText("");
            }
            try {
                // arrays / objects → canonical JSON
                String otherNode = MAPPER.writeValueAsString(jsonNode);
                return encodeToPlaceHolders(otherNode);
            } catch (JsonProcessingException e) {
                // fallback to best-effort text
                return jsonNode.toString();
            }
        }

        return String.valueOf(obj);
    }

    @Override
    public String toString() {
        return "\n====\n" + getMapsForResolution()
                .stream()
                .map(String::valueOf) // safely converts each element to its
                                      // string form
                .collect(Collectors.joining(System.lineSeparator())) + "\n---\n";
    }
}
