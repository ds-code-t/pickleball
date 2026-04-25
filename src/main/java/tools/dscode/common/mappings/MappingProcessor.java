package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import tools.dscode.common.mappings.queries.Tokenized;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static io.cucumber.core.runner.util.TableUtils.CELL_KEY;
import static io.cucumber.core.runner.util.TableUtils.DATA_OBJECT_KEY;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.HEADER_KEY;
import static io.cucumber.core.runner.util.TableUtils.ENTRY_KEY;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static tools.dscode.common.GlobalConstants.MATCH_BREAK;
import static tools.dscode.common.dataoperations.DataComparisons.filterGroupedValues;

import static tools.dscode.common.dataoperations.TableQueries.findCells;
import static tools.dscode.common.dataoperations.TableQueries.findHeaders;
import static tools.dscode.common.dataoperations.TableQueries.findRows;

import static tools.dscode.common.evaluations.AviatorUtil.eval;
import static tools.dscode.common.evaluations.AviatorUtil.evalToBoolean;
import static tools.dscode.common.mappings.FileAndDataParsing.buildJsonFromPath;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.queries.Tokenized.AS_LIST_SUFFIX;
import static tools.dscode.common.util.StringUtilities.decodeBackToText;
import static tools.dscode.common.util.StringUtilities.encodeToPlaceHolders;

import static tools.dscode.common.variables.RunVars.resolveFromVars;
import static tools.dscode.testengine.PickleballRunner.PKB_PREFIX;

public abstract class MappingProcessor implements Map<String, Object> {

    protected final LinkedListMultimap<MapConfigurations.MapType, NodeMap> maps = LinkedListMultimap.create();
    protected final List<MapConfigurations.MapType> keyOrder = new ArrayList<>();
    protected final List<MapConfigurations.MapType> singletonOrder = new ArrayList<>();

    public static ThreadLocal<NodeMap> runMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> singletonMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> overridesMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> defaultsMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> dataTableMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> docStringMap = new ThreadLocal<>();

    public static void resetCommonMaps() {
        runMap.set(new NodeMap(MapConfigurations.MapType.RUN_MAP));
        singletonMap.set(new NodeMap(MapConfigurations.MapType.SINGLETON));
        overridesMap.set(new NodeMap(MapConfigurations.MapType.OVERRIDE_MAP));
        defaultsMap.set(new NodeMap(MapConfigurations.MapType.DEFAULT));
        dataTableMap.set(new NodeMap(MapConfigurations.MapType.DATATABLE));
        docStringMap.set(new NodeMap(MapConfigurations.MapType.DOCSTRING));
    }

    public NodeMap getOrAddAndGetMap(MapConfigurations.MapType mapType) {
        List<NodeMap> returnMaps = maps.get(mapType);
        if (returnMaps.isEmpty()) {
            NodeMap newMap = new NodeMap(mapType);
            addMaps(newMap);
            return newMap;
        }
        return returnMaps.getFirst();
    }

    public static NodeMap getRunMap() {
        return runMap.get();
    }

    public static NodeMap getSingletonMap() {
        return singletonMap.get();
    }

    public static NodeMap getOverridesMap() {
        return overridesMap.get();
    }

    public static NodeMap getDefaultsMap() {
        return defaultsMap.get();
    }

    public static NodeMap getDataTableMap() {
        return dataTableMap.get();
    }

    public static NodeMap getDocStringMap() {
        return docStringMap.get();
    }


    public MappingProcessor() {
        // Defensive copy to make key order immutable
        addMaps(GLOBALS, runMap.get(), singletonMap.get(), overridesMap.get(), defaultsMap.get(), dataTableMap.get(), docStringMap.get());

        this.keyOrder.addAll(Arrays.asList(
                MapConfigurations.MapType.OVERRIDE_MAP,
                MapConfigurations.MapType.PHRASE_MAP,
                MapConfigurations.MapType.STEP_MAP,
                MapConfigurations.MapType.DATATABLE,
                MapConfigurations.MapType.DOCSTRING,
                MapConfigurations.MapType.PASSED_MAP,
                MapConfigurations.MapType.EXAMPLE_MAP,
                MapConfigurations.MapType.RUN_MAP,
                MapConfigurations.MapType.SINGLETON,
                MapConfigurations.MapType.GLOBAL_NODE,
                MapConfigurations.MapType.DEFAULT));
        this.singletonOrder.addAll(Arrays.asList(
                MapConfigurations.MapType.OVERRIDE_MAP,
                MapConfigurations.MapType.SINGLETON,
                MapConfigurations.MapType.PHRASE_MAP,
                MapConfigurations.MapType.STEP_MAP,
                MapConfigurations.MapType.DATATABLE,
                MapConfigurations.MapType.DOCSTRING,
                MapConfigurations.MapType.PASSED_MAP,
                MapConfigurations.MapType.EXAMPLE_MAP,
                MapConfigurations.MapType.RUN_MAP,
                MapConfigurations.MapType.GLOBAL_NODE,
                MapConfigurations.MapType.DEFAULT));
    }

    protected MappingProcessor(NodeMap nodeMap) {
        addMaps(nodeMap);
        this.keyOrder.add(nodeMap.getMapType());
        this.singletonOrder.add(nodeMap.getMapType());
    }

    public NodeMap getPhraseMap() {
        return getOrAddAndGetMap(MapConfigurations.MapType.PHRASE_MAP);
    }

    public NodeMap getPrimaryRunMap() {
        return maps.get(MapConfigurations.MapType.RUN_MAP).getFirst();
    }

    public NodeMap getRootSingletonMap() {
        return maps.get(MapConfigurations.MapType.SINGLETON).getFirst();
    }

    public LinkedListMultimap<MapConfigurations.MapType, NodeMap> getMaps() {
        return maps;
    }

    public void removeMaps(NodeMap... nodes) {
        removeMaps(Arrays.stream(nodes).toList());
    }

    public void clearDataSourceMaps(MapConfigurations.DataSource... dataSources) {
        for (MapConfigurations.DataSource dataSource : dataSources) {
            maps.values().forEach(m -> m.getDataSources());
        }
    }

    public void removeMaps(MapConfigurations.DataSource... dataSources) {
        List<NodeMap> nodeMapList = new ArrayList<>();
        for (MapConfigurations.DataSource dataSource : dataSources) {
            nodeMapList.addAll(maps.values().stream().filter(nodeMap -> nodeMap.getDataSources().contains(dataSource)).toList());
        }
        removeMaps(nodeMapList);
    }

    public void removeMaps(MapConfigurations.MapType... mapTypes) {
        List<NodeMap> nodeMapList = new ArrayList<>();
        for (MapConfigurations.MapType mapType : mapTypes) {
            nodeMapList.addAll(maps.values().stream().filter(nodeMap -> nodeMap.getMapType() == mapType).toList());
        }
        removeMaps(nodeMapList);
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
        boolean log = (nodes.stream().anyMatch(m -> m.getMapType() == MapConfigurations.MapType.STEP_MAP));
        if (log) {
        }
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
        boolean log = (nodes.stream().anyMatch(m -> m.getMapType() == MapConfigurations.MapType.STEP_MAP));
        if (log) {
        }
        List<List<NodeMap>> grouped = groupByMapType(nodes);
        for (List<NodeMap> list : grouped) {
            if (list.isEmpty())
                continue;
            List<NodeMap> existingNodes = maps.get(list.getFirst().getMapType());
            existingNodes.addAll(0, list);
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
            System.out.println("");
            String resolvedText = parsedObj.restore();
            System.out.println("Resolved: '" + input + "' -> '" + resolvedText + "'");
            return resolvedText;
        } catch (Throwable t) {
            String resolvedText = parsedObj.restore();
            System.out.println("Handled exception '" + t.getMessage() + "' when attempting to resolve: '" + input + "' -> '" + resolvedText + "'");
            return resolvedText;
        }
    }

    public String resolveAll(String input) {
        try {
            String originalInput;
            do {
                originalInput = input;
                String prev;
                do {
                    prev = input;
                    if (input.contains("<")) {
                        input = resolveByMap(input);
                    }
                    if (input.contains("{")) {
                        input = resolveCurly(input);
                    }
                } while (!input.equals(prev));
                input = input.replaceAll("<\\?[^<>{}]+>", "");
            } while (!input.equals(originalInput));
            return decodeBackToText(input.replaceAll(MATCH_BREAK,""));
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException("Could not resolve '" + input + "' due to '" + t.getMessage() + "'", t);
        }
    }

    private String resolveByMap(String s) {
        String key = null;
        try {
            Matcher m = ANGLE.matcher(s);
            StringBuffer sb = new StringBuffer();
            Object replacement = null;

            while (m.find()) {
                key = m.group(1);
                if(key.contains(MATCH_BREAK)) {
                    replacement = "<" + key + ">";
                    break;
                }
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
            if(stringReplacement.contains("<") && !key.contains(MATCH_BREAK) && stringReplacement.contains("<" + key + ">"))
            {
                stringReplacement = stringReplacement.replaceAll("<" + key + ">", "<" + MATCH_BREAK + key + ">");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(stringReplacement));
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            throw new RuntimeException("Could not resolve by map '" + s + "' due to: " + t.getMessage(), t);
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


    public Object getAndResolve(Object key) {
        if (key == null)
            throw new RuntimeException("key cannot be null");
        Object returnObj = get(key);
        if (returnObj instanceof String returnString) return resolveWholeText(returnString);
        return returnObj;
    }

//    public Object getCaseInsensitiveAndResolve(String key) {
//        Object returnObj = getCaseInsensitive(resolveWholeText(key));
//        if (returnObj == null) return null;
//        if(returnObj  instanceof String returnString)  return resolveWholeText(returnString);
//        return returnObj;
//    }

    public Object getCaseInsensitive(String key) {
        if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
            return resolveFromVars(key);
        }
        Tokenized tokenized = new Tokenized(key);
        for (NodeMap map : (tokenized.isSingletonKey ? getMapsForSingletonResolution() : getMapsForResolution())) {
            if (map == null)
                continue;
            Object replacement = map.getByNormalizedPath(key);
            if (replacement != null) {
                return replacement;
            }
        }
        return null;
    }


    private void putVar(String key, Object value) {
        key = (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) ? key.toLowerCase() : PKB_PREFIX + key.toLowerCase();
        singletonMap.get().root.remove(key);
        singletonMap.get().putAsSingleton(key, value);
    }

    public List<Object> getList(String key) {
        return (List<Object>) get(addSuffix(key, AS_LIST_SUFFIX));
    }

    public static String addSuffix(String key, String suffix) {
        return key.endsWith(suffix) ? key : key + " " + suffix;
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
        if (key.startsWith("$"))
            return getRunningStep().resolveStepFromString(key.substring(1));

        boolean directGet = (key.startsWith("`") && key.endsWith("`"));

        if (directGet) {
            key = key.substring(1, key.length() - 1);
        } else {
            if (key.startsWith("/")) {
                return buildJsonFromPath(key.substring(1));
            }
            if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
                return resolveFromVars(key);
            }
        }

        Tokenized tokenized = new Tokenized(key);
        for (NodeMap map : (tokenized.isSingletonKey ? getMapsForSingletonResolution() : getMapsForResolution())) {
            if (map == null)
                continue;
            Object replacement = directGet ? map.directGet(key) : map.get(tokenized);
            if (replacement != null) {
                return replacement;
            }
        }
        return null;
    }

    public List<?> get(ElementMatch element) {
        String categoryName = element.category.replaceFirst("(?i:s)$", "");
        boolean noQuotedText = element.defaultText == null || element.defaultText.isNullOrBlank();
        if (categoryName.equals(TABLE_KEY)) {
            if (noQuotedText) {
                return element.parentPhrase.getPhraseParsingMap().getPhraseMap().getAsList(TABLE_KEY);
            } else {
                java.util.LinkedHashMap<String, JsonNode> tableList = (java.util.LinkedHashMap) getDataTableMap().get(TABLE_KEY);
                List<String> tableNames = new ArrayList<>();
                List<JsonNode> tableNodes = new ArrayList<>();
//                for (Map.Entry<String, JsonNode> entry : tableList.entrySet())
                for (Map.Entry<String, JsonNode> entry : tableList.entrySet()) {
                    tableNames.add(entry.getKey());
                    tableNodes.add(MAPPER.valueToTree(entry.getValue()));
                }
                return filterGroupedValues(tableNames, tableNodes, element, false);
            }
        } else if (categoryName.equals(DOCSTRING_KEY)) {
            if (noQuotedText) {
                return element.parentPhrase.getPhraseParsingMap().getPhraseMap().getAsList(DOCSTRING_KEY);
            } else {
                ObjectNode objectNode = (ObjectNode) getDocStringMap().get(DOCSTRING_KEY);
                return new ArrayList<>(Collections.singleton(objectNode));
            }
        }
        NodeMap phraseMap = getPhraseMap();

        switch (categoryName) {
            case ENTRY_KEY:
                JsonNode jsonNode = phraseMap.getRoot().get(ROW_KEY);
                ArrayList<JsonNode> list = new ArrayList<>();
                if (jsonNode instanceof ArrayNode arrayNode) {
                    arrayNode.forEach(list::add);
                } else {
                    phraseMap.getRoot().elements().forEachRemaining(list::add);
                }
                return list;
            case ROW_KEY:
                List<JsonNode> rowsArray = findRows(phraseMap.getRoot());
                List<String> keyList = new ArrayList<>();
                rowsArray.forEach(row -> keyList.add(row.values().next().get(0).asText()));
                return filterGroupedValues(keyList, rowsArray, element, false);
            case CELL_KEY:

                List<JsonNode> cellsArray = findCells(phraseMap.getRoot());
                List<String> cellKeys = new ArrayList<>();
                List<String> cellValues = new ArrayList<>();

                for (JsonNode cell : cellsArray) {
                    if (cell.isObject() && cell.size() == 1) {
                        cellKeys.add(cell.fieldNames().next());
                        JsonNode value = cell.elements().next();
                        cellValues.add(value == null || value.isNull() || value.isMissingNode() ? "" : value.asText(""));
                    } else {
                        cellKeys.add("");
                        cellValues.add(cell == null || cell.isNull() || cell.isMissingNode() ? "" : cell.asText(""));
                    }
                }

                List<Map<String, String>> keyedCellValues = new ArrayList<>(cellKeys.size());
                for (int i = 0; i < cellKeys.size(); i++) {
                    keyedCellValues.add(Map.of(cellKeys.get(i), cellValues.get(i)));
                }

                return filterGroupedValues(cellKeys, keyedCellValues, element, false);
            case HEADER_KEY:
                List<String> headers = findHeaders(phraseMap.root);
                return filterGroupedValues(headers, headers, element, false);
            case DATA_OBJECT_KEY:
                return Collections.singletonList(get(element.defaultText.toString()));
            default:
                return null;
        }

    }


    @Override
    public Object put(String key, Object value) {
        if (key == null || key.isBlank())
            throw new RuntimeException("key cannot be null or blank");

        if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
            Object oldValue = resolveFromVars(key);
            putVar(key.toLowerCase(), value);
            return oldValue;
        }
        Tokenized tokenized = new Tokenized(key);
        if (tokenized.isSingletonKey) {
            Object oldValue = getRootSingletonMap().get(tokenized);
            getRootSingletonMap().put(tokenized, value);
            return oldValue;
        }

        if (key.startsWith("`") && key.endsWith("`")) {
            key = key.substring(1, key.length() - 1);
            Object oldValue = getPrimaryRunMap().directGet(key);
            getPrimaryRunMap().directPut(key, value);
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
        maps.clear();
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
        return formatMaps(getMapsForResolution().stream());
    }

    public String toString(MapConfigurations.MapType... mapTypes) {
        if (mapTypes == null || mapTypes.length == 0) {
            return toString();
        }

        var allowed = EnumSet.copyOf(Arrays.asList(mapTypes));

        return formatMaps(getMapsForResolution().stream()
                .filter(m -> allowed.contains(m.getMapType())));
    }

    private String formatMaps(Stream<?> stream) {
        return "\n====\n" + stream
                .map(String::valueOf)
                .collect(Collectors.joining(System.lineSeparator()))
                + "\n---\n";
    }

}
