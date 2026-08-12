package tools.dscode.common.mappings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import tools.dscode.common.treeparsing.parsedComponents.DataElementMatch;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;
import java.util.List;
import java.util.Optional;
import static io.cucumber.core.runner.GlobalState.getClosestScenarioStepAncestor;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRootScenarioStep;
import static io.cucumber.core.runner.util.TableUtils.DATA_OBJECT_KEY;
import static io.cucumber.core.runner.util.TableUtils.ROW_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.custommappings.ValConverter.valConverter;
import static tools.dscode.coredefinitions.DataTableDefinitions.dataTableToJsonNode;
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
    private ParsingMap(NodeMap nodeMap) {
        super(nodeMap);
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
}
