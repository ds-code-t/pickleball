/*
 * MappingProcessor delimiter rewrite — revision v6
 *
 * Supports both <...> and ~[~...~]~ as built-in default bookend styles,
 * while retaining custom delimiters, comparison handling, file:, and ~unquote.
 */
package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.LinkedListMultimap;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.mappings.queries.Tokenized;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.util.TableUtils.CELL_KEY;
import static io.cucumber.core.runner.util.TableUtils.DATA_OBJECT_KEY;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.ENTRY_KEY;
import static io.cucumber.core.runner.util.TableUtils.HEADER_KEY;
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
import static tools.dscode.common.mappings.NodeMap.getNodeMap;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.util.StringUtilities.decodeBackToText;
import static tools.dscode.common.util.StringUtilities.encodeToPlaceHolders;
import static tools.dscode.common.variables.RunVars.resolveFromVars;
import static tools.dscode.coredefinitions.DataTableDefinitions.dataTableToJsonNode;
import static tools.dscode.coredefinitions.DataTableDefinitions.jsonNodeToDataTable;
import static tools.dscode.coredefinitions.DocStringDefinitions.docStringtoJsonNode;
import static tools.dscode.coredefinitions.GeneralSteps.getReturnValue;
import static tools.dscode.coredefinitions.ModularScenarios.getScenarioMarkerData;
import static tools.dscode.testengine.PKB_props.PKB_PREFIX;

public abstract class MappingProcessor implements Map<String, Object> {
    private static final String FILE_REFERENCE_PREFIX = "file:";
    private static final String DATA_REFERENCE_PREFIX = "data:";

    protected final LinkedListMultimap<MapConfigurations.MapType, NodeMap> maps =
            LinkedListMultimap.create();
    protected final List<MapConfigurations.MapType> keyOrder = new ArrayList<>();
    protected final List<MapConfigurations.MapType> singletonOrder = new ArrayList<>();

    public static ThreadLocal<NodeMap> runMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> singletonMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> overridesMap = new ThreadLocal<>();
    public static ThreadLocal<NodeMap> defaultsMap = new ThreadLocal<>();

    public static void resetCommonMaps() {
        runMap.set(new NodeMap(MapConfigurations.MapType.RUN_MAP));
        singletonMap.set(new NodeMap(MapConfigurations.MapType.SINGLETON));
        overridesMap.set(new NodeMap(MapConfigurations.MapType.OVERRIDE_MAP));
        defaultsMap.set(new NodeMap(MapConfigurations.MapType.DEFAULT));
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

    public MappingProcessor() {
        addMaps(GLOBALS, runMap.get(), singletonMap.get(), overridesMap.get(), defaultsMap.get());
        keyOrder.addAll(Arrays.asList(
                MapConfigurations.MapType.OVERRIDE_MAP,
                MapConfigurations.MapType.PHRASE_MAP,
                MapConfigurations.MapType.STEP_MAP,
                MapConfigurations.MapType.PASSED_MAP,
                MapConfigurations.MapType.EXAMPLE_MAP,
                MapConfigurations.MapType.RUN_MAP,
                MapConfigurations.MapType.SINGLETON,
                MapConfigurations.MapType.GLOBAL_NODE,
                MapConfigurations.MapType.DEFAULT));
        singletonOrder.addAll(Arrays.asList(
                MapConfigurations.MapType.OVERRIDE_MAP,
                MapConfigurations.MapType.SINGLETON,
                MapConfigurations.MapType.PHRASE_MAP,
                MapConfigurations.MapType.STEP_MAP,
                MapConfigurations.MapType.PASSED_MAP,
                MapConfigurations.MapType.EXAMPLE_MAP,
                MapConfigurations.MapType.RUN_MAP,
                MapConfigurations.MapType.GLOBAL_NODE,
                MapConfigurations.MapType.DEFAULT));
    }

    protected MappingProcessor(NodeMap nodeMap) {
        addMaps(nodeMap);
        keyOrder.add(nodeMap.getMapType());
        singletonOrder.add(nodeMap.getMapType());
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
        for (MapConfigurations.DataSource ignored : dataSources) {
            maps.values().forEach(NodeMap::getDataSources);
        }
    }

    public void removeMaps(MapConfigurations.DataSource... dataSources) {
        List<NodeMap> nodeMapList = new ArrayList<>();
        for (MapConfigurations.DataSource dataSource : dataSources) {
            nodeMapList.addAll(maps.values().stream()
                    .filter(nodeMap -> nodeMap.getDataSources().contains(dataSource))
                    .toList());
        }
        removeMaps(nodeMapList);
    }

    public void removeMaps(MapConfigurations.MapType... mapTypes) {
        List<NodeMap> nodeMapList = new ArrayList<>();
        for (MapConfigurations.MapType mapType : mapTypes) {
            nodeMapList.addAll(maps.values().stream()
                    .filter(nodeMap -> nodeMap.getMapType() == mapType)
                    .toList());
        }
        removeMaps(nodeMapList);
    }

    public void removeMaps(List<NodeMap> nodes) {
        for (NodeMap node : nodes) {
            maps.get(node.getMapType()).remove(node);
        }
    }

    public void replaceMaps(NodeMap... nodes) {
        replaceMaps(Arrays.stream(nodes).toList());
    }

    public void replaceMaps(List<NodeMap> nodes) {
        if (nodes == null) {
            return;
        }
        nodes.forEach(node -> clearMapType(node.getMapType()));
        nodes.forEach(node -> maps.put(node.getMapType(), node));
    }

    public void addMaps(NodeMap... nodes) {
        addMaps(Arrays.stream(nodes).toList());
    }

    public void addMaps(List<NodeMap> nodes) {
        if (nodes != null) {
            nodes.stream().filter(java.util.Objects::nonNull)
                    .forEach(node -> maps.put(node.getMapType(), node));
        }
    }

    public void addMapsToStart(NodeMap... nodes) {
        addMapsToStart(Arrays.stream(nodes).toList());
    }

    public void addMapsToStart(List<NodeMap> nodes) {
        for (List<NodeMap> list : groupByMapType(nodes)) {
            if (!list.isEmpty()) {
                maps.get(list.getFirst().getMapType()).addAll(0, list);
            }
        }
    }

    public static List<List<NodeMap>> groupByMapType(List<NodeMap> nodes) {
        List<List<NodeMap>> grouped = new ArrayList<>();
        for (NodeMap node : nodes) {
            if (grouped.isEmpty()
                    || grouped.getLast().getFirst().getMapType() != node.getMapType()) {
                grouped.add(new ArrayList<>());
            }
            grouped.getLast().add(node);
        }
        return grouped;
    }

    private void clearMapType(MapConfigurations.MapType key) {
        maps.removeAll(key);
    }

    public List<NodeMap> getMapsForResolution() {
        List<NodeMap> out = new ArrayList<>();
        keyOrder.forEach(key -> out.addAll(maps.get(key)));
        return out;
    }

    public List<NodeMap> getMapsForResolution(String mapTypes) {
        List<String> segments = mapTypes == null
                ? List.of()
                : Arrays.stream(mapTypes.split(","))
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .toList();
        if (segments.isEmpty()) {
            return getMapsForResolution();
        }
        List<NodeMap> nodeMaps = new ArrayList<>(segments.size());
        segments.forEach(segment -> nodeMaps.add(getNodeMap(segment)));
        return nodeMaps;
    }

    public List<NodeMap> getMapsForSingletonResolution() {
        List<NodeMap> out = new ArrayList<>();
        for (MapConfigurations.MapType key : singletonOrder) {
            List<NodeMap> mapList = maps.get(key);
            out.addAll(key == MapConfigurations.MapType.STEP_MAP ? mapList.reversed() : mapList);
        }
        return out;
    }

    public List<MapConfigurations.MapType> keyOrder() {
        return keyOrder;
    }

    private static final String DEFAULT_OPEN_BOOKEND = "<";
    private static final String DEFAULT_CLOSE_BOOKEND = ">";
    private static final String DEFAULT_OPEN_EXPRESSION_COMPONENT = "{";
    private static final String DEFAULT_CLOSE_EXPRESSION_COMPONENT = "}";
    private static final String SECONDARY_DEFAULT_OPEN_BOOKEND = "~[~";
    private static final String SECONDARY_DEFAULT_CLOSE_BOOKEND = "~]~";
    private static final Pattern XML_CLOSING_ELEMENT = Pattern.compile(
            "</\\s*[A-Za-z_][A-Za-z0-9_.-]*\\s*>");
    private static final Pattern XML_SELF_CLOSING_ELEMENT = Pattern.compile(
            "<\\s*[A-Za-z_][A-Za-z0-9_.-]*(?:\\s+[^<>]*?)?/\\s*>");

    private record Bookends(
            String open, String close, String expressionOpen, String expressionClose
    ) {
        String wrap(String key) {
            return open + key + close;
        }
    }

    private static final String INTERNAL_OPEN_BOOKEND_SUB = "\uE000";
    private static final String INTERNAL_CLOSE_BOOKEND_SUB = "\uE001";
    private static final String INTERNAL_MAP_BOOKEND_FLAG = "\uE002";
    private static final String INTERNAL_EXPRESSION_BOOKEND_FLAG = "\uE003";
    private static final String INTERNAL_MAP_OPEN =
            INTERNAL_OPEN_BOOKEND_SUB + INTERNAL_MAP_BOOKEND_FLAG;
    private static final String INTERNAL_MAP_CLOSE =
            INTERNAL_MAP_BOOKEND_FLAG + INTERNAL_CLOSE_BOOKEND_SUB;
    private static final String INTERNAL_EXPRESSION_OPEN =
            INTERNAL_OPEN_BOOKEND_SUB + INTERNAL_EXPRESSION_BOOKEND_FLAG;
    private static final String INTERNAL_EXPRESSION_CLOSE =
            INTERNAL_EXPRESSION_BOOKEND_FLAG + INTERNAL_CLOSE_BOOKEND_SUB;
    private static final String UNQUOTE_SUFFIX = "~unquote";
    private static final String INTERNAL_UNQUOTE_OPEN = "\uE004";
    private static final String INTERNAL_UNQUOTE_CLOSE = "\uE005";
    private static final String MAP_BODY =
            "[^\\r\\n" + INTERNAL_MAP_BOOKEND_FLAG + INTERNAL_EXPRESSION_BOOKEND_FLAG + "]+";
    private static final String EXPRESSION_BODY =
            "[^\\r\\n" + INTERNAL_EXPRESSION_BOOKEND_FLAG + "]+";
    private static final Pattern MAP_PLACEHOLDER = Pattern.compile(
            Pattern.quote(INTERNAL_MAP_OPEN)
                    + "(" + MAP_BODY + ")"
                    + Pattern.quote(INTERNAL_MAP_CLOSE));
    private static final Pattern EXPRESSION = Pattern.compile(
            Pattern.quote(INTERNAL_EXPRESSION_OPEN)
                    + "(" + EXPRESSION_BODY + ")"
                    + Pattern.quote(INTERNAL_EXPRESSION_CLOSE));
    private static final Pattern UNRESOLVED_OPTIONAL_PLACEHOLDER = Pattern.compile(
            Pattern.quote(INTERNAL_MAP_OPEN)
                    + "\\?" + MAP_BODY
                    + Pattern.quote(INTERNAL_MAP_CLOSE));

    public String resolveWholeText(String input, String... delimiterReplacements) {
        return resolveWholeText(input, true, delimiterReplacements);
    }

    public String resolveWholeText(
            String input,
            boolean resolveEvaluations,
            String... delimiterReplacements
    ) {
        return (String) resolveWhole(
                input, resolveEvaluations, false, delimiterReplacements);
    }

    public Object resolveWholeValue(String input, String... delimiterReplacements) {
        return resolveWholeValue(input, true, delimiterReplacements);
    }

    public Object resolveWholeValue(
            String input,
            boolean resolveEvaluations,
            String... delimiterReplacements
    ) {
        return resolveWhole(
                input, resolveEvaluations, true, delimiterReplacements);
    }

    private Object resolveWhole(
            String input,
            boolean resolveEvaluations,
            boolean preserveWholeObject,
            String... delimiterReplacements
    ) {
        validateDelimiterReplacements(delimiterReplacements);
        Object resolvedValue;
        if (usesDualDefaultOuterBookends(delimiterReplacements) && looksLikeXml(input)) {
            resolvedValue = resolveUsingBookends(
                    input,
                    createBookendsForOuter(
                            SECONDARY_DEFAULT_OPEN_BOOKEND,
                            SECONDARY_DEFAULT_CLOSE_BOOKEND,
                            delimiterReplacements),
                    resolveEvaluations,
                    preserveWholeObject);
        } else if (usesDualDefaultOuterBookends(delimiterReplacements)) {
            resolvedValue = resolveUntilStable(
                    input,
                    resolveEvaluations,
                    preserveWholeObject,
                    createBookendsForOuter(
                            DEFAULT_OPEN_BOOKEND,
                            DEFAULT_CLOSE_BOOKEND,
                            delimiterReplacements),
                    createBookendsForOuter(
                            SECONDARY_DEFAULT_OPEN_BOOKEND,
                            SECONDARY_DEFAULT_CLOSE_BOOKEND,
                            delimiterReplacements));
        } else {
            resolvedValue = resolveUsingBookends(
                    input,
                    createBookends(delimiterReplacements),
                    resolveEvaluations,
                    preserveWholeObject);
        }
        logTrace("Resolved: '" + input + "' -> '" + resolvedValue + "'");
        return resolvedValue;
    }

    private static boolean usesDualDefaultOuterBookends(String[] replacements) {
        boolean defaultOpen = replacements == null
                || replacements.length < 1
                || replacements[0] == null;
        boolean defaultClose = replacements == null
                || replacements.length < 2
                || replacements[1] == null;
        return defaultOpen && defaultClose;
    }

    private static boolean looksLikeXml(String input) {
        return input != null
                && !input.isBlank()
                && (XML_CLOSING_ELEMENT.matcher(input).find()
                || XML_SELF_CLOSING_ELEMENT.matcher(input).find());
    }

    private Object resolveUntilStable(
            String input,
            boolean resolveEvaluations,
            boolean preserveWholeObject,
            Bookends... bookendStyles
    ) {
        Set<String> seenValues = new HashSet<>();
        String current = input;
        while (seenValues.add(current)) {
            String previous = current;
            for (Bookends bookends : bookendStyles) {
                Object resolved = resolveUsingBookends(
                        current, bookends, resolveEvaluations, preserveWholeObject);
                if (!(resolved instanceof String resolvedText)) {
                    return resolved;
                }
                current = resolvedText;
            }
            if (current.equals(previous)) {
                return current;
            }
        }
        throw new IllegalStateException(
                "Cyclic template resolution detected between bookend styles while resolving: "
                        + input);
    }

    private Object resolveUsingBookends(
            String input,
            Bookends bookends,
            boolean resolveEvaluations,
            boolean preserveWholeObject
    ) {
        QuoteParser parsedObj = new QuoteParser(input);
        for (var entry : parsedObj.entrySetWithoutTripleSingle()) {
            parsedObj.put(
                    entry.getKey(),
                    (String) resolveAll(
                            entry.getValue(),
                            parsedObj,
                            bookends,
                            resolveEvaluations,
                            false));
        }
        Object resolvedMasked = resolveAll(
                parsedObj.masked(),
                parsedObj,
                bookends,
                resolveEvaluations,
                preserveWholeObject);
        if (!(resolvedMasked instanceof String resolvedText)) {
            return resolvedMasked;
        }
        parsedObj.setMasked(resolvedText);
        return cleanupUnquotedReplacements(parsedObj.restore());
    }

    private static void validateDelimiterReplacements(String[] replacements) {
        if (replacements != null && replacements.length > 4) {
            throw new IllegalArgumentException(
                    "resolveWholeText accepts at most four delimiter replacements "
                            + "in this order: open, close, expression-open component, "
                            + "expression-close component. Received "
                            + replacements.length + ".");
        }
    }

    private static Bookends createBookends(String... replacements) {
        return createBookendsForOuter(
                delimiterAt(replacements, 0, DEFAULT_OPEN_BOOKEND, "open"),
                delimiterAt(replacements, 1, DEFAULT_CLOSE_BOOKEND, "close"),
                replacements);
    }

    private static Bookends createBookendsForOuter(
            String open,
            String close,
            String[] replacements
    ) {
        String expressionOpen = delimiterAt(
                replacements, 2, DEFAULT_OPEN_EXPRESSION_COMPONENT,
                "expression-open component");
        String expressionClose = delimiterAt(
                replacements, 3, DEFAULT_CLOSE_EXPRESSION_COMPONENT,
                "expression-close component");
        return new Bookends(
                open,
                close,
                open + expressionOpen,
                expressionClose + close);
    }

    private static String delimiterAt(
            String[] replacements,
            int index,
            String defaultValue,
            String description
    ) {
        if (replacements == null
                || index >= replacements.length
                || replacements[index] == null) {
            return defaultValue;
        }
        if (replacements[index].isEmpty()) {
            throw new IllegalArgumentException(
                    "The " + description + " delimiter replacement cannot be empty.");
        }
        return replacements[index];
    }

    private Object resolveAll(
            String input,
            QuoteParser parsedObj,
            Bookends bookends,
            boolean resolveEvaluations,
            boolean preserveWholeObject
    ) {
        try {
            String originalInput;
            do {
                input = normalizeBookends(input, bookends);
                originalInput = input;
                String previousInput;
                do {
                    previousInput = input;
                    if (input.contains(INTERNAL_MAP_OPEN)) {
                        Object resolved = resolveByMap(
                                input,
                                parsedObj,
                                bookends,
                                resolveEvaluations,
                                preserveWholeObject);
                        if (!(resolved instanceof String resolvedText)) {
                            return resolved;
                        }
                        input = resolvedText;
                    }
                    if (resolveEvaluations && input.contains(INTERNAL_EXPRESSION_OPEN)) {
                        input = resolveExpression(input, parsedObj, bookends);
                    }
                    input = normalizeBookends(input, bookends);
                } while (!input.equals(previousInput));
                input = UNRESOLVED_OPTIONAL_PLACEHOLDER.matcher(input).replaceAll("");
            } while (!input.equals(originalInput));
            return restoreBookends(
                    decodeBackToText(input.replaceAll(MATCH_BREAK, "")),
                    bookends);
        } catch (Throwable t) {
            t.printStackTrace();
            throw new RuntimeException(
                    "Could not resolve '" + input + "' due to '" + t.getMessage() + "'",
                    t);
        }
    }

    private Object resolveByMap(
            String input,
            QuoteParser parsedObj,
            Bookends bookends,
            boolean resolveEvaluations,
            boolean preserveWholeObject
    ) {
        String key = null;
        String matchedKey = null;
        boolean unquote = false;
        try {
            Matcher matcher = MAP_PLACEHOLDER.matcher(input);
            boolean wholeReference = preserveWholeObject && matcher.matches();
            matcher.reset();
            StringBuffer output = new StringBuffer();
            Object replacement = null;
            while (matcher.find()) {
                matchedKey = matcher.group(1);
                key = matchedKey;
                unquote = key.endsWith(UNQUOTE_SUFFIX);
                if (unquote) {
                    key = key.substring(0, key.length() - UNQUOTE_SUFFIX.length());
                }
                if (key.startsWith("&")) {
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    replacement = getReturnValue(key.substring(1));
                    break;
                }
                if (key.startsWith("$")) {
                    if (!resolveEvaluations) {
                        continue;
                    }
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    replacement = getRunningStep()
                            .createNewStepExtension(key.substring(1))
                            .runAndGetReturnValue();
                    break;
                }
                if (key.contains(MATCH_BREAK)) {
                    continue;
                }
                if (key.contains("&&") || key.contains("||")) {
                    replacement = bookends.wrap(key);
                    unquote = false;
                    break;
                }
                replacement = get(key);
                if (replacement != null) {
                    logTrace("'" + bookends.wrap(matchedKey) + "' -> '" + replacement + "'");
                    break;
                }
            }

            if (replacement == null) {
                return input;
            }
            if (wholeReference && !(replacement instanceof String)) {
                return replacement;
            }
            String stringReplacement = unquote
                    && !(replacement instanceof JsonNode)
                    && (replacement instanceof Map<?, ?>
                    || replacement instanceof Collection<?>
                    || replacement.getClass().isArray())
                    ? encodeToPlaceHolders(MAPPER.valueToTree(replacement).toString())
                    : getStringValue(replacement);
            String wrappedKey = bookends.wrap(matchedKey);
            if (stringReplacement.contains(bookends.open())
                    && !matchedKey.contains(MATCH_BREAK)
                    && stringReplacement.contains(wrappedKey)) {
                stringReplacement = stringReplacement.replace(
                        wrappedKey,
                        bookends.open() + MATCH_BREAK + matchedKey + bookends.close());
            }
            if (unquote) {
                stringReplacement = INTERNAL_UNQUOTE_OPEN
                        + stringReplacement
                        + INTERNAL_UNQUOTE_CLOSE;
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement(stringReplacement));
            matcher.appendTail(output);
            return output.toString();
        } catch (Throwable t) {
            throw new RuntimeException(
                    "Could not resolve by map '" + input + "' due to: " + t.getMessage(),
                    t);
        }
    }

    private String resolveExpression(String input, QuoteParser parsedObj, Bookends bookends) {
        Matcher matcher = EXPRESSION.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).trim()
                    .replace(INTERNAL_MAP_OPEN, bookends.open())
                    .replace(INTERNAL_MAP_CLOSE, bookends.close());
            key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
            String replacement = key.endsWith("?")
                    ? String.valueOf(evalToBoolean(key.substring(0, key.length() - 1), this))
                    : String.valueOf(eval(key, this));
            logTrace("'" + bookends.expressionOpen() + key
                    + bookends.expressionClose() + "' -> '" + replacement + "'");
            matcher.appendReplacement(
                    output,
                    replacement == null
                            ? matcher.group(0)
                            : Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String normalizeBookends(String input, Bookends bookends) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        input = input
                .replace(bookends.expressionOpen(), INTERNAL_EXPRESSION_OPEN)
                .replace(bookends.expressionClose(), INTERNAL_EXPRESSION_CLOSE);
        input = Pattern.compile(Pattern.quote(bookends.open()) + "(?![\\s=])")
                .matcher(input)
                .replaceAll(Matcher.quoteReplacement(INTERNAL_MAP_OPEN));
        return Pattern.compile("(?<!\\s)" + Pattern.quote(bookends.close()))
                .matcher(input)
                .replaceAll(Matcher.quoteReplacement(INTERNAL_MAP_CLOSE));
    }

    private static String restoreBookends(String input, Bookends bookends) {
        return input
                .replace(INTERNAL_MAP_OPEN, bookends.open())
                .replace(INTERNAL_MAP_CLOSE, bookends.close())
                .replace(INTERNAL_EXPRESSION_OPEN, bookends.expressionOpen())
                .replace(INTERNAL_EXPRESSION_CLOSE, bookends.expressionClose());
    }

    private static String cleanupUnquotedReplacements(String input) {
        String output = unwrapMarkedValue(input, '"');
        output = unwrapMarkedValue(output, '\'');
        output = unwrapMarkedValue(output, '`');
        return output
                .replace(INTERNAL_UNQUOTE_OPEN, "")
                .replace(INTERNAL_UNQUOTE_CLOSE, "");
    }

    private static String unwrapMarkedValue(String input, char quote) {
        String quoteText = String.valueOf(quote);
        Pattern pattern = Pattern.compile(
                "(?<!" + Pattern.quote(quoteText) + ")"
                        + Pattern.quote(quoteText + INTERNAL_UNQUOTE_OPEN)
                        + "(.*?)"
                        + Pattern.quote(INTERNAL_UNQUOTE_CLOSE + quoteText)
                        + "(?!" + Pattern.quote(quoteText) + ")",
                Pattern.DOTALL);
        Matcher matcher = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(
                    output,
                    Matcher.quoteReplacement(
                            matcher.group(1).replace("\\" + quoteText, quoteText)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    public Object getAndResolve(Object key) {
        if (key == null) {
            throw new RuntimeException("key cannot be null");
        }
        Object value = get(key);
        return value instanceof String text ? resolveWholeText(text) : value;
    }

    public Object getCaseInsensitive(String key) {
        if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
            return resolveFromVars(key);
        }
        ParsedMapPrefix parsed = extractMapPrefix(key);
        for (NodeMap map : getMapsForResolution(parsed.prefix)) {
            if (map == null) {
                continue;
            }
            Object replacement = map.getByNormalizedPath(parsed.key);
            if (replacement != null) {
                return replacement;
            }
        }
        return null;
    }

    private void putVar(String key, Object value) {
        key = key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)
                ? key.toLowerCase()
                : PKB_PREFIX + key.toLowerCase();
        logTrace("putVar '" + key + "' -> '" + value + "'");
        singletonMap.get().root.remove(key);
        singletonMap.get().putAsSingleton(key, value);
    }

    public List<Object> getList(String key) {
        Object obj = get(key);
        if (obj instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (obj instanceof Set<?> set) {
            return new ArrayList<>(set);
        }
        if (obj instanceof ArrayNode arrayNode) {
            List<Object> result = new ArrayList<>(arrayNode.size());
            arrayNode.forEach(result::add);
            return result;
        }
        return new ArrayList<>(Collections.singletonList(obj));
    }

    public static String addSuffix(String key, String suffix) {
        return key.endsWith(suffix) ? key : key + " " + suffix;
    }

    @Override
    public Object get(Object key) {
        if (key == null) {
            throw new RuntimeException("key cannot be null");
        }
        if (key instanceof String stringKey) {
            return get(stringKey);
        }
        for (NodeMap map : maps.values()) {
            Object returnObj = map.get(String.valueOf(key));
            if (returnObj != null) {
                return returnObj;
            }
        }
        return null;
    }

    public Object get(String key) {
        ParsedMapPrefix parsed = extractMapPrefix(key);
        String query = parsed.key;
        if (query.startsWith(FILE_REFERENCE_PREFIX)) {
            return buildJsonFromPath(query.substring(FILE_REFERENCE_PREFIX.length()));
        }
        if (query.startsWith(DATA_REFERENCE_PREFIX)) {
            return getScenarioMarkerData(query.substring(DATA_REFERENCE_PREFIX.length()));
        }
        if (query.contains("_") && query.toLowerCase().startsWith(PKB_PREFIX)) {
            return resolveFromVars(query);
        }

        Object primary = resolveQuery(query, parsed.prefix);
        String literalQuery = Tokenized.unquoteLiteralProperty(query);
        if (isQuestionPrefixedPropertyQuery(query) || !isMissingNullOrBlank(primary)) {
            return primary;
        }

        Object fallback = resolveQuery(
                Tokenized.quoteLiteralProperty("?" + literalQuery),
                parsed.prefix);
        return fallback != null ? fallback : primary;
    }

    private Object resolveQuery(String query, String mapTypes) {
        Tokenized tokenized = new Tokenized(query);
        for (NodeMap map : getMapsForResolution(mapTypes)) {
            if (map == null) {
                continue;
            }
            Object replacement = map.get(tokenized);
            if (replacement != null) {
                return replacement;
            }
        }
        return null;
    }

    private static boolean isQuestionPrefixedPropertyQuery(String query) {
        String source = query.strip();
        return source.startsWith("?")
                || source.startsWith("`?")
                || source.contains(".?")
                || source.contains(".`?");
    }

    private static boolean isMissingNullOrBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String string) {
            return string.isBlank();
        }
        if (value instanceof JsonNode node) {
            return node.isNull()
                    || node.isMissingNode()
                    || node.isTextual() && node.textValue().isBlank();
        }
        return false;
    }

    public List<?> get(ElementMatch element) {
        String categoryName = element.category.replaceFirst("(?i:s)$", "");
        boolean noQuotedText = element.defaultText == null || element.defaultText.isNullOrBlank();
        if (categoryName.equals(TABLE_KEY)) {
            if (noQuotedText) {
                Object raw = element.parentPhrase
                        .getPhraseParsingMap()
                        .getPhraseMap()
                        .get(TABLE_KEY);
                return raw == null ? List.of() : List.of(raw);
            }
            Object raw = getDataElementValue(element);
            if (raw instanceof DataTable dataTable) {
                return List.of(dataTable);
            }
            if (raw instanceof JsonNode jsonNode) {
                return List.of(jsonNodeToDataTable(jsonNode));
            }
            return raw == null ? List.of() : List.of(raw);
        }
        if (categoryName.equals(DOCSTRING_KEY)) {
            if (noQuotedText) {
                Object raw = element.parentPhrase
                        .getPhraseParsingMap()
                        .getPhraseMap()
                        .get(DOCSTRING_KEY);
                return raw == null ? List.of() : List.of(raw);
            }
            Object raw = getDataElementValue(element);
            return raw == null ? List.of() : List.of(raw);
        }

        NodeMap phraseMap = getPhraseMap();
        switch (categoryName) {
            case ENTRY_KEY:
                JsonNode jsonNode = phraseMap.getRoot().get(ROW_KEY);
                ArrayList<JsonNode> list = new ArrayList<>();
                if (jsonNode instanceof ArrayNode arrayNode) {
                    arrayNode.forEach(list::add);
                } else if (jsonNode != null) {
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
                        cellValues.add(value == null
                                || value.isNull()
                                || value.isMissingNode()
                                ? ""
                                : value.isContainerNode() ? value.toString() : value.asText(""));
                    } else {
                        cellKeys.add("");
                        cellValues.add(cell == null
                                || cell.isNull()
                                || cell.isMissingNode()
                                ? ""
                                : cell.isContainerNode() ? cell.toString() : cell.asText(""));
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
                Object raw = getDataElementValue(element);
                if (raw instanceof DataTable dataTable) {
                    raw = dataTableToJsonNode(dataTable);
                } else if (raw instanceof DocString docString) {
                    try {
                        raw = docStringtoJsonNode(docString);
                    } catch (JsonProcessingException e) {
                        throw new IllegalArgumentException(
                                "Could not convert Doc String to Data", e);
                    }
                }
                return Collections.singletonList(raw);
            default:
                return null;
        }
    }

    private Object getDataElementValue(ElementMatch element) {
        String value = element.defaultText.toString();
        Object reference = ValueFormatting.fromReferenceText(value);
        return reference != null ? reference : get(value);
    }

    @Override
    public Object put(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new RuntimeException("key cannot be null or blank");
        }
        if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
            Object oldValue = resolveFromVars(key);
            putVar(key.toLowerCase(), value);
            return oldValue;
        }
        Tokenized tokenized = new Tokenized(key);
        Object oldValue = getPrimaryRunMap().get(tokenized);
        getPrimaryRunMap().put(tokenized, value);
        return oldValue;
    }

    public Object putObject(Object key, Object value) {
        if (key instanceof String stringKey) {
            return put(stringKey, value);
        }
        if (key == null) {
            throw new RuntimeException("key cannot be null");
        }
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
        if (obj == null) {
            return "";
        }
        if (obj instanceof DataTable || obj instanceof DocString) {
            return ValueFormatting.toReferenceText(obj);
        }
        if (obj instanceof JsonNode jsonNode) {
            if (jsonNode.isTextual()) {
                return jsonNode.textValue();
            }
            if (jsonNode.isValueNode()) {
                return jsonNode.asText("");
            }
            try {
                return encodeToPlaceHolders(MAPPER.writeValueAsString(jsonNode));
            } catch (JsonProcessingException e) {
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
        return "\n====\n"
                + stream.map(String::valueOf)
                .collect(Collectors.joining(System.lineSeparator()))
                + "\n---\n";
    }

    private static final Pattern MAP_PREFIX_PATTERN = Pattern.compile(
            "^([A-Z](?:[A-Z., ]*[A-Z])?):(.*)$",
            Pattern.DOTALL);

    public record ParsedMapPrefix(String prefix, String key) {
    }

    public static ParsedMapPrefix extractMapPrefix(String input) {
        if (input == null) {
            return new ParsedMapPrefix(null, null);
        }
        Matcher matcher = MAP_PREFIX_PATTERN.matcher(input);
        if (!matcher.matches()) {
            return new ParsedMapPrefix(null, input);
        }
        return new ParsedMapPrefix(
                matcher.group(1).replaceAll("\\s+", " ").trim(),
                matcher.group(2).trim());
    }
}
