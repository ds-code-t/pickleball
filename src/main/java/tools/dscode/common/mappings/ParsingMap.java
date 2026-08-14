package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.ScenarioStepData;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.dataelements.DataContextNodeMap;
import tools.dscode.common.dataelements.DataElementGroup;
import tools.dscode.common.dataelements.DataExecutionResult;
import tools.dscode.common.dataelements.DataElementKind;
import tools.dscode.common.dataelements.DataElementRuntime;
import tools.dscode.common.dataelements.StructuredDataConverter;
import tools.dscode.common.mappings.queries.Tokenized;
import tools.dscode.common.treeparsing.parsedComponents.DataElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getClosestScenarioStepAncestor;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRootScenarioStep;
import static io.cucumber.core.runner.util.TableUtils.DATA_OBJECT_KEY;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.ValueFormatting.MAPPER;
import static tools.dscode.common.mappings.custommappings.ValConverter.valConverter;
import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;
import static tools.dscode.coredefinitions.DataTableDefinitions.dataTableToJsonNode;
import static tools.dscode.coredefinitions.DataTableDefinitions.jsonNodeToDataTable;
import static tools.dscode.coredefinitions.DocStringDefinitions.docStringtoJsonNode;

public class ParsingMap extends MappingProcessor {
    private static final String DATA_REFERENCE_PREFIX = "data:";
    private static final String CONFIG_REFERENCE_PREFIX = "config:";
    private static final DataElementRuntime DATA_ELEMENT_RUNTIME =
            new DataElementRuntime();
    private static final ParsingMap GLOBALS_PARSINGMAP =
            new ParsingMap(GLOBALS);
    public static final String CONFIGS_MAP_ROOT = "configs";
    public static final String DEFAULT_CONFIG_PATH = "configs";

    /** @deprecated use {@link #CONFIGS_MAP_ROOT}; retained for source compatibility. */
    @Deprecated
    public static final String configsRoot = CONFIGS_MAP_ROOT;

    public static synchronized void initializeConfigs(String configuredPath) {
        String path = configuredPath == null || configuredPath.isBlank()
                ? DEFAULT_CONFIG_PATH
                : configuredPath.trim();
        JsonNode configsNode = loadConfigs(path);
        GLOBALS.root.set(CONFIGS_MAP_ROOT, configsNode);
    }

    private static JsonNode loadConfigs(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("classpath:")) {
            return FileAndDataParsing.buildJsonFromPath(
                    normalized.substring("classpath:".length()),
                    false
            );
        }

        int slash = normalized.lastIndexOf('/');
        if (slash < 0) {
            return FileAndDataParsing.buildJsonFromPath(normalized, false);
        }

        String root = normalized.substring(0, slash);
        String name = normalized.substring(slash + 1);
        if (name.isBlank()) {
            int previousSlash = root.lastIndexOf('/');
            name = previousSlash < 0 ? root : root.substring(previousSlash + 1);
            root = previousSlash < 0 ? "" : root.substring(0, previousSlash);
        }
        return FileAndDataParsing.buildJsonFromPathUnderRoot(root, name, false);
    }

    public static ParsingMap getGlobalsParsingmap() {
        return GLOBALS_PARSINGMAP;
    }

    public List<NodeMap> getNodeMaps(
            MapConfigurations.MapType mapType
    ) {
        return getMaps().get(mapType);
    }

    public ParsingMap() {
    }

    ParsingMap(NodeMap nodeMap) {
        super(nodeMap);
    }

    @Override
    public String resolveWholeText(String input, String... delimiterReplacements) {
        return resolveWholeText(input, true, delimiterReplacements);
    }

    @Override
    public String resolveWholeText(
            String input,
            boolean resolveEvaluations,
            String... delimiterReplacements
    ) {
        return MappingDirectiveResolver.resolveText(
                this,
                input,
                resolveEvaluations,
                delimiterReplacements
        );
    }

    @Override
    public Object resolveWholeValue(String input, String... delimiterReplacements) {
        return resolveWholeValue(input, true, delimiterReplacements);
    }

    @Override
    public Object resolveWholeValue(
            String input,
            boolean resolveEvaluations,
            String... delimiterReplacements
    ) {
        return MappingDirectiveResolver.resolveValue(
                this,
                input,
                resolveEvaluations,
                delimiterReplacements
        );
    }

    private String legacyResolveWholeText(
            String input,
            boolean resolveEvaluations,
            String... delimiterReplacements
    ) {
        return super.resolveWholeText(
                input,
                resolveEvaluations,
                delimiterReplacements
        );
    }

    private Object legacyResolveWholeValue(
            String input,
            boolean resolveEvaluations,
            String... delimiterReplacements
    ) {
        return super.resolveWholeValue(
                input,
                resolveEvaluations,
                delimiterReplacements
        );
    }

    @Override
    public Object get(String key) {
        String query = extractMapPrefix(key).key();
        if (query != null && query.startsWith(CONFIG_REFERENCE_PREFIX)) {
            String configQuery = query.substring(CONFIG_REFERENCE_PREFIX.length());
            return super.get(configQuery.isBlank()
                    ? CONFIGS_MAP_ROOT
                    : CONFIGS_MAP_ROOT + "." + configQuery);
        }

        Object value = super.get(key);
        if (!(value instanceof ScenarioStepData data)
                || query == null
                || !query.startsWith(DATA_REFERENCE_PREFIX)) {
            return value;
        }
        Object dataTable = data.getDataTableValue(null);
        return dataTable != null
                ? dataTable
                : data.getDocStringValue(null);
    }

    @Override
    public Object getCaseInsensitive(String key) {
        String query = extractMapPrefix(key).key();
        if (query != null && query.startsWith(CONFIG_REFERENCE_PREFIX)) {
            String configQuery = query.substring(CONFIG_REFERENCE_PREFIX.length());
            return super.getCaseInsensitive(configQuery.isBlank()
                    ? CONFIGS_MAP_ROOT
                    : CONFIGS_MAP_ROOT + "." + configQuery);
        }
        return super.getCaseInsensitive(key);
    }

    @Override
    public List<?> get(ElementMatch element) {
        Optional<DataElementMatch> dataElement =
                DataElementMatch.from(element);
        if (dataElement.isPresent()
                && DataElementRuntime.supports(
                        dataElement.get().registration().kind()
                )
                && !usesLegacyDataAlias(dataElement.get())) {
            DataElementMatch queryElement = dataElement.get();
            Object source = resolveDataSource(queryElement);
            DataExecutionResult result = DATA_ELEMENT_RUNTIME
                    .execute(source, queryElement);
            return DataContextNodeMap.contextualValues(result);
        }
        String categoryName =
                element.category.replaceFirst("(?i:s)$", "");
        boolean noQuotedText = element.defaultText == null
                || element.defaultText.isNullOrBlank();
        if (!noQuotedText) {
            return super.get(element);
        }

        if (TABLE_KEY.equals(categoryName)) {
            List<?> activeTable = super.get(element);
            if (activeTable != null && !activeTable.isEmpty()) {
                return activeTable;
            }
            Object dataTable = nearestUnnamedMarkerData(true);
            return dataTable == null
                    ? List.of()
                    : List.of(dataTable);
        }
        if (DATA_OBJECT_KEY.equals(categoryName)) {
            Object data = nearestUnnamedMarkerData(false);
            if (data == null) {
                return List.of();
            }
            return List.of(toJsonData(data));
        }
        return super.get(element);
    }

    private Object resolveDataSource(
            DataElementMatch element
    ) {
        DataElementKind kind = element.registration().kind();
        if (element.hasExplicitSource()) {
            return resolveExplicitDataSource(element);
        }
        if (kind == DataElementKind.DATA_TABLE) {
            Object active = activeTable(element);
            return active != null
                    ? active
                    : nearestUnnamedMarkerData(true);
        }
        if (kind.group() == DataElementGroup.FORMAT) {
            Object active = activeTable(element);
            if (active != null) {
                return active;
            }
            Object marker = nearestUnnamedMarkerData(false);
            return marker != null ? marker : phraseContextSource();
        }
        if (kind.group() == DataElementGroup.JAVA) {
            Object context = phraseContextSource();
            return context != null ? context : activeTable(element);
        }
        Object active = activeTable(element);
        return active != null ? active : phraseContextSource();
    }

    private Object resolveExplicitDataSource(
            DataElementMatch element
    ) {
        ValueWrapper sourceOperand = element.defaultText;
        if (sourceOperand == null) {
            return null;
        }
        String text = sourceOperand.toString();

        Object reference = ValueFormatting.fromReferenceText(text);
        if (reference != null) {
            return reference;
        }

        Object resolved = resolveWholeValue(text);
        if (!(resolved instanceof String resolvedText)
                || !resolvedText.equals(text)) {
            return resolved;
        }

        Object mapped = get(text);
        if (mapped != null) {
            return mapped;
        }

        Object structuredLiteral = structuredLiteral(sourceOperand.getValue());
        if (structuredLiteral != null) {
            return structuredLiteral;
        }

        if (element.registration().kind() == DataElementKind.DATA_TABLE) {
            return firstRawValue(super.get(element));
        }

        return supportsLiteralFallback(element)
                ? sourceOperand.getValue()
                : null;
    }

    private static Object structuredLiteral(Object value) {
        if (!(value instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            return null;
        }
        return StructuredDataConverter.convert(
                text,
                DataElementKind.STRUCTURED_DATA
        );
    }

    private static Object firstRawValue(List<?> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Object value = values.getFirst();
        return value instanceof ValueWrapper wrapper
                ? wrapper.getValue()
                : value;
    }

    private Object phraseContextSource() {
        NodeMap phraseMap = getPhraseMap();
        if (phraseMap instanceof DataContextNodeMap dataContext) {
            return dataContext.materialize();
        }
        if (phraseMap == null || phraseMap.getRoot() == null) {
            return null;
        }
        ObjectNode root = phraseMap.getRoot().deepCopy();
        root.remove(NodeMap.MAP_TYPE_KEY);
        if (root.isEmpty()) {
            return null;
        }
        JsonNode rows = root.get(ROW_KEY);
        return rows != null && root.size() == 1
                ? rows
                : root;
    }

    private static boolean usesLegacyDataAlias(
            DataElementMatch element
    ) {
        return element.registration().alias()
                && "Data".equalsIgnoreCase(
                        element.registration().name()
                );
    }

    private static boolean supportsLiteralFallback(
            DataElementMatch element
    ) {
        return element.registration().kind().group()
                == DataElementGroup.FORMAT
                && !usesLegacyDataAlias(element);
    }

    private Object activeTable(DataElementMatch element) {
        if (element.parentPhrase == null
                || element.parentPhrase.getPhraseParsingMap() == null) {
            return null;
        }
        NodeMap phraseMap = element.parentPhrase
                .getPhraseParsingMap()
                .getPhraseMap();
        return phraseMap == null ? null : phraseMap.get(TABLE_KEY);
    }

    private static Object nearestUnnamedMarkerData(
            boolean dataTableOnly
    ) {
        ScenarioStep scenarioStep = getClosestScenarioStepAncestor();
        StepExtension currentStep = GlobalState.getRunningStep();
        if (scenarioStep == null || currentStep == null) {
            return null;
        }
        return dataTableOnly
                ? scenarioStep.getNearestUnnamedDataTable(currentStep)
                : scenarioStep.getNearestUnnamedData(currentStep);
    }

    private static Object toJsonData(Object data) {
        if (data instanceof DataTable dataTable) {
            return dataTableToJsonNode(dataTable);
        }
        if (data instanceof DocString docString) {
            try {
                return docStringtoJsonNode(docString);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException(
                        "Could not convert Doc String to Data",
                        exception
                );
            }
        }
        return data;
    }

    public static ParsingMap getRunningParsingMap() {
        CurrentScenarioState currentScenarioState =
                getCurrentScenarioState();
        if (currentScenarioState == null) {
            return getGlobalsParsingmap();
        }
        if (currentScenarioState.currentPhrase != null) {
            return currentScenarioState.currentPhrase
                    .getPhraseParsingMap();
        }
        try {
            return currentScenarioState.getCurrentStep()
                    .getStepParsingMap();
        } catch (Throwable ignored) {
            return currentScenarioState.getParsingMap();
        }
    }

    public static Object resolveFromParsingMap(Object input) {
        if (input instanceof String inputString) {
            return getRunningParsingMap()
                    .resolveWholeValue(inputString);
        }
        if (input instanceof JsonNode jsonNode
                && jsonNode.isTextual()) {
            return getRunningParsingMap()
                    .resolveWholeValue(jsonNode.textValue());
        }

        return input;
    }

    public static String resolveToStringWithRunningParsingMap(
            String input
    ) {
        if (input == null) {
            return null;
        }
        return getRunningParsingMap().resolveWholeText(input);
    }

    public static Object getFromRunningParsingMapCaseInsensitive(
            String key
    ) {
        if (key == null) {
            return null;
        }
        return getRunningParsingMap().getCaseInsensitive(key);
    }

    public static Object getFromRunningParsingMap(String key) {
        if (key == null) {
            return null;
        }
        return getRunningParsingMap().get(key);
    }

    public static Object
    getFromRunningParsingMapCaseInsensitiveOrDefault(
            String key,
            String defaultValue
    ) {
        if (key == null) {
            return defaultValue;
        }
        Object returnVal =
                getFromRunningParsingMapCaseInsensitive(key);
        return returnVal == null ? defaultValue : returnVal;
    }

    public static Object getFromRunningParsingMapOrDefault(
            String key,
            String defaultValue
    ) {
        if (key == null) {
            return defaultValue;
        }
        Object returnVal = getFromRunningParsingMap(key);
        return returnVal == null ? defaultValue : returnVal;
    }

    public static Object resolveFromDocStringOrConfig(String key) {
        StepExtension currentStep = GlobalState.getRunningStep();
        if (currentStep.argument instanceof DocStringArgument) {
            return valConverter.convert(
                    currentStep.argument.getValue()
            );
        }
        return getFromRunningParsingMapCaseInsensitive(
                CONFIGS_MAP_ROOT + "." + key
        );
    }

    public static NodeMap getRootScenarioStepNodeMap() {
        return getRootScenarioStep().getDefaultStepNodeMap();
    }

    public static NodeMap
    getClosestScenarioStepAncestorNodeMap() {
        return getClosestScenarioStepAncestor()
                .getDefaultStepNodeMap();
    }

    /**
     * Public parser/converter for the supported mapping directive grammar.
     * Runtime template resolution is integrated through {@link ParsingMap}; the
     * public helpers are also used by structured DocString and table-key mapping.
     */
    public static final class MappingDirectiveResolver {
        private static final Pattern REMOVED_UNQUOTE = Pattern.compile(
                "~unquote(?![A-Za-z-])"
        );
        private static final String MASK_OPEN = "~^^";
        private static final String MASK_CLOSE = "^^~";
        private static final Pattern TRAILING_DIRECTIVE = Pattern.compile(
                "~([A-Za-z][A-Za-z-]*);\\s*$"
        );
        private static final Pattern DIRECTIVE_TOKEN = Pattern.compile(
                "~[A-Za-z][A-Za-z-]*;"
        );
        private static final Pattern XML_CLOSING = Pattern.compile(
                "</\\s*[A-Za-z_][A-Za-z0-9_.-]*\\s*>"
        );
        private static final Pattern XML_SELF_CLOSING = Pattern.compile(
                "<\\s*[A-Za-z_][A-Za-z0-9_.-]*(?:\\s+[^<>]*?)?/\\s*>"
        );
        private static final Set<String> DIRECTIVES = Set.of(
                "UNRESOLVED",
                "UNQUOTED",
                "JSON",
                "JSON-STRING",
                "XML",
                "XML-STRING",
                "YAML",
                "YAML-STRING",
                "MAP",
                "LIST",
                "SET",
                "MULTIMAP",
                "DATATABLE",
                "DOCSTRING",
                "DATA",
                "STRING",
                "MERGE"
        );
        private static final Object MISSING = new Object();
        private static final int MAX_RESOLUTION_PASSES = 100;

        private MappingDirectiveResolver() {
        }

        public record DirectiveSpec(String base, List<String> directives) {
            public boolean has(String directive) {
                return directives.contains(directive.toUpperCase(Locale.ROOT));
            }
        }

        private record Reference(int start, int end, String body) {
            String fullText(String input) {
                return input.substring(start, end + 1);
            }
        }

        private record Resolution(
                Object value,
                boolean unresolved,
                boolean unquoted
        ) {
        }

        static String resolveText(
                ParsingMap owner,
                String input,
                boolean resolveEvaluations,
                String... delimiterReplacements
        ) {
            Object resolved = resolve(
                    owner,
                    input,
                    resolveEvaluations,
                    false,
                    delimiterReplacements
            );
            return resolved == null ? null : stringify(resolved);
        }

        static Object resolveValue(
                ParsingMap owner,
                String input,
                boolean resolveEvaluations,
                String... delimiterReplacements
        ) {
            return resolve(
                    owner,
                    input,
                    resolveEvaluations,
                    true,
                    delimiterReplacements
            );
        }

        private static Object resolve(
                ParsingMap owner,
                String input,
                boolean resolveEvaluations,
                boolean preserveWholeObject,
                String... delimiterReplacements
        ) {
            if (input == null) {
                return null;
            }
            rejectRemovedSyntax(input);
            if (!usesDefaultDelimiters(delimiterReplacements)
                    || !requiresDirectiveResolver(input)) {
                return preserveWholeObject
                        ? owner.legacyResolveWholeValue(
                                input,
                                resolveEvaluations,
                                delimiterReplacements
                        )
                        : owner.legacyResolveWholeText(
                                input,
                                resolveEvaluations,
                                delimiterReplacements
                        );
            }

            OpaqueStore opaque = new OpaqueStore();
            String masked = opaque.mask(input);
            Object resolved = resolveString(
                    owner,
                    masked,
                    resolveEvaluations,
                    preserveWholeObject,
                    opaque,
                    0
            );
            return opaque.restoreObject(resolved);
        }

        public static DirectiveSpec parseDirectiveSuffix(String input) {
            if (input == null) {
                return new DirectiveSpec(null, List.of());
            }
            rejectRemovedSyntax(input);
            String base = input;
            List<String> reversed = new ArrayList<>();
            while (true) {
                Matcher matcher = TRAILING_DIRECTIVE.matcher(base);
                if (!matcher.find()) {
                    break;
                }
                String directive = matcher.group(1).toUpperCase(Locale.ROOT);
                if (!DIRECTIVES.contains(directive)) {
                    throw new IllegalArgumentException(
                            "Unsupported mapping directive '~"
                                    + matcher.group(1) + ";'. Supported directives: "
                                    + DIRECTIVES
                    );
                }
                reversed.add(directive);
                base = base.substring(0, matcher.start()).stripTrailing();
            }
            Collections.reverse(reversed);
            return new DirectiveSpec(base, List.copyOf(reversed));
        }

        public static void validateTableKeyDirectives(DirectiveSpec spec) {
            if (spec.has("UNQUOTED")) {
                throw new IllegalArgumentException(
                        "Mapping behavior '~unquoted;' is only valid on a quoted "
                                + "template reference, not on a MAP TABLE VALUES row key."
                );
            }
        }

        public static void validateStructuredKeyDirectives(DirectiveSpec spec) {
            if (spec.has("UNRESOLVED") || spec.has("UNQUOTED") || spec.has("MERGE")) {
                throw new IllegalArgumentException(
                        "Structured object keys support conversion directives only. "
                                + "Put '~unresolved;' on the referenced value and use "
                                + "'~unquoted;' only for raw text splicing."
                );
            }
        }

        private static void validateReferenceDirectives(
                DirectiveSpec spec,
                String body
        ) {
            if (spec.has("MERGE")) {
                throw new IllegalArgumentException(
                        "Mapping behavior '~merge;' is only valid on destination keys "
                                + "used by NodeMap put operations, not on mapping "
                                + "references or pipeline stages: <" + body + ">"
                );
            }
        }

        public static Object resolveUnresolvedCarrier(String input) {
            if (input == null) {
                return null;
            }
            rejectRemovedSyntax(input);
            String trimmed = input.trim();
            if (trimmed.startsWith("<") && trimmed.endsWith(">")
                    && !trimmed.startsWith("<^~")) {
                String body = trimmed.substring(1, trimmed.length() - 1);
                return getRunningParsingMap().resolveWholeValue(
                        "<" + body + "~unresolved;>"
                );
            }
            if (trimmed.startsWith("~[~") && trimmed.endsWith("~]~")) {
                String body = trimmed.substring(3, trimmed.length() - 3);
                return getRunningParsingMap().resolveWholeValue(
                        "~[~" + body + "~unresolved;~]~"
                );
            }
            return input;
        }

        public static Object applyDirectives(
                Object value,
                List<String> directives
        ) {
            Object current = value;
            for (String directive : directives) {
                current = switch (directive) {
                    case "UNRESOLVED", "UNQUOTED", "MERGE" -> current;
                    case "JSON" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.JSON_DATA
                    );
                    case "JSON-STRING" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.JSON_STRING
                    );
                    case "XML" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.XML_DATA
                    );
                    case "XML-STRING" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.XML_STRING
                    );
                    case "YAML" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.YAML_DATA
                    );
                    case "YAML-STRING" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.YAML_STRING
                    );
                    case "DATA" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.STRUCTURED_DATA
                    );
                    case "STRING" -> StructuredDataConverter.convert(
                            current,
                            DataElementKind.DATA_STRING
                    );
                    case "MAP" -> toMap(current);
                    case "LIST" -> toList(current);
                    case "SET" -> toSet(current);
                    case "MULTIMAP" -> toMultimap(current);
                    case "DATATABLE" -> toDataTable(current);
                    case "DOCSTRING" -> toDocString(current);
                    default -> throw new IllegalArgumentException(
                            "Unsupported mapping directive '~" + directive + ";'."
                    );
                };
            }
            return current;
        }

        public static Object convertSpecialLiteral(Object value) {
            if (!(value instanceof String text)) {
                return value;
            }
            return switch (text.trim().toUpperCase(Locale.ROOT)) {
                case "<^~NULL~^>" -> NullNode.getInstance();
                case "<^~NAN~^>" -> Double.NaN;
                case "<^~INF~^>" -> Double.POSITIVE_INFINITY;
                case "<^~-INF~^>" -> Double.NEGATIVE_INFINITY;
                case "<^~TAB~^>" -> "\t";
                case "<^~EMPTY~^>" -> "";
                default -> value;
            };
        }

        public static void rejectRemovedSyntax(String input) {
            if (input != null && REMOVED_UNQUOTE.matcher(input).find()) {
                throw new IllegalArgumentException(
                        "Mapping suffix '~unquote' was removed. Use '~unquoted;' instead."
                );
            }
        }

        private static Object resolveString(
                ParsingMap owner,
                String input,
                boolean resolveEvaluations,
                boolean preserveWholeObject,
                OpaqueStore opaque,
                int depth
        ) {
            if (depth > MAX_RESOLUTION_PASSES) {
                throw new IllegalStateException(
                        "Mapping resolution exceeded " + MAX_RESOLUTION_PASSES
                                + " nested passes while resolving: " + input
                );
            }

            String current = input;
            Set<String> seen = new HashSet<>();
            for (int pass = 0; pass < MAX_RESOLUTION_PASSES; pass++) {
                if (!seen.add(current)) {
                    throw new IllegalStateException(
                            "Cyclic mapping directive resolution detected while resolving: "
                                    + input
                    );
                }
                Reference reference = findInnermostReference(current);
                if (reference == null) {
                    break;
                }

                Resolution resolution = resolveReference(
                        owner,
                        reference.body(),
                        resolveEvaluations,
                        opaque,
                        depth + 1
                );
                if (resolution.value() == MISSING) {
                    String protectedReference = opaque.protect(
                            reference.fullText(current)
                    );
                    current = replace(
                            current,
                            reference.start(),
                            reference.end() + 1,
                            protectedReference
                    );
                    continue;
                }

                Object value = resolution.value();
                boolean wholeReference = reference.start() == 0
                        && reference.end() == current.length() - 1;
                if (wholeReference
                        && preserveWholeObject
                        && !(value instanceof String)) {
                    if (resolution.unquoted()) {
                        throw new IllegalArgumentException(
                                "Mapping behavior '~unquoted;' requires the reference to be "
                                        + "directly surrounded by one matching quote pair: "
                                        + reference.fullText(current)
                        );
                    }
                    return value;
                }

                String replacement = stringify(value);
                if (resolution.unresolved()
                        && containsReferenceSyntax(replacement)) {
                    replacement = opaque.protect(replacement);
                }
                current = replaceReference(
                        current,
                        reference,
                        replacement,
                        resolution.unquoted()
                );
            }

            if (findInnermostReference(current) != null) {
                throw new IllegalStateException(
                        "Mapping resolution did not stabilize within "
                                + MAX_RESOLUTION_PASSES + " passes: " + input
                );
            }

            if (containsLegacyResolvableSyntax(current)) {
                Object legacy = preserveWholeObject
                        ? owner.legacyResolveWholeValue(
                                current,
                                resolveEvaluations
                        )
                        : owner.legacyResolveWholeText(
                                current,
                                resolveEvaluations
                        );
                return legacy;
            }
            return current;
        }

        private static Resolution resolveReference(
                ParsingMap owner,
                String body,
                boolean resolveEvaluations,
                OpaqueStore opaque,
                int depth
        ) {
            Object special = specialMarker(body);
            if (special != MISSING) {
                return new Resolution(special, false, false);
            }
            String trimmedBody = body.trim();
            if (trimmedBody.startsWith("^~") && trimmedBody.endsWith("~^")) {
                throw new IllegalArgumentException(
                        "Unsupported special mapping marker '<" + trimmedBody + ">'. "
                                + "Supported markers are <^~NULL~^>, <^~NAN~^>, "
                                + "<^~INF~^>, <^~-INF~^>, <^~TAB~^>, and <^~EMPTY~^>."
                );
            }

            List<String> pipeline = splitPipeline(body);
            DirectiveSpec sourceSpec = parseDirectiveSuffix(pipeline.getFirst());
            validateReferenceDirectives(sourceSpec, body);
            validateUnquotedTerminal(
                    sourceSpec,
                    pipeline.size() > 1,
                    body
            );
            String source = sourceSpec.base();
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                        "Mapping reference has no source before its directives: <" + body + ">"
                );
            }

            Object current;
            if (source.startsWith("value:")) {
                current = source.substring("value:".length());
            } else if (source.startsWith("&")) {
                logWarn(
                        "Mapping return-value reference '<&...>' is deprecated; "
                                + "prefer a named mapping or explicit dynamic-step result."
                );
                current = owner.legacyResolveWholeValue(
                        "<" + source + ">",
                        resolveEvaluations
                );
            } else if (source.startsWith("$")
                    || source.startsWith("{") && source.endsWith("}")) {
                current = owner.legacyResolveWholeValue(
                        "<" + source + ">",
                        resolveEvaluations
                );
            } else {
                current = owner.get(source);
            }

            if (current == null) {
                if (source.startsWith("?")) {
                    current = "";
                } else if (pipeline.size() > 1) {
                    throw new IllegalArgumentException(
                            "Mapping pipeline source '" + source
                                    + "' produced no value while resolving <" + body + ">"
                    );
                } else {
                    return new Resolution(MISSING, false, false);
                }
            }

            boolean unresolved = sourceSpec.has("UNRESOLVED");
            boolean unquoted = sourceSpec.has("UNQUOTED");
            current = resolveReturnedString(
                    owner,
                    current,
                    unresolved,
                    resolveEvaluations,
                    opaque,
                    depth
            );
            current = applyDirectives(current, sourceSpec.directives());

            for (int index = 1; index < pipeline.size(); index++) {
                DirectiveSpec querySpec = parseDirectiveSuffix(pipeline.get(index));
                validateReferenceDirectives(querySpec, body);
                String query = querySpec.base();
                validateUnquotedTerminal(
                        querySpec,
                        index < pipeline.size() - 1,
                        body
                );
                if (query == null || query.isBlank()) {
                    throw new IllegalArgumentException(
                            "Mapping pipeline contains an empty query stage in <" + body + ">"
                    );
                }
                current = applyQuery(current, query, body);
                unresolved = unresolved || querySpec.has("UNRESOLVED");
                unquoted = unquoted || querySpec.has("UNQUOTED");
                current = resolveReturnedString(
                        owner,
                        current,
                        querySpec.has("UNRESOLVED"),
                        resolveEvaluations,
                        opaque,
                        depth
                );
                current = applyDirectives(current, querySpec.directives());
            }

            return new Resolution(current, unresolved, unquoted);
        }

        private static void validateUnquotedTerminal(
                DirectiveSpec spec,
                boolean hasLaterPipelineStage,
                String body
        ) {
            int unquotedIndex = spec.directives().indexOf("UNQUOTED");
            if (unquotedIndex < 0) {
                return;
            }
            if (hasLaterPipelineStage
                    || unquotedIndex != spec.directives().size() - 1) {
                throw new IllegalArgumentException(
                        "Mapping behavior '~unquoted;' is terminal and must be the final "
                                + "directive on the final pipeline stage in <" + body + ">"
                );
            }
        }

        private static Object resolveReturnedString(
                ParsingMap owner,
                Object value,
                boolean unresolved,
                boolean resolveEvaluations,
                OpaqueStore opaque,
                int depth
        ) {
            if (unresolved || !(value instanceof String text) || text.isEmpty()) {
                return value;
            }
            rejectRemovedSyntax(text);
            if (!text.contains("<") && !text.contains("~[~")) {
                return value;
            }
            if (looksLikeXml(text) && !requiresDirectiveResolver(text)) {
                return owner.legacyResolveWholeValue(text, resolveEvaluations);
            }
            return resolveString(
                    owner,
                    opaque.mask(text),
                    resolveEvaluations,
                    true,
                    opaque,
                    depth + 1
            );
        }

        private static Object applyQuery(
                Object source,
                String query,
                String originalBody
        ) {
            JsonNode sourceNode = ValueFormatting.toSafeJsonNode(source);
            ObjectNode wrapper = MAPPER.createObjectNode();
            wrapper.set("_value", sourceNode);

            String trimmed = query.trim();
            String wrappedQuery = trimmed.startsWith("[")
                    ? "_value" + trimmed
                    : "_value." + trimmed.replaceFirst("^\\.", "");
            final Object result;
            try {
                result = new Tokenized(wrappedQuery).get(wrapper);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Mapping pipeline query '" + query
                                + "' failed while resolving <" + originalBody + ">: "
                                + exception.getMessage(),
                        exception
                );
            }
            if (result == null) {
                throw new IllegalArgumentException(
                        "Mapping pipeline query '" + query
                                + "' produced no value while resolving <"
                                + originalBody + ">"
                );
            }
            return result;
        }

        private static Object specialMarker(String body) {
            Object converted = convertSpecialLiteral("<" + body.trim() + ">");
            return converted instanceof String text
                    && text.equals("<" + body.trim() + ">")
                    ? MISSING
                    : converted;
        }

        private static String replaceReference(
                String input,
                Reference reference,
                String replacement,
                boolean unquoted
        ) {
            int start = reference.start();
            int endExclusive = reference.end() + 1;
            Character containingQuote = containingQuoteAt(input, start);
            boolean directlySurroundedByMatchingQuotes =
                    containingQuote != null
                            && start > 0
                            && endExclusive < input.length()
                            && input.charAt(start - 1) == containingQuote
                            && input.charAt(endExclusive) == containingQuote;

            if (unquoted) {
                if (!directlySurroundedByMatchingQuotes) {
                    throw new IllegalArgumentException(
                            "Mapping behavior '~unquoted;' requires the reference to be "
                                    + "directly surrounded by one matching quote pair: "
                                    + reference.fullText(input)
                    );
                }
                return replace(
                        input,
                        start - 1,
                        endExclusive + 1,
                        replacement
                );
            }

            if (containingQuote != null) {
                replacement = escapeForQuote(replacement, containingQuote);
            }
            return replace(input, start, endExclusive, replacement);
        }

        private static Character containingQuoteAt(String input, int index) {
            Character quote = null;
            boolean escaped = false;
            for (int cursor = 0; cursor < index; cursor++) {
                char current = input.charAt(cursor);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (current == '\\') {
                    escaped = true;
                    continue;
                }
                if (quote == null) {
                    if (isQuote(current)) {
                        quote = current;
                    }
                } else if (current == quote) {
                    quote = null;
                }
            }
            return quote;
        }

        private static String replace(
                String input,
                int start,
                int endExclusive,
                String replacement
        ) {
            return input.substring(0, start)
                    + replacement
                    + input.substring(endExclusive);
        }

        private static String escapeForQuote(String input, char quote) {
            String escaped = input.replace("\\", "\\\\");
            return escaped.replace(
                    String.valueOf(quote),
                    "\\" + quote
            );
        }

        private static boolean isQuote(char value) {
            return value == '\'' || value == '"' || value == '`';
        }

        private static Reference findInnermostReference(String input) {
            if (!looksLikeXml(input)) {
                List<Integer> opens = new ArrayList<>();
                for (int index = 0; index < input.length(); index++) {
                    char current = input.charAt(index);
                    if (current == '<' && isReferenceOpen(input, index)) {
                        opens.add(index);
                        continue;
                    }
                    if (current == '>' && !opens.isEmpty() && isReferenceClose(input, index)) {
                        int start = opens.removeLast();
                        return new Reference(
                                start,
                                index,
                                input.substring(start + 1, index)
                        );
                    }
                }
            }

            int close = input.indexOf("~]~");
            if (close < 0) {
                return null;
            }
            int start = input.lastIndexOf("~[~", close);
            if (start < 0) {
                throw new IllegalArgumentException(
                        "Mapping text contains '~]~' without a matching '~[~': " + input
                );
            }
            return new Reference(
                    start,
                    close + 2,
                    input.substring(start + 3, close)
            );
        }

        private static boolean isReferenceOpen(String input, int index) {
            if (index + 1 >= input.length()) {
                return false;
            }
            char next = input.charAt(index + 1);
            return !Character.isWhitespace(next) && next != '=';
        }

        private static boolean isReferenceClose(String input, int index) {
            return index > 0 && !Character.isWhitespace(input.charAt(index - 1));
        }

        private static List<String> splitPipeline(String body) {
            body = decodeDirectiveQuotedText(body);
            List<String> segments = new ArrayList<>();
            List<Character> structures = new ArrayList<>();
            int start = 0;
            char quote = 0;
            boolean escaped = false;
            for (int index = 0; index < body.length(); index++) {
                char current = body.charAt(index);
                if (quote != 0) {
                    if (escaped) {
                        escaped = false;
                    } else if (current == '\\') {
                        escaped = true;
                    } else if (current == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (isQuote(current)) {
                    quote = current;
                    continue;
                }
                if (current == '{' || current == '[' || current == '(') {
                    structures.add(current);
                    continue;
                }
                if (current == '}' || current == ']' || current == ')') {
                    if (structures.isEmpty()
                            || !matchingStructure(structures.getLast(), current)) {
                        throw new IllegalArgumentException(
                                "Unbalanced structured value in mapping reference: <"
                                        + body + ">"
                        );
                    }
                    structures.removeLast();
                    continue;
                }
                if (structures.isEmpty()
                        && current == ':'
                        && index + 1 < body.length()
                        && body.charAt(index + 1) == ':') {
                    segments.add(body.substring(start, index));
                    start = index + 2;
                    index++;
                }
            }
            if (quote != 0 || !structures.isEmpty()) {
                throw new IllegalArgumentException(
                        "Unbalanced quoted or structured value in mapping reference: <"
                                + body + ">"
                );
            }
            segments.add(body.substring(start));
            return segments;
        }

        private static String decodeDirectiveQuotedText(String input) {
            if (input == null || !input.contains("\\\"")) {
                return input;
            }
            StringBuilder output = new StringBuilder(input.length());
            for (int index = 0; index < input.length(); index++) {
                char current = input.charAt(index);
                if (current == '\\'
                        && index + 1 < input.length()
                        && input.charAt(index + 1) == '"') {
                    output.append('"');
                    index++;
                } else {
                    output.append(current);
                }
            }
            return output.toString();
        }

        private static boolean matchingStructure(char open, char close) {
            return open == '{' && close == '}'
                    || open == '[' && close == ']'
                    || open == '(' && close == ')';
        }

        private static boolean requiresDirectiveResolver(String input) {
            if (input == null || input.isEmpty()) {
                return false;
            }
            if (input.contains(MASK_OPEN)
                    || input.contains("::")
                    || input.contains("<value:")
                    || input.contains("~[~value:")
                    || input.contains("<^~")
                    || input.contains("<&")
                    || input.contains("~[~&")) {
                return true;
            }
            if (DIRECTIVE_TOKEN.matcher(input).find()) {
                return true;
            }
            return containsNestedReference(input);
        }


        private static boolean containsReferenceSyntax(String input) {
            return input != null
                    && (input.contains("<") || input.contains("~[~"));
        }

        private static boolean containsNestedReference(String input) {
            int depth = 0;
            for (int index = 0; index < input.length(); index++) {
                if (input.charAt(index) == '<' && isReferenceOpen(input, index)) {
                    depth++;
                    if (depth > 1) {
                        return true;
                    }
                } else if (input.charAt(index) == '>'
                        && depth > 0
                        && isReferenceClose(input, index)) {
                    depth--;
                }
            }
            return false;
        }

        private static boolean containsLegacyResolvableSyntax(String input) {
            return input.contains("~[~") || input.contains("<{");
        }

        private static boolean usesDefaultDelimiters(String[] replacements) {
            if (replacements == null || replacements.length == 0) {
                return true;
            }
            boolean open = replacements.length < 1 || replacements[0] == null;
            boolean close = replacements.length < 2 || replacements[1] == null;
            return open && close;
        }

        private static boolean looksLikeXml(String input) {
            return input != null
                    && (XML_CLOSING.matcher(input).find()
                    || XML_SELF_CLOSING.matcher(input).find());
        }

        private static String stringify(Object value) {
            if (value == null || value instanceof NullNode) {
                return "";
            }
            if (value instanceof DataTable || value instanceof DocString) {
                return ValueFormatting.toReferenceText(value);
            }
            if (value instanceof JsonNode node) {
                if (node.isTextual()) {
                    return node.textValue();
                }
                if (node.isValueNode()) {
                    return node.asText("");
                }
                return node.toString();
            }
            if (value instanceof Map<?, ?>
                    || value instanceof Collection<?>
                    || value instanceof Multimap<?, ?>
                    || value.getClass().isArray()) {
                try {
                    return MAPPER.writeValueAsString(value);
                } catch (JsonProcessingException ignored) {
                    return String.valueOf(value);
                }
            }
            return String.valueOf(value);
        }

        private static Object toMap(Object value) {
            if (value instanceof Multimap<?, ?> multimap) {
                return new LinkedHashMap<>(multimap.asMap());
            }
            Object normalized = javaConversionSource(value);
            if (normalized instanceof Map<?, ?> map) {
                return new LinkedHashMap<>(map);
            }
            if (normalized instanceof JsonNode node && node.isObject()) {
                return MAPPER.convertValue(
                        node,
                        new TypeReference<LinkedHashMap<String, Object>>() {
                        }
                );
            }
            throw conversionError("MAP", value);
        }

        private static Object toList(Object value) {
            Object normalized = javaConversionSource(value);
            if (normalized instanceof List<?> list) {
                return new ArrayList<>(list);
            }
            if (normalized instanceof Collection<?> collection) {
                return new ArrayList<>(collection);
            }
            if (normalized instanceof JsonNode node && node.isArray()) {
                return MAPPER.convertValue(
                        node,
                        new TypeReference<ArrayList<Object>>() {
                        }
                );
            }
            if (normalized != null && normalized.getClass().isArray()) {
                int length = Array.getLength(normalized);
                List<Object> result = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    result.add(Array.get(normalized, index));
                }
                return result;
            }
            throw conversionError("LIST", value);
        }

        private static Object toSet(Object value) {
            Object list = toList(value);
            return new LinkedHashSet<>((List<?>) list);
        }

        private static Object toMultimap(Object value) {
            if (value instanceof Multimap<?, ?> existing) {
                LinkedListMultimap<Object, Object> copy = LinkedListMultimap.create();
                existing.entries().forEach(
                        entry -> copy.put(entry.getKey(), entry.getValue())
                );
                return copy;
            }
            Object mapValue = toMap(value);
            LinkedListMultimap<Object, Object> multimap = LinkedListMultimap.create();
            ((Map<?, ?>) mapValue).forEach((key, rawValue) -> {
                if (rawValue instanceof Collection<?> collection) {
                    collection.forEach(item -> multimap.put(key, item));
                } else {
                    multimap.put(key, rawValue);
                }
            });
            return multimap;
        }

        private static Object toDataTable(Object value) {
            if (value instanceof DataTable dataTable) {
                return dataTable;
            }
            Object normalized = javaConversionSource(value);
            JsonNode node = normalized instanceof JsonNode jsonNode
                    ? jsonNode
                    : MAPPER.valueToTree(normalized);
            try {
                return jsonNodeToDataTable(node);
            } catch (RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Could not convert value to DATATABLE: " + value,
                        exception
                );
            }
        }

        private static Object toDocString(Object value) {
            if (value instanceof DocString docString) {
                return docString;
            }
            boolean structured = value instanceof JsonNode
                    || value instanceof Map<?, ?>
                    || value instanceof Collection<?>
                    || value instanceof Multimap<?, ?>
                    || value instanceof DataTable
                    || value != null && value.getClass().isArray();
            Object content = value instanceof DataTable dataTable
                    ? dataTableToJsonNode(dataTable)
                    : value;
            return DocString.create(
                    stringify(content),
                    structured ? "json" : null
            );
        }

        private static Object javaConversionSource(Object value) {
            if (value instanceof DataTable dataTable) {
                return dataTableToJsonNode(dataTable);
            }
            if (value instanceof DocString docString) {
                return StructuredDataConverter.convert(
                        docString,
                        DataElementKind.STRUCTURED_DATA
                );
            }
            if (value instanceof String text) {
                return StructuredDataConverter.convert(
                        text,
                        DataElementKind.STRUCTURED_DATA
                );
            }
            return value;
        }

        private static IllegalArgumentException conversionError(
                String directive,
                Object value
        ) {
            return new IllegalArgumentException(
                    "Could not convert value to " + directive + ": " + value
            );
        }

        private static final class OpaqueStore {
            private static final char TOKEN_OPEN = '\uE100';
            private static final char TOKEN_CLOSE = '\uE101';
            private final Map<String, String> values = new LinkedHashMap<>();
            private int nextId;

            String mask(String input) {
                if (input == null || !input.contains(MASK_OPEN)) {
                    return input;
                }
                StringBuilder output = new StringBuilder(input.length());
                int cursor = 0;
                while (cursor < input.length()) {
                    int start = input.indexOf(MASK_OPEN, cursor);
                    if (start < 0) {
                        output.append(input, cursor, input.length());
                        break;
                    }
                    output.append(input, cursor, start);
                    int end = input.indexOf(MASK_CLOSE, start + MASK_OPEN.length());
                    if (end < 0) {
                        throw new IllegalArgumentException(
                                "Unclosed mapping mask starting at index " + start
                                        + ". Expected '" + MASK_CLOSE + "'."
                        );
                    }
                    String literal = input.substring(
                            start + MASK_OPEN.length(),
                            end
                    );
                    output.append(protect(literal));
                    cursor = end + MASK_CLOSE.length();
                }
                return output.toString();
            }

            String protect(String value) {
                String token = TOKEN_OPEN + Integer.toString(nextId++) + TOKEN_CLOSE;
                values.put(token, value);
                return token;
            }

            String restore(String input) {
                if (input == null || values.isEmpty()) {
                    return input;
                }
                String output = input;
                boolean changed;
                do {
                    changed = false;
                    for (Map.Entry<String, String> entry : values.entrySet()) {
                        if (output.contains(entry.getKey())) {
                            output = output.replace(entry.getKey(), entry.getValue());
                            changed = true;
                        }
                    }
                } while (changed && containsToken(output));
                return output;
            }

            Object restoreObject(Object value) {
                if (value instanceof String text) {
                    return restore(text);
                }
                if (value instanceof JsonNode node) {
                    return restoreJson(node);
                }
                if (value instanceof DataTable table) {
                    List<List<String>> cells = table.cells().stream()
                            .map(row -> row.stream().map(this::restore).toList())
                            .toList();
                    return DataTable.create(cells);
                }
                if (value instanceof DocString docString) {
                    return DocString.create(
                            restore(docString.getContent()),
                            docString.getContentType()
                    );
                }
                if (value instanceof Map<?, ?> map) {
                    Map<Object, Object> restored = new LinkedHashMap<>();
                    map.forEach((key, item) -> restored.put(
                            key instanceof String text ? restore(text) : key,
                            restoreObject(item)
                    ));
                    return restored;
                }
                if (value instanceof List<?> list) {
                    return list.stream().map(this::restoreObject).toList();
                }
                if (value instanceof Set<?> set) {
                    LinkedHashSet<Object> restored = new LinkedHashSet<>();
                    set.forEach(item -> restored.add(restoreObject(item)));
                    return restored;
                }
                return value;
            }

            private JsonNode restoreJson(JsonNode node) {
                if (node.isTextual()) {
                    return TextNode.valueOf(restore(node.textValue()));
                }
                if (node instanceof ObjectNode object) {
                    ObjectNode restored = MAPPER.createObjectNode();
                    object.fields().forEachRemaining(entry -> restored.set(
                            restore(entry.getKey()),
                            restoreJson(entry.getValue())
                    ));
                    return restored;
                }
                if (node instanceof ArrayNode array) {
                    ArrayNode restored = MAPPER.createArrayNode();
                    array.forEach(item -> restored.add(restoreJson(item)));
                    return restored;
                }
                return node;
            }

            private static boolean containsToken(String value) {
                return value.indexOf(TOKEN_OPEN) >= 0;
            }
        }
    }
}
