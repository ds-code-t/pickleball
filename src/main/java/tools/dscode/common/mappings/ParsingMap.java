package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.ScenarioStepData;
import io.cucumber.core.runner.StepBase;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.assertions.ValueWrapper;
import tools.dscode.common.dataelements.DataElementKind;
import tools.dscode.common.dataelements.DataElementRuntime;
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
    private static final DataElementRuntime DATA_ELEMENT_RUNTIME =
            new DataElementRuntime();
    private static final ParsingMap GLOBALS_PARSINGMAP =
            new ParsingMap(GLOBALS);

    public static final String configsRoot = "configs";

    static {
        JsonNode configsNode = FileAndDataParsing.buildJsonFromPath(
                configsRoot,
                false
        );
        GLOBALS.root.set(configsRoot, configsNode);
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
        Object value = super.get(key);
        String query = extractMapPrefix(key).key();
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
    public List<?> get(ElementMatch element) {
        Optional<DataElementMatch> dataElement =
                DataElementMatch.from(element);
        if (dataElement.isPresent()
                && DataElementRuntime.supports(
                        dataElement.get().registration().kind()
                )) {
            Object source = resolveTabularSource(dataElement.get());
            return DATA_ELEMENT_RUNTIME
                    .execute(source, dataElement.get())
                    .values();
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

    private Object resolveTabularSource(
            DataElementMatch element
    ) {
        DataElementKind kind = element.registration().kind();
        boolean noQuotedText = element.defaultText == null
                || element.defaultText.isNullOrBlank();

        if (kind == DataElementKind.DATA_TABLE) {
            if (!noQuotedText) {
                return resolveExplicitDataSource(element);
            }

            Object active = activeTable(element);
            if (active != null) {
                return active;
            }
            return nearestUnnamedMarkerData(true);
        }

        Object active = activeTable(element);
        if (active != null) {
            return active;
        }

        NodeMap phraseMap = getPhraseMap();
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

    private Object resolveExplicitDataSource(
            DataElementMatch element
    ) {
        List<ValueWrapper> values = (List<ValueWrapper>) super.get(element);
        return values == null || values.isEmpty()
                ? null
                : values.get(0).getValue();
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

        StepExtension markerStep = nearestUnnamedMarkerStep(
                scenarioStep,
                currentStep
        );
        if (markerStep == null) {
            return null;
        }

        ScenarioStepData markerData = new ScenarioStepData(
                scenarioStep,
                markerStep
        );
        Object dataTable = markerData.getDataTableValue(null);
        if (dataTableOnly || dataTable != null) {
            return dataTable;
        }
        return markerData.getDocStringValue(null);
    }

    private static StepExtension nearestUnnamedMarkerStep(
            ScenarioStep scenarioStep,
            StepExtension currentStep
    ) {
        StepBase branch = currentStep;
        while (branch != null && branch != scenarioStep) {
            StepBase sibling = branch.previousSibling;
            while (sibling != null) {
                if (sibling instanceof StepExtension step
                        && step.isStepMarker
                        && isUnnamedMarker(step.stepMarkerText)) {
                    return step;
                }
                sibling = sibling.previousSibling;
            }
            branch = branch.parentStep;
        }
        return null;
    }

    private static boolean isUnnamedMarker(String markerText) {
        return markerText == null
                || markerText.chars().allMatch(character ->
                character == '-' || Character.isWhitespace(character)
        );
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
                configsRoot + "." + key
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
