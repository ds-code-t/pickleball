/*
 * MappingProcessor delimiter rewrite — revision v6
 *
 * Supports both <...> and ~[~...~]~ as built-in default bookend styles,
 * while retaining custom delimiters, comparison handling, FILE:, and ~unquote.
 * XML-like input automatically uses only the XML-safe ~[~...~]~ bookends when
 * no custom outer delimiters are supplied.
 */
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
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.queries.Tokenized.AS_LIST_SUFFIX;
import static tools.dscode.common.reporting.logging.LogForwarder.logTrace;
import static tools.dscode.common.util.StringUtilities.decodeBackToText;
import static tools.dscode.common.util.StringUtilities.encodeToPlaceHolders;
import static tools.dscode.common.variables.RunVars.resolveFromVars;
import static tools.dscode.coredefinitions.GeneralSteps.getReturnValue;
import static tools.dscode.testengine.PKB_props.PKB_PREFIX;

public abstract class MappingProcessor implements Map<String, Object> {

    private static final String FILE_REFERENCE_PREFIX = "FILE:";
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
        boolean log = nodes.stream().anyMatch(m -> m.getMapType() == MapConfigurations.MapType.STEP_MAP);
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
        boolean log = nodes.stream().anyMatch(m -> m.getMapType() == MapConfigurations.MapType.STEP_MAP);
        if (log) {
        }
        List<List<NodeMap>> grouped = groupByMapType(nodes);
        for (List<NodeMap> list : grouped) {
            if (list.isEmpty()) {
                continue;
            }
            List<NodeMap> existingNodes = maps.get(list.getFirst().getMapType());
            existingNodes.addAll(0, list);
        }
    }

    public static List<List<NodeMap>> groupByMapType(List<NodeMap> nodes) {
        List<List<NodeMap>> grouped = new ArrayList<>();
        for (NodeMap c : nodes) {
            if (grouped.isEmpty()
                    || !grouped.getLast().getFirst().getMapType().equals(c.getMapType())) {
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
     * Get a flat list of values, grouped and ordered by the original key order.
     */
    public List<NodeMap> getMapsForResolution() {
        List<NodeMap> out = new ArrayList<>();
        for (MapConfigurations.MapType key : keyOrder) {
            out.addAll(maps.get(key));
        }
        return out;
    }

    public List<NodeMap> getMapsForSingletonResolution() {
        List<NodeMap> out = new ArrayList<>();
        for (MapConfigurations.MapType key : singletonOrder) {
            List<NodeMap> mapList = maps.get(key);
            out.addAll(key.equals(MapConfigurations.MapType.STEP_MAP) ? mapList.reversed() : mapList);
        }
        return out;
    }

    /**
     * Expose the configured key order for debugging and inspection.
     */
    public List<MapConfigurations.MapType> keyOrder() {
        return keyOrder;
    }

    private static final String DEFAULT_OPEN_BOOKEND = "<";
    private static final String DEFAULT_CLOSE_BOOKEND = ">";
    private static final String DEFAULT_OPEN_EXPRESSION_COMPONENT = "{";
    private static final String DEFAULT_CLOSE_EXPRESSION_COMPONENT = "}";
    private static final String SECONDARY_DEFAULT_OPEN_BOOKEND = "~[~";
    private static final String SECONDARY_DEFAULT_CLOSE_BOOKEND = "~]~";

    /*
     * Deliberately limited XML detection. This does not validate XML. It only
     * recognizes text containing a closing element or a self-closing element,
     * which is enough to avoid interpreting normal XML tags as <...> template
     * references during default delimiter processing.
     */
    private static final Pattern XML_CLOSING_ELEMENT = Pattern.compile(
            "</\\s*[A-Za-z_][A-Za-z0-9_.-]*\\s*>");

    private static final Pattern XML_SELF_CLOSING_ELEMENT = Pattern.compile(
            "<\\s*[A-Za-z_][A-Za-z0-9_.-]*"
                    + "(?:\\s+[^<>]*?)?/\\s*>");

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
     * Private-use sentinels used to collapse the configured bookends into one
     * internal syntax. These sentinels are restored before text is returned.
     */
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

    /*
     * Map references may not span lines or contain another normalized map or
     * expression placeholder. Expression bodies may contain temporary map
     * sentinels because comparison operators such as < and > can be normalized
     * while the outer expression is being processed.
     */
    private static final String MAP_BODY =
            "[^\\r\\n"
                    + INTERNAL_MAP_BOOKEND_FLAG
                    + INTERNAL_EXPRESSION_BOOKEND_FLAG
                    + "]+";

    private static final String EXPRESSION_BODY =
            "[^\\r\\n"
                    + INTERNAL_EXPRESSION_BOOKEND_FLAG
                    + "]+";

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


    /**
     * Resolves mapping and expression references in {@code input}.
     *
     * <p>When neither outer delimiter is explicitly supplied, both built-in
     * outer-bookend styles are supported in ordinary text:</p>
     * <ul>
     *     <li>{@code <key>} and {@code <{expression}>}</li>
     *     <li>{@code ~[~key~]~} and {@code ~[~{expression}~]~}</li>
     * </ul>
     *
     * <p>If the input looks like XML and neither outer delimiter is explicitly
     * supplied, only the XML-safe {@code ~[~...~]~} style is processed. This
     * prevents normal XML elements from being interpreted as map references.</p>
     *
     * <p>The optional delimiter arguments replace, by position:</p>
     * <ol>
     *     <li>{@code <} — map/reference opening delimiter</li>
     *     <li>{@code >} — map/reference closing delimiter</li>
     *     <li>{@code {} opening component — appended to the map opening delimiter</li>
     *     <li>{@code }} closing component — prepended to the map closing delimiter</li>
     * </ol>
     *
     * <p>If either outer delimiter is explicitly supplied, only the resulting
     * custom bookend style is used. Missing or {@code null} components retain
     * their normal defaults. For example:</p>
     * <pre>
     * resolveWholeText(text)
     * // Supports both &lt;key&gt; / &lt;{expression}&gt; and
     * // ~[~key~]~ / ~[~{expression}~]~
     *
     * resolveWholeText(text, "[[", "]]", "(", ")")
     * // Supports only [[key]] and [[(expression)]]
     *
     * resolveWholeText(text, null, null, "[", "]")
     * // Supports both &lt;key&gt; / &lt;[expression]&gt; and
     * // ~[~key~]~ / ~[~[expression]~]~
     * </pre>
     */
    public String resolveWholeText(String input, String... delimiterReplacements) {
        validateDelimiterReplacements(delimiterReplacements);

        String resolvedText;
        if (usesDualDefaultOuterBookends(delimiterReplacements)
                && looksLikeXml(input)) {
            Bookends xmlSafeBookends = createBookendsForOuter(
                    SECONDARY_DEFAULT_OPEN_BOOKEND,
                    SECONDARY_DEFAULT_CLOSE_BOOKEND,
                    delimiterReplacements);

            resolvedText = resolveUsingBookends(input, xmlSafeBookends);
        } else if (usesDualDefaultOuterBookends(delimiterReplacements)) {
            Bookends angleBookends = createBookendsForOuter(
                    DEFAULT_OPEN_BOOKEND,
                    DEFAULT_CLOSE_BOOKEND,
                    delimiterReplacements);

            Bookends tildeSquareBookends = createBookendsForOuter(
                    SECONDARY_DEFAULT_OPEN_BOOKEND,
                    SECONDARY_DEFAULT_CLOSE_BOOKEND,
                    delimiterReplacements);

            resolvedText = resolveUntilStable(
                    input,
                    angleBookends,
                    tildeSquareBookends);
        } else {
            resolvedText = resolveUsingBookends(
                    input,
                    createBookends(delimiterReplacements));
        }

        logTrace("Resolved: '" + input + "' -> '" + resolvedText + "'");
        return resolvedText;
    }

    /**
     * Dual built-in mode is active only when neither outer delimiter was
     * explicitly supplied. Expression components may still be customized.
     */
    private static boolean usesDualDefaultOuterBookends(
            String[] delimiterReplacements
    ) {
        boolean defaultOpen = delimiterReplacements == null
                || delimiterReplacements.length < 1
                || delimiterReplacements[0] == null;

        boolean defaultClose = delimiterReplacements == null
                || delimiterReplacements.length < 2
                || delimiterReplacements[1] == null;

        return defaultOpen && defaultClose;
    }

    private static boolean looksLikeXml(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }

        return XML_CLOSING_ELEMENT.matcher(input).find()
                || XML_SELF_CLOSING_ELEMENT.matcher(input).find();
    }

    /**
     * Runs each built-in dialect in its own complete normalization/restoration
     * pass. This preserves the original unresolved syntax because each pass
     * restores its own active bookends before the next dialect is attempted.
     */
    private String resolveUntilStable(String input, Bookends... bookendStyles) {
        Set<String> seenValues = new HashSet<>();
        String current = input;

        while (seenValues.add(current)) {
            String previous = current;

            for (Bookends bookends : bookendStyles) {
                current = resolveUsingBookends(current, bookends);
            }

            if (current.equals(previous)) {
                return current;
            }
        }

        throw new IllegalStateException(
                "Cyclic template resolution detected between bookend styles while resolving: "
                        + input);
    }

    /**
     * Performs one complete quote, normalization, resolution, restoration, and
     * unquote-cleanup pass using exactly one bookend style.
     */
    private String resolveUsingBookends(String input, Bookends bookends) {
        QuoteParser parsedObj = new QuoteParser(input);

        // Resolve quoted substring values first. This lets expression bookends
        // restore quoted placeholders that have already been resolved.
        for (var entry : parsedObj.entrySetWithoutTripleSingle()) {
            parsedObj.put(
                    entry.getKey(),
                    resolveAll(entry.getValue(), parsedObj, bookends));
        }

        parsedObj.setMasked(resolveAll(parsedObj.masked(), parsedObj, bookends));
        return cleanupUnquotedReplacements(parsedObj.restore());
    }

    private static void validateDelimiterReplacements(
            String[] delimiterReplacements
    ) {
        if (delimiterReplacements != null && delimiterReplacements.length > 4) {
            throw new IllegalArgumentException(
                    "resolveWholeText accepts at most four delimiter replacements "
                            + "in this order: open, close, expression-open component, "
                            + "expression-close component. Received "
                            + delimiterReplacements.length + ".");
        }
    }

    private static Bookends createBookends(String... delimiterReplacements) {
        String open = delimiterAt(
                delimiterReplacements, 0, DEFAULT_OPEN_BOOKEND, "open");
        String close = delimiterAt(
                delimiterReplacements, 1, DEFAULT_CLOSE_BOOKEND, "close");

        return createBookendsForOuter(
                open,
                close,
                delimiterReplacements);
    }

    private static Bookends createBookendsForOuter(
            String open,
            String close,
            String[] delimiterReplacements
    ) {
        String expressionOpenComponent = delimiterAt(
                delimiterReplacements, 2, DEFAULT_OPEN_EXPRESSION_COMPONENT,
                "expression-open component");
        String expressionCloseComponent = delimiterAt(
                delimiterReplacements, 3, DEFAULT_CLOSE_EXPRESSION_COMPONENT,
                "expression-close component");

        return new Bookends(
                open,
                close,
                open + expressionOpenComponent,
                expressionCloseComponent + close);
    }

    private static String delimiterAt(
            String[] delimiterReplacements,
            int index,
            String defaultValue,
            String description
    ) {
        if (delimiterReplacements == null
                || index >= delimiterReplacements.length
                || delimiterReplacements[index] == null) {
            return defaultValue;
        }

        String replacement = delimiterReplacements[index];
        if (replacement.isEmpty()) {
            throw new IllegalArgumentException(
                    "The " + description + " delimiter replacement cannot be empty.");
        }
        return replacement;
    }

    private String resolveAll(String input, QuoteParser parsedObj, Bookends bookends) {
        try {
            String originalInput;
            do {
                input = normalizeBookends(input, bookends);
                originalInput = input;
                String previousInput;
                do {
                    previousInput = input;
                    if (input.contains(INTERNAL_MAP_OPEN)) {
                        input = resolveByMap(input, parsedObj, bookends);
                    }
                    if (input.contains(INTERNAL_EXPRESSION_OPEN)) {
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

    private String resolveByMap(String input, QuoteParser parsedObj, Bookends bookends) {
        String key = null;
        String matchedKey = null;
        boolean unquote = false;
        try {
            Matcher matcher = MAP_PLACEHOLDER.matcher(input);
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
                    key = parsedObj.restoreAndStripBookEnds(decodeBackToText(key));
                    replacement = getRunningStep().resolveStepFromString(key.substring(1));
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

            String stringReplacement = getStringValue(replacement);
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
            String key = matcher.group(1).trim();

            // Restore map delimiters that were temporarily normalized inside
            // the expression, such as the < and > comparison operators.
            key = key
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

    /**
     * Converts configured expression bookends first, then conditionally
     * normalizes map bookends. An opening map delimiter is ignored when it is
     * immediately followed by whitespace or '=', and a closing map delimiter
     * is ignored when it is immediately preceded by whitespace. This avoids
     * treating common comparison operators as map-reference delimiters.
     */
    private static String normalizeBookends(String input, Bookends bookends) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        input = input
                .replace(bookends.expressionOpen(), INTERNAL_EXPRESSION_OPEN)
                .replace(bookends.expressionClose(), INTERNAL_EXPRESSION_CLOSE);

        input = Pattern.compile(
                        Pattern.quote(bookends.open()) + "(?![\\s=])")
                .matcher(input)
                .replaceAll(Matcher.quoteReplacement(INTERNAL_MAP_OPEN));

        return Pattern.compile(
                        "(?<!\\s)" + Pattern.quote(bookends.close()))
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

    /**
     * Removes a directly surrounding matching quote pair from a value marked by
     * {@code ~unquote}. If the markers are not directly surrounded by matching
     * double quotes, single quotes, or backticks, only the internal markers are
     * removed and the surrounding text is left unchanged.
     */
    private static String cleanupUnquotedReplacements(String input) {
        String output = unwrapMarkedValue(input, '\"');
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
            matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    public Object getAndResolve(Object key) {
        if (key == null) {
            throw new RuntimeException("key cannot be null");
        }
        Object returnObj = get(key);
        if (returnObj instanceof String returnString) {
            return resolveWholeText(returnString);
        }
        return returnObj;
    }

    public Object getCaseInsensitive(String key) {
        if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
            return resolveFromVars(key);
        }

        Tokenized tokenized = new Tokenized(key);
        for (NodeMap map : tokenized.isSingletonKey
                ? getMapsForSingletonResolution()
                : getMapsForResolution()) {
            if (map == null) {
                continue;
            }
            Object replacement = map.getByNormalizedPath(key);
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

    @SuppressWarnings("unchecked")
    public List<Object> getList(String key) {
        return (List<Object>) get(addSuffix(key, AS_LIST_SUFFIX));
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
        boolean directGet = key.startsWith("`") && key.endsWith("`");
        if (directGet) {
            key = key.substring(1, key.length() - 1);
        } else {
            if (key.startsWith(FILE_REFERENCE_PREFIX)) {
                return buildJsonFromPath(key.substring(FILE_REFERENCE_PREFIX.length()));
            }
            if (key.contains("_") && key.toLowerCase().startsWith(PKB_PREFIX)) {
                return resolveFromVars(key);
            }
        }

        Tokenized tokenized = new Tokenized(key);
        Object returnReplacement = null;
        while (true) {
            for (NodeMap map : tokenized.isSingletonKey
                    ? getMapsForSingletonResolution()
                    : getMapsForResolution()) {
                if (map == null) {
                    continue;
                }

                Object replacement;
                if (directGet) {
                    replacement = map.directGet(key);
                    if (replacement instanceof ArrayNode arrayNode) {
                        replacement = arrayNode.isEmpty()
                                ? null
                                : arrayNode.get(arrayNode.size() - 1);
                    }
                } else {
                    replacement = map.get(tokenized);
                }

                if (replacement != null) {
                    if (replacement instanceof String replacementString
                            && replacementString.isEmpty()
                            && !key.startsWith("?")) {
                        key = "?" + key;
                        directGet = true;
                        returnReplacement = replacement;
                        continue;
                    }
                    returnReplacement = replacement;
                    break;
                }
            }

            if (returnReplacement != null || key.startsWith("?")) {
                break;
            }
            key = "?" + key;
            directGet = true;
        }
        return returnReplacement;
    }

    public List<?> get(ElementMatch element) {
        String categoryName = element.category.replaceFirst("(?i:s)$", "");
        boolean noQuotedText = element.defaultText == null || element.defaultText.isNullOrBlank();

        if (categoryName.equals(TABLE_KEY)) {
            if (noQuotedText) {
                return element.parentPhrase
                        .getPhraseParsingMap()
                        .getPhraseMap()
                        .getAsList(TABLE_KEY);
            }

            List<JsonNode> tableList = getDataTableMap().getAsList(TABLE_KEY);
            List<String> tableNames = new ArrayList<>();
            List<JsonNode> tableNodes = new ArrayList<>();
            for (JsonNode jsonNode : tableList) {
                if (jsonNode instanceof ObjectNode objectNode) {
                    String tableName = objectNode.fieldNames().next();
                    tableNames.add(tableName);
                    tableNodes.add(MAPPER.valueToTree(objectNode.get(tableName)));
                }
            }
            return filterGroupedValues(tableNames, tableNodes, element, false);
        }

        if (categoryName.equals(DOCSTRING_KEY)) {
            if (noQuotedText) {
                return element.parentPhrase
                        .getPhraseParsingMap()
                        .getPhraseMap()
                        .getAsList(DOCSTRING_KEY);
            }
            ObjectNode objectNode = (ObjectNode) getDocStringMap().get(DOCSTRING_KEY);
            return new ArrayList<>(Collections.singleton(objectNode));
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
                        cellValues.add(value == null
                                || value.isNull()
                                || value.isMissingNode()
                                ? ""
                                : value.asText(""));
                    } else {
                        cellKeys.add("");
                        cellValues.add(cell == null
                                || cell.isNull()
                                || cell.isMissingNode()
                                ? ""
                                : cell.asText(""));
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
        if (key == null || key.isBlank()) {
            throw new RuntimeException("key cannot be null or blank");
        }

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
        if (obj instanceof JsonNode jsonNode) {
            if (jsonNode.isTextual()) {
                return jsonNode.textValue();
            }
            if (jsonNode.isValueNode()) {
                return jsonNode.asText("");
            }
            try {
                String otherNode = MAPPER.writeValueAsString(jsonNode);
                return encodeToPlaceHolders(otherNode);
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
}
