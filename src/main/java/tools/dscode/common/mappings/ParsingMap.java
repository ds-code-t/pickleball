package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.GlobalState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.ScenarioStepData;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.treeparsing.parsedComponents.ElementMatch;

import java.util.List;
import static io.cucumber.core.runner.GlobalState.getClosestScenarioStepAncestor;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getRootScenarioStep;
import static io.cucumber.core.runner.util.TableUtils.DATA_OBJECT_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;
import static tools.dscode.common.mappings.custommappings.ValConverter.valConverter;
import static tools.dscode.coredefinitions.DataTableDefinitions.dataTableToJsonNode;
import static tools.dscode.coredefinitions.DocStringDefinitions.docStringtoJsonNode;

public class ParsingMap extends MappingProcessor {
    private static final String DATA_REFERENCE_PREFIX = "data:";
    private final static ParsingMap GLOBALS_PARSINGMAP = new ParsingMap(GLOBALS);
    public static final String configsRoot = "configs";

    static {
        // Skip template resolution: configs may contain late-bound placeholders
        // (e.g. <$ScenarioNameAndLine>) that require a running step.
        JsonNode configsNode = FileAndDataParsing.buildJsonFromPath(configsRoot, false);
        GLOBALS.root.set(configsRoot, configsNode);
    }
    public static ParsingMap getGlobalsParsingmap() {
        return GLOBALS_PARSINGMAP;
    }


    public List<NodeMap> getNodeMaps(MapConfigurations.MapType mapType) {
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
        return dataTable != null ? dataTable : data.getDocStringValue(null);
    }

    @Override
    public List<?> get(ElementMatch element) {
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
            return dataTable == null ? List.of() : List.of(dataTable);
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

    private static Object nearestUnnamedMarkerData(boolean dataTableOnly) {
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
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(
                        "Could not convert Doc String to Data",
                        e
                );
            }
        }
        return data;
    }

    public static ParsingMap getRunningParsingMap() {
        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
        if (currentScenarioState == null)
            return getGlobalsParsingmap();
        if (currentScenarioState.currentPhrase != null)
            return currentScenarioState.currentPhrase.getPhraseParsingMap();
        try {
            return currentScenarioState.getCurrentStep().getStepParsingMap();
        } catch (Throwable e) {
            return currentScenarioState.getParsingMap();
        }
    }

    public static Object resolveFromParsingMap(Object input) {
        if (input instanceof String inputString) {
            return getRunningParsingMap().resolveWholeValue(inputString);
        }

        if (input instanceof JsonNode jsonNode && jsonNode.isTextual()) {
            return getRunningParsingMap().resolveWholeValue(jsonNode.textValue());
        }

        return input;
    }

    public static String resolveToStringWithRunningParsingMap(String input) {
        if (input == null) return null;
        return getRunningParsingMap().resolveWholeText(input);
    }

    public static Object getFromRunningParsingMapCaseInsensitive(String key) {
        if (key == null) return null;
        return getRunningParsingMap().getCaseInsensitive(key);
    }
    public static Object getFromRunningParsingMap(String key) {
        if (key == null) return null;
        return getRunningParsingMap().get(key);
    }


    public static Object getFromRunningParsingMapCaseInsensitiveOrDefault(String key, String defaultValue) {
        if (key == null) return defaultValue;
        Object returnVal = getRunningParsingMap().getCaseInsensitive(key);
        if (returnVal == null) return defaultValue;
        return returnVal;
    }
    public static Object getFromRunningParsingMapOrDefault(String key, String defaultValue) {
        if (key == null) return defaultValue;
        Object returnVal = getRunningParsingMap().get(key);
        if (returnVal == null) return defaultValue;
        return returnVal;
    }
    public static Object resolveFromDocStringOrConfig(String key) {
        StepExtension currentStep = GlobalState.getRunningStep();
        if (currentStep.argument instanceof DocStringArgument)
            return valConverter.convert(currentStep.argument.getValue());
        else
            return getFromRunningParsingMapCaseInsensitive(configsRoot + "." + key);
    }


    public static NodeMap getRootScenarioStepNodeMap() {
        return getRootScenarioStep().getDefaultStepNodeMap();
    }
    public static NodeMap getClosestScenarioStepAncestorNodeMap() {
        return getClosestScenarioStepAncestor().getDefaultStepNodeMap();
    }
}
