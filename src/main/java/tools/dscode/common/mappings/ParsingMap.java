package tools.dscode.common.mappings;

import com.fasterxml.jackson.databind.JsonNode;
import io.cucumber.core.runner.CurrentScenarioState;

import java.util.List;

import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static tools.dscode.common.mappings.GlobalMappings.GLOBALS;

public class ParsingMap extends MappingProcessor {

    private final static ParsingMap GLOBALS_PARSINGMAP = new ParsingMap(GLOBALS);
    public static final String configsRoot = "configs";

    static {
        JsonNode configsNode = FileAndDataParsing.buildJsonFromPath(configsRoot);
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





    public static ParsingMap getRunningParsingMap() {
        CurrentScenarioState currentScenarioState = getCurrentScenarioState();
        if(currentScenarioState == null)
            return getGlobalsParsingmap();
        if(currentScenarioState.currentPhrase != null)
            return currentScenarioState.currentPhrase.getPhraseParsingMap();
        try {
            return currentScenarioState.getCurrentStep().getStepParsingMap();
        } catch (Throwable e) {
            return currentScenarioState.getParsingMap();
        }
    }

    public static String resolveToStringWithRunningParsingMap(String input) {
        return getRunningParsingMap().resolveWholeText(input);
    }

    public static Object getFromRunningParsingMapCaseInsensitive(String key) {
        return getRunningParsingMap().getCaseInsensitive(key);
    }

    public static Object getFromRunningParsingMap(String key) {
        return getCurrentScenarioState().getParsingMap().get(key);
    }



}
