package tools.ds.modkit.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.LinkedListMultimap;
import tools.ds.modkit.mappings.queries.Tokenized;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static tools.ds.modkit.evaluations.AviatorUtil.eval;
import static tools.ds.modkit.evaluations.AviatorUtil.evalToBoolean;


public abstract class MappingProcessor implements Map<String, Object> {


    private final LinkedListMultimap<ParsingMap.MapType, NodeMap> maps = LinkedListMultimap.create();
    private final List<ParsingMap.MapType> keyOrder;

    public MappingProcessor(ParsingMap parsingMap) {
        this.keyOrder = parsingMap.keyOrder();
        this.maps.putAll(parsingMap.getMaps());
    }

    public MappingProcessor(ParsingMap.MapType... keys) {
        // Defensive copy to make key order immutable
        this.keyOrder = Collections.unmodifiableList(new ArrayList<>(Arrays.asList(keys)));
    }

    protected LinkedListMultimap<ParsingMap.MapType, NodeMap> getMaps() {
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
        for(List<NodeMap> list : grouped)
        {
            if(list.isEmpty())
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


    private void clearMapType(ParsingMap.MapType key) {
        maps.removeAll(key);
    }


    /**
     * Get a flat list of values, grouped and ordered by the original key order
     */
    public List<NodeMap> valuesInKeyOrder() {
        List<NodeMap> out = new ArrayList<>();
        for (ParsingMap.MapType key : keyOrder) {
            out.addAll(maps.get(key)); // maps.get() is live, but empty if unused
        }
        return out;
    }


    /**
     * Expose immutable key order (for debugging/inspection)
     */
    public List<ParsingMap.MapType> keyOrder() {
        return keyOrder;
    }


    private static final Pattern ANGLE = Pattern.compile("<([^<>{}]+)>");
    private static final Pattern CURLY = Pattern.compile("\\{([^{}]+)\\}");


//    public void put(int index, Object key, Object value) {
//        maps.get(index).put(key, value);
//    }


    public String resolveWholeText(String input) {
        QuoteParser parsedObj = new QuoteParser(input);
        try {
            parsedObj.setMasked(resolveAll(parsedObj.masked()));
            for (var e : parsedObj.entrySet()) {
                char q = parsedObj.quoteTypeOf(e.getKey());
                if (q == QuoteParser.SINGLE || q == QuoteParser.DOUBLE) {
                    parsedObj.put(e.getKey(), resolveAll(e.getValue()));
                }
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
                    if (input.equals(prev)) equalityCount = 0;
                }
            } while (equalityCount < 2);
            return input;
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

            outer:
            while (m.find()) {
                key = m.group(1);

                Tokenized tokenized = new Tokenized(key);

                for (NodeMap map : valuesInKeyOrder()) {
                    if (map == null) continue;
                    replacement = map.get(tokenized);
                    if (replacement != null) {
                        break outer;  // exits BOTH loops in one go
                    }
                }
            }


            if (replacement == null)
                return s;

            String stringReplacement = getStringValue(replacement);
            if (stringReplacement.isEmpty() && key != null)
                stringReplacement = "<?" + key + ">";

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
                String repl = key.endsWith("?") ? String.valueOf(evalToBoolean(key.substring(0, key.length() - 1), this)) : String.valueOf(eval(key, this));
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
        for (NodeMap map : maps.values()) {
            Object returnObj = map.get(String.valueOf(key));
            if (returnObj != null)
                return returnObj;
        }
        return null;
    }


    @Override
    public Object put(String key, Object value) {
        return null;
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
        if (obj instanceof List<?> list) {
            if (list.isEmpty()) return String.valueOf(obj);
            if (list.getFirst() instanceof JsonNode)
                return String.valueOf(((List<JsonNode>) list).stream().map(JsonNode::asText).toList());
        }
        if (obj instanceof JsonNode jsonNode)
            return jsonNode.asText();
        return String.valueOf(obj);
    }

//    public static boolean isMatch(Object obj) {
//        if(obj == null)
//            return false;
//        if(obj instanceof ArrayNode arrayNode)
//
//    }

}
