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

    public void setStepParsingMap(ParsingMap stepParsingMap) {
        copyParsingMap(stepParsingMap);
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

    public void copyParsingMap(ParsingMap parsingMap) {
        this.stepParsingMap.clear();
        this.stepParsingMap.keyOrder.clear();
        if (this instanceof ScenarioStep) {
            Set<MapConfigurations.MapType> exclude = EnumSet.of(MapConfigurations.MapType.PASSED_MAP, MapConfigurations.MapType.EXAMPLE_MAP);
            this.stepParsingMap.maps.putAll(Multimaps.filterKeys(parsingMap.getMaps(), k -> !exclude.contains(k)));
            this.stepParsingMap.keyOrder.addAll(parsingMap.keyOrder());
        } else {
            this.stepParsingMap.maps.putAll(parsingMap.getMaps());
            this.stepParsingMap.keyOrder.addAll(parsingMap.keyOrder());
        }
    }

    public static ParsingMap copytoNewParsingMap( ParsingMap parsingMap) {
        ParsingMap newParsingMap = new ParsingMap();
        newParsingMap.clear();
        newParsingMap.keyOrder.clear();
        newParsingMap.maps.putAll(parsingMap.getMaps());
        newParsingMap.keyOrder.addAll(parsingMap.keyOrder());
        return newParsingMap;
    }
}
