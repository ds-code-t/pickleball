package tools.dscode.common.mappings;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimaps;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepBase;


import java.util.EnumSet;
import java.util.Set;

import static tools.dscode.common.evaluations.AviatorUtil.eval;

public abstract class StepMapping extends StepBase {

    public NodeMap getDefaultStepNodeMap() {
        return defaultStepNodeMap;
    }

    public void mergeToStepNodeMap(LinkedListMultimap<?, ?> obj) {
        defaultStepNodeMap.merge(obj);
    }

    public void put(Object key, Object value) {
        if (key == null || (key instanceof String && ((String) key).isBlank()))
            throw new RuntimeException("key cannot be null or blank");
        defaultStepNodeMap.put(String.valueOf(key), value);
    }

    public void setStepParsingMap(ParsingMap inputParsingMap) {
        copyParsingMap(inputParsingMap);
        if (dataContextStepNodeMap != null)
            this.stepParsingMap.addMapsToStart(dataContextStepNodeMap);
        this.stepParsingMap.addMapsToStart(defaultStepNodeMap);
    }

    public void addToStepParsingMap(NodeMap... nodes) {
        this.stepParsingMap.addMaps(nodes);
    }

    public String evalWithStepMaps(String expression) {
        return String.valueOf(eval(expression, getStepParsingMap()));
    }

    static final  Set<MapConfigurations.MapType> includeStepMaps = EnumSet.of(MapConfigurations.MapType.STEP_MAP, MapConfigurations.MapType.PHRASE_MAP);
    static final  Set<MapConfigurations.MapType> includeStepExamplePassedMaps = EnumSet.of(MapConfigurations.MapType.STEP_MAP, MapConfigurations.MapType.EXAMPLE_MAP, MapConfigurations.MapType.PASSED_MAP, MapConfigurations.MapType.PHRASE_MAP);


    public void copyParsingMap(ParsingMap inputParsingMap) {
        this.stepParsingMap.removeMaps(MapConfigurations.MapType.STEP_MAP, MapConfigurations.MapType.PHRASE_MAP);
        if (this instanceof ScenarioStep) {
            this.stepParsingMap.maps.putAll(Multimaps.filterKeys(inputParsingMap.getMaps(), includeStepMaps::contains));
        } else {
            this.stepParsingMap.maps.putAll(Multimaps.filterKeys(inputParsingMap.getMaps(), includeStepExamplePassedMaps::contains));
        }
    }

    public static ParsingMap copytoNewParsingMap( ParsingMap parsingMap) {
        ParsingMap newParsingMap = new ParsingMap();
        newParsingMap.maps.putAll(Multimaps.filterKeys(parsingMap.getMaps(), includeStepExamplePassedMaps::contains));
        return newParsingMap;
    }
}
