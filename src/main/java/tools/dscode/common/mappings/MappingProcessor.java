package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.LinkedListMultimap;
import io.cucumber.datatable.DataTable;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.mappings.queries.Tokenized;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementType;

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
import static io.cucumber.core.runner.util.DataUtils.CELL_KEY;
import static io.cucumber.core.runner.util.DataUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.DataUtils.HEADER_KEY;
import static io.cucumber.core.runner.util.DataUtils.ENTRY_KEY;
import static io.cucumber.core.runner.util.DataUtils.ROW_KEY;
import static io.cucumber.core.runner.util.DataUtils.TABLE_KEY;
import static tools.dscode.common.GlobalConstants.CLOSE_ANGLE_REPLACEMENT_SUB;
import static tools.dscode.common.GlobalConstants.CLOSE_BRACKET_SUB_A;
import static tools.dscode.common.GlobalConstants.CLOSE_BRACKET_SUB_B;
import static tools.dscode.common.GlobalConstants.CLOSE_CURLY_REPLACEMENT_SUB;
import static tools.dscode.common.GlobalConstants.MATCH_BREAK;
import static tools.dscode.common.GlobalConstants.OPEN_ANGLE_REPLACEMENT_SUB;
import static tools.dscode.common.GlobalConstants.OPEN_BRACKET_SUB_A;
import static tools.dscode.common.GlobalConstants.OPEN_BRACKET_SUB_B;
import static tools.dscode.common.GlobalConstants.OPEN_CURLY_REPLACEMENT_SUB;
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
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.treeparsing.parsedComponents.ElementMatch.getElementMatchesFromString;
import static tools.dscode.common.util.StringUtilities.decodeBackToText;
import static tools.dscode.common.util.StringUtilities.encodeToPlaceHolders;

import static tools.dscode.common.variables.RunVars.resolveFromVars;
import static tools.dscode.coredefinitions.GeneralSteps.getReturnValue;
import static tools.dscode.testengine.PKB_props.PKB_PREFIX;

public abstract class MappingProcessor implements Map<String, Object> {

    protected final LinkedListMultimap<MapConfigurations.MapType, NodeMap> maps = LinkedListMultimap.create();
    protected final List<MapConfigurations.MapType> keyOrder = new ArrayList<>();
    protected final List<MapConfigurations.MapType> singletonOrder = new ArrayList<>();

    public static ThreadLocal<NodeMap> runMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> singletonMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> overridesMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> defaultsMap = new ThreadLocal<>();
    public static ThreadLocal<DataMap> dataMap = new ThreadLocal<>();
//    public static ThreadLocal<NodeMap> dataTableMap = new ThreadLocal<>();
//    public static ThreadLocal<NodeMap> docStringMap = new ThreadLocal<>();

    public static void resetCommonMaps() {
        runMap.set(new NodeMap(MapConfigurations.MapType.RUN_MAP));
        singletonMap.set(new NodeMap(MapConfigurations.MapType.SINGLETON));
        overridesMap.set(new NodeMap(MapConfigurations.MapType.OVERRIDE_MAP));
        defaultsMap.set(new NodeMap(MapConfigurations.MapType.DEFAULT));
        dataMap.set(new DataMap());
//        dataTableMap.set(new NodeMap(MapConfigurations.MapType.DATATABLE));
//        docStringMap.set(new NodeMap(MapConfigurations.MapType.DOCSTRING));
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

//    public static NodeMap getDataTableMap() {
//        return dataTableMap.get();
//    }

//    public static NodeMap getDocStringMap() {
//        return docStringMap.get();
//    }


    public MappingProcessor() {
        // Defensive copy to make key order immutable
//        addMaps(GLOBALS, runMap.get(), singletonMap.get(), overridesMap.get(), defaultsMap.get(), dataTableMap.get(), docStringMap.get());
        addMaps(GLOBALS, runMap.get(), singletonMap.get(), overridesMap.get(), defaultsMap.get());

        this.keyOrder.addAll(Arrays.asList(
                MapConfigurations.MapType.OVERRIDE_MAP,
                MapConfigurations.MapType.PHRASE_MAP,
                MapConfigurations.MapType.STEP_MAP,
//                MapConfigurations.MapType.DATATABLE,
//                MapConfigurations.MapType.DOCSTRING,
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
//                MapConfigurations.MapType.DATATABLE,
//                MapConfigurations.MapType.DOCSTRING,
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
//        boolean log = (nodes.stream().anyMatch(m -> m.getMapType() == MapConfigurations.MapType.STEP_MAP));
//        if (log) {
//        }
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
//        boolean log = (nodes.stream().anyMatch(m -> m.getMapType() == MapConfigurations.MapType.STEP_MAP));
//        if (log) {
//        }
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

    private static final String DEFAULT_OPEN_BOOKEND = "<";
    private static final String DEFAULT_CLOSE_BOOKEND = ">";
    private static final String DEFAULT_OPEN_EXPRESSION_BOOKEND = "<{";
    private static final String DEFAULT_CLOSE_EXPRESSION_BOOKEND = "}>";

    private static final String TILDE_OPEN_BOOKEND = "~<~";
    private static final String TILDE_CLOSE_BOOKEND = "~>~";
    private static final String TILDE_OPEN_EXPRESSION_BOOKEND = "~<~{";
    private static final String TILDE_CLOSE_EXPRESSION_BOOKEND = "}~>~";

    private static final Bookends DEFAULT_BOOKENDS = new Bookends(
            DEFAULT_OPEN_BOOKEND,
            DEFAULT_CLOSE_BOOKEND,
            DEFAULT_OPEN_EXPRESSION_BOOKEND,
            DEFAULT_CLOSE_EXPRESSION_BOOKEND);

    private static final Bookends TILDE_BOOKENDS = new Bookends(
            TILDE_OPEN_BOOKEND,
            TILDE_CLOSE_BOOKEND,
            TILDE_OPEN_EXPRESSION_BOOKEND,
            TILDE_CLOSE_EXPRESSION_BOOKEND);

    private record Bookends(
            String open,
            String close,
            String expressionOpen,
            String expressionClose
    ) {
        String wrap(String key) {
            return open + key + close;
        }
    }

    /**
     * Private-use placeholders used to temporarily collapse active bookends
     * into one internal syntax.  The resolver logic after this point does not
     * need to know whether the original input used default or tilde bookends.
     */
    private static final String INTERNAL_OPEN_BOOKEND_SUB = "\uE000";
    private static final String INTERNAL_CLOSE_BOOKEND_SUB = "\uE001";
    private static final String INTERNAL_MAP_BOOKEND_FLAG = "\uE002";
    private static final String INTERNAL_EXPRESSION_BOOKEND_FLAG = "\uE003";

    private static final String INTERNAL_MAP_OPEN = INTERNAL_OPEN_BOOKEND_SUB + INTERNAL_MAP_BOOKEND_FLAG;
    private static final String INTERNAL_MAP_CLOSE = INTERNAL_MAP_BOOKEND_FLAG + INTERNAL_CLOSE_BOOKEND_SUB;
    private static final String INTERNAL_EXPRESSION_OPEN = INTERNAL_OPEN_BOOKEND_SUB + INTERNAL_EXPRESSION_BOOKEND_FLAG;
    private static final String INTERNAL_EXPRESSION_CLOSE = INTERNAL_EXPRESSION_BOOKEND_FLAG + INTERNAL_CLOSE_BOOKEND_SUB;

    /*
     * Important:
     * Do not use Pattern.MULTILINE here.
     *
     * Matcher.find() already searches through the whole input, including multi-line input.
     * These patterns intentionally exclude \r and \n inside the matched placeholder body,
     * so a placeholder may be found on any line, but may not span lines.
     *
     * The internal flags are also excluded so a single placeholder match cannot contain
     * another normalized map or expression placeholder.
     */
    private static final String INTERNAL_BODY_EXCLUSIONS =
            "\\r\\n" + INTERNAL_MAP_BOOKEND_FLAG + INTERNAL_EXPRESSION_BOOKEND_FLAG;

    private static final Pattern MAP_PLACEHOLDER = Pattern.compile(
            Pattern.quote(INTERNAL_MAP_OPEN)
                    + "([^" + INTERNAL_BODY_EXCLUSIONS + "]+)"
                    + Pattern.quote(INTERNAL_MAP_CLOSE)
    );

    private static final Pattern EXPRESSION = Pattern.compile(
            Pattern.quote(INTERNAL_EXPRESSION_OPEN)
                    + "([^" + INTERNAL_BODY_EXCLUSIONS + "]+)"
                    + Pattern.quote(INTERNAL_EXPRESSION_CLOSE)
    );

    private static final Pattern UNRESOLVED_OPTIONAL_PLACEHOLDER = Pattern.compile(
            Pattern.quote(INTERNAL_MAP_OPEN)
                    + "\\?[^" + INTERNAL_BODY_EXCLUSIONS + "]+?"
                    + Pattern.quote(INTERNAL_MAP_CLOSE)
    );

    public String resolveWholeText(String input) {
        return resolveWholeText(input, usesTildeBookends(input) ? TILDE_BOOKENDS : DEFAULT_BOOKENDS);
    }

    private String resolveWholeText(String input, Bookends bookends) {

        QuoteParser parsedObj = new QuoteParser(input);
//        try {
        // Resolve quoted substring values first.
        // This lets expression bookends restore already-resolved quoted values.
        for (var e : parsedObj.entrySetWithoutTripleSingle()) {
            parsedObj.put(e.getKey(), resolveAll(e.getValue(), parsedObj, bookends));
        }

        // Now resolve the outer/masked text.
        // If resolveExpression restores quoted placeholders, they are already resolved.
        parsedObj.setMasked(resolveAll(parsedObj.masked(), parsedObj, bookends));
        String resolvedText = parsedObj.restore();
        logTrace("Resolved: '" + input + "' -> '" + resolvedText + "'");
        return resolvedText;

    }

    private static boolean usesTildeBookends(String input) {
        return input != null && !input.equals(normalizeBookends(input, TILDE_BOOKENDS));
    }

    private String resolveAll(String input, QuoteParser parsedObj, Bookends bookends) {
        boolean isDirectoryPath = input.startsWith("</");
        try {
            String originalInput;
            do {
                input = normalizeBookends(input, bookends);
                originalInput = input;
                String prev;
                do {
                    prev = input;
                    if (input.contains(INTERNAL_MAP_OPEN)) {
                        input = resolveByMap(input, parsedObj, bookends);
                    }
                    if (!isDirectoryPath && input.contains(INTERNAL_EXPRESSION_OPEN)) {
                        input = resolveExpression(input, parsedObj, bookends);
                    }
                    input = normalizeBookends(input, bookends);
                } while (!input.equals(prev));
                input = UNRESOLVED_OPTIONAL_PLACEHOLDER.matcher(input).replaceAll("");
            } while (!input.equals(originalInput));
            return restoreBookends(decodeBackToText(input.replaceAll(MATCH_BREAK, "")), bookends);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException("Could not resolve '" + input + "' due to '" + t.getMessage() + "'", t);
        }
    }

    private String resolveByMap(String s, QuoteParser parsedObj, Bookends bookends) {
        String key = null;
        try {
            Matcher m = MAP_PLACEHOLDER.matcher(s);
            StringBuffer sb = new StringBuffer();
            Object replacement = null;

            while (m.find()) {
                key = m.group(1);

                if (key.startsWith("&")) {
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    replacement = getReturnValue(key.substring(1));
                    break;

                }
                if (key.startsWith("$")) {
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    replacement = getRunningStep().resolveStepFromString(key.substring(1));
                    break;
                }
                if (key.contains(MATCH_BREAK)) {
                    continue;
                }
                if (key.contains("&&") || key.contains("||")) {
                    replacement = bookends.wrap(key);
                    break;
                }
                replacement = get(key);
                if (replacement != null) {
                    logTrace("'" + bookends.wrap(key) + "' -> '" + replacement + "'");
                    break;
                }
            }

            if (replacement == null)
                return s;

            String stringReplacement = getStringValue(replacement);

            String wrappedKey = bookends.wrap(key);

            if (stringReplacement.contains(bookends.open()) && !key.contains(MATCH_BREAK) && stringReplacement.contains(wrappedKey)) {
                stringReplacement = stringReplacement.replace(wrappedKey, bookends.open() + MATCH_BREAK + key + bookends.close());
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(stringReplacement));
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable t) {
            throw new RuntimeException("Could not resolve by map '" + s + "' due to: " + t.getMessage(), t);
        }
    }

    private String resolveExpression(String s, QuoteParser parsedObj, Bookends bookends) {
        Matcher m = EXPRESSION.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String key = m.group(1).trim();
            key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
            String repl = key.endsWith("?")
                    ? String.valueOf(evalToBoolean(key.substring(0, key.length() - 1), this))
                    : String.valueOf(eval(key, this));
            logTrace("'" + bookends.expressionOpen() + key + bookends.expressionClose() + "' -> '" + repl + "'");
            m.appendReplacement(sb, repl == null ? m.group(0) : Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String normalizeBookends(String input, Bookends bookends) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return normalizeMapBookends(normalizeExpressionBookends(input, bookends), bookends);
    }

    private static String normalizeExpressionBookends(String input, Bookends bookends) {
        return normalizeBalancedBookends(
                input,
                bookends.expressionOpen(),
                bookends.expressionClose(),
                INTERNAL_EXPRESSION_OPEN,
                INTERNAL_EXPRESSION_CLOSE,
                false);
    }

    private static String normalizeMapBookends(String input, Bookends bookends) {
        return normalizeBalancedBookends(
                input,
                bookends.open(),
                bookends.close(),
                INTERNAL_MAP_OPEN,
                INTERNAL_MAP_CLOSE,
                true);
    }

    private static String normalizeBalancedBookends(
            String input,
            String open,
            String close,
            String internalOpen,
            String internalClose,
            boolean mapBookends
    ) {
        StringBuilder sb = null;
        int copyFrom = 0;
        int searchFrom = 0;

        while (true) {
            int openIndex = input.indexOf(open, searchFrom);
            if (openIndex < 0) {
                break;
            }

            int bodyStart = openIndex + open.length();
            int closeIndex = input.indexOf(close, bodyStart);
            if (closeIndex < 0) {
                break;
            }

            String body = input.substring(bodyStart, closeIndex);
            if (isValidBookendBody(body, open, close, mapBookends)) {
                if (sb == null) {
                    sb = new StringBuilder(input.length());
                }
                sb.append(input, copyFrom, openIndex)
                        .append(internalOpen)
                        .append(body)
                        .append(internalClose);
                copyFrom = closeIndex + close.length();
                searchFrom = copyFrom;
            } else {
                searchFrom = bodyStart;
            }
        }

        if (sb == null) {
            return input;
        }
        return sb.append(input, copyFrom, input.length()).toString();
    }

    private static boolean isValidBookendBody(String body, String open, String close, boolean mapBookends) {
        if (body.isEmpty()
                || containsLineBreak(body)
                || body.contains(INTERNAL_MAP_BOOKEND_FLAG)
                || body.contains(INTERNAL_EXPRESSION_BOOKEND_FLAG)
                || body.contains(open)
                || body.contains(close)) {
            return false;
        }
        if (!mapBookends) {
            return true;
        }
        char first = body.charAt(0);
        char last = body.charAt(body.length() - 1);
        return first != '='
                && !Character.isWhitespace(first)
                && !Character.isWhitespace(last)
                && body.indexOf('<') < 0
                && body.indexOf('>') < 0
                && body.indexOf('{') < 0
                && body.indexOf('}') < 0;
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static String restoreBookends(String input, Bookends bookends) {
        return input
                .replace(INTERNAL_MAP_OPEN, bookends.open())
                .replace(INTERNAL_MAP_CLOSE, bookends.close())
                .replace(INTERNAL_EXPRESSION_OPEN, bookends.expressionOpen())
                .replace(INTERNAL_EXPRESSION_CLOSE, bookends.expressionClose());
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
        logTrace("putVar '" + key + "' -> '" + value + "'");
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
        Object returnReplacement = null;

        while (true) {
            for (NodeMap map : (tokenized.isSingletonKey ? getMapsForSingletonResolution() : getMapsForResolution())) {
                if (map == null)
                    continue;
                Object replacement = null;
                if (directGet) {
                    replacement = map.directGet(key);
//                    System.out.println((replacement == null ? "null" : replacement.getClass().getName()));
                    if (replacement instanceof ArrayNode arrayNode) {
                        replacement = arrayNode.isEmpty() ? null : arrayNode.get(arrayNode.size() - 1);
                    }
                } else {
                    replacement = map.get(tokenized);
                }

                if (replacement != null) {
                    if (replacement instanceof String replacementString && replacementString.isEmpty() && !key.startsWith("?")) {
                        key = "?" + key;
                        directGet = true;
                        returnReplacement = replacement;
                        continue;
                    }
                    returnReplacement = replacement;
                    break;
                }
            }
            if (returnReplacement != null || key.startsWith("?"))
                break;
            key = "?" + key;
            directGet = true;
        }
        return returnReplacement;
    }

//    public List<?> get(ElementMatch element) {
//        String categoryName = element.category.replaceFirst("(?i:s)$", "");
//        boolean noQuotedText = element.defaultText == null || element.defaultText.isNullOrBlank();
//        if (categoryName.equals(TABLE_KEY)) {
//            if (noQuotedText) {
//                return element.parentPhrase.getPhraseParsingMap().getPhraseMap().getAsList(TABLE_KEY);
//            } else {
//                List<JsonNode> tableList = getDataTableMap().getAsList(TABLE_KEY);
//                List<String> tableNames = new ArrayList<>();
//                List<JsonNode> tableNodes = new ArrayList<>();
//                for (JsonNode jsonNode : tableList) {
//                    if (jsonNode instanceof ObjectNode objectNode) {
//                        String tableName = objectNode.fieldNames().next();
//                        tableNames.add(tableName);
//                        tableNodes.add(MAPPER.valueToTree(objectNode.get(tableName)));
//                    }
//                }
//                return filterGroupedValues(tableNames, tableNodes, element, false);
//            }
//        } else if (categoryName.equals(DOCSTRING_KEY)) {
//            if (noQuotedText) {
//                return element.parentPhrase.getPhraseParsingMap().getPhraseMap().getAsList(DOCSTRING_KEY);
//            } else {
//                ObjectNode objectNode = (ObjectNode) getDocStringMap().get(DOCSTRING_KEY);
//                return new ArrayList<>(Collections.singleton(objectNode));
//            }
//        }
//        NodeMap phraseMap = getPhraseMap();
//
//        switch (categoryName) {
//            case ENTRY_KEY:
//                JsonNode jsonNode = phraseMap.getRoot().get(ROW_KEY);
//                ArrayList<JsonNode> list = new ArrayList<>();
//                if (jsonNode instanceof ArrayNode arrayNode) {
//                    arrayNode.forEach(list::add);
//                } else {
//                    phraseMap.getRoot().elements().forEachRemaining(list::add);
//                }
//                return list;
//            case ROW_KEY:
//                List<JsonNode> rowsArray = findRows(phraseMap.getRoot());
//                List<String> keyList = new ArrayList<>();
//                rowsArray.forEach(row -> keyList.add(row.values().next().get(0).asText()));
//                return filterGroupedValues(keyList, rowsArray, element, false);
//            case CELL_KEY:
//
//                List<JsonNode> cellsArray = findCells(phraseMap.getRoot());
//                List<String> cellKeys = new ArrayList<>();
//                List<String> cellValues = new ArrayList<>();
//
//                for (JsonNode cell : cellsArray) {
//                    if (cell.isObject() && cell.size() == 1) {
//                        cellKeys.add(cell.fieldNames().next());
//                        JsonNode value = cell.elements().next();
//                        cellValues.add(value == null || value.isNull() || value.isMissingNode() ? "" : value.asText(""));
//                    } else {
//                        cellKeys.add("");
//                        cellValues.add(cell == null || cell.isNull() || cell.isMissingNode() ? "" : cell.asText(""));
//                    }
//                }
//
//                List<Map<String, String>> keyedCellValues = new ArrayList<>(cellKeys.size());
//                for (int i = 0; i < cellKeys.size(); i++) {
//                    keyedCellValues.add(Map.of(cellKeys.get(i), cellValues.get(i)));
//                }
//
//                return filterGroupedValues(cellKeys, keyedCellValues, element, false);
//            case HEADER_KEY:
//                List<String> headers = findHeaders(phraseMap.root);
//                return filterGroupedValues(headers, headers, element, false);
//            case DATA_OBJECT_KEY:
//                return Collections.singletonList(get(element.defaultText.toString()));
//            default:
//                return null;
//        }
//
//    }


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


    public String resolveWholeTextWithAlternateBookends(
            String input,
            String openAngleReplacement,
            String closeAngleReplacement,
            String openCurlyReplacement,
            String closeCurlyReplacement
    ) {
        if (input == null) {
            return null;
        }

        boolean replaceOpenAngle = hasText(openAngleReplacement);
        boolean replaceCloseAngle = hasText(closeAngleReplacement);
        boolean replaceOpenCurly = hasText(openCurlyReplacement);
        boolean replaceCloseCurly = hasText(closeCurlyReplacement);

        String prepared = input;

        // 1. Protect the alternate bookend strings first.
        // Longer expression bookends must be protected before their shorter
        // angle-bookend prefixes/suffixes, e.g. ~<~{ before ~<~.
        if (replaceOpenCurly) {
            prepared = prepared.replace(openCurlyReplacement, OPEN_CURLY_REPLACEMENT_SUB);
        }
        if (replaceCloseCurly) {
            prepared = prepared.replace(closeCurlyReplacement, CLOSE_CURLY_REPLACEMENT_SUB);
        }
        if (replaceOpenAngle) {
            prepared = prepared.replace(openAngleReplacement, OPEN_ANGLE_REPLACEMENT_SUB);
        }
        if (replaceCloseAngle) {
            prepared = prepared.replace(closeAngleReplacement, CLOSE_ANGLE_REPLACEMENT_SUB);
        }

        // 2. Protect literal real delimiters.
        if (replaceOpenAngle) {
            prepared = prepared.replace("<", OPEN_BRACKET_SUB_A);
        }
        if (replaceCloseAngle) {
            prepared = prepared.replace(">", CLOSE_BRACKET_SUB_A);
        }
        if (replaceOpenCurly) {
            prepared = prepared.replace("{", OPEN_BRACKET_SUB_B);
        }
        if (replaceCloseCurly) {
            prepared = prepared.replace("}", CLOSE_BRACKET_SUB_B);
        }

        // 3. Turn alternate-bookend placeholders into real delimiters.
        // Alternate curly replacements now mean alternate expression bookends.
        // They are converted to the paired <{ and }> syntax, not raw { and }.
        if (replaceOpenCurly) {
            prepared = prepared.replace(OPEN_CURLY_REPLACEMENT_SUB, "<{");
        }
        if (replaceCloseCurly) {
            prepared = prepared.replace(CLOSE_CURLY_REPLACEMENT_SUB, "}>");
        }
        if (replaceOpenAngle) {
            prepared = prepared.replace(OPEN_ANGLE_REPLACEMENT_SUB, "<");
        }
        if (replaceCloseAngle) {
            prepared = prepared.replace(CLOSE_ANGLE_REPLACEMENT_SUB, ">");
        }

        // 4. Resolve normally.
        String resolved = resolveWholeText(prepared, DEFAULT_BOOKENDS);

        // 5. Restore any unresolved alternate placeholders back to their original syntax.
        // This matters if something survives resolution unchanged.
        if (replaceOpenCurly) {
            resolved = resolved.replace("<{", openCurlyReplacement);
        }
        if (replaceCloseCurly) {
            resolved = resolved.replace("}>", closeCurlyReplacement);
        }
        if (replaceOpenAngle) {
            resolved = resolved.replace(OPEN_ANGLE_REPLACEMENT_SUB, openAngleReplacement);
        }
        if (replaceCloseAngle) {
            resolved = resolved.replace(CLOSE_ANGLE_REPLACEMENT_SUB, closeAngleReplacement);
        }
        if (replaceOpenCurly) {
            resolved = resolved.replace(OPEN_CURLY_REPLACEMENT_SUB, openCurlyReplacement);
        }
        if (replaceCloseCurly) {
            resolved = resolved.replace(CLOSE_CURLY_REPLACEMENT_SUB, closeCurlyReplacement);
        }

        // 6. Restore protected literal delimiters.
        if (replaceOpenAngle) {
            resolved = resolved.replace(OPEN_BRACKET_SUB_A, "<");
        }
        if (replaceCloseAngle) {
            resolved = resolved.replace(CLOSE_BRACKET_SUB_A, ">");
        }
        if (replaceOpenCurly) {
            resolved = resolved.replace(OPEN_BRACKET_SUB_B, "{");
        }
        if (replaceCloseCurly) {
            resolved = resolved.replace(CLOSE_BRACKET_SUB_B, "}");
        }

        return resolved;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }


    public static DataMap getDataMap() {
        return dataMap.get();
    }

    public static JsonNode getFromDataMap(String key) {
        return dataMap.get().getData(key);
    }

    public static JsonNode getFromDataMap(ElementMatch elementMatch) {
        return dataMap.get().getData(elementMatch);
    }

    public static void setInDataMap(ElementMatch elementMatch, DataTable dataTable) {
        dataMap.get().setData(elementMatch, dataTable);
    }




    public static void setInDataMap(ElementMatch elementMatch, JsonNode jsonNode) {
        dataMap.get().setData(elementMatch, jsonNode);
    }

    public static void setInDataMap(String key, JsonNode jsonNode) {
        dataMap.get().setData(key, jsonNode);
    }

//    public static ValueWrapper dataMapGet(ElementMatch elementMatch) {
//        Object object = dataMap.get().get(elementMatch.getKey());
//        if(object instanceof ValueWrapper valueWrapper)
//            return valueWrapper;
//        return ValueWrapper.createValueWrapper(object , ValueWrapper.ValueTypes.DATA);
//    }
//
//    public static ValueWrapper dataMapGet(String key) {
//        List<ElementMatch> elementMatches = new ArrayList<>();
//        try {
//            elementMatches.addAll(getElementMatchesFromString(key));
//        } catch (Exception e) {
//            // ignore
//        }
//        if (!elementMatches.isEmpty() && elementMatches.getFirst().elementTypes.contains(ElementType.DATA_TYPE))
//            return dataMapGet(elementMatches.getFirst());
//
//        Object object =  dataMap.get().get(key);
//        if(object instanceof ValueWrapper valueWrapper)
//            return valueWrapper;
//        return ValueWrapper.createValueWrapper(object , ValueWrapper.ValueTypes.DATA);
//    }
//
//
//    public static void dataMapPut(String key, Object value) {
//        ValueWrapper valueWrapper = value instanceof ValueWrapper valueWrapper1 ? valueWrapper1 : ValueWrapper.createValueWrapper(value , ValueWrapper.ValueTypes.DATA);
//
//        List<ElementMatch> elementMatches = new ArrayList<>();
//        try {
//            elementMatches.addAll(getElementMatchesFromString(key));
//        } catch (Exception e) {
//            // ignore
//        }
//        if (!elementMatches.isEmpty() && elementMatches.getFirst().elementTypes.contains(ElementType.DATA_TYPE))
//            dataMapPut(elementMatches.getFirst(), valueWrapper);
//        else
//            dataMap.get().put(key, valueWrapper);
//    }
//
//    public static void dataMapPut(ElementMatch elementMatch, Object value) {
//        ValueWrapper valueWrapper = value instanceof ValueWrapper valueWrapper1 ? valueWrapper1 : ValueWrapper.createValueWrapper(value , ValueWrapper.ValueTypes.DATA);
//        dataMap.get().put(elementMatch.getKey(), value);
//    }


}
