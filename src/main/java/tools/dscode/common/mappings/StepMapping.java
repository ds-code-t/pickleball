package tools.dscode.common.mappings;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimaps;
import io.cucumber.core.runner.ScenarioStep;
import tools.dscode.common.treeparsing.PhraseExecution;

import java.util.EnumSet;
import java.util.Set;

import static tools.dscode.common.evaluations.AviatorUtil.eval;
import static tools.dscode.common.mappings.MapConfigurations.MapType.STEP_MAP;
import static tools.dscode.common.util.DebugUtils.printDebug;

public class StepMapping {

    private final ParsingMap stepParsingMap = new ParsingMap();


    public ParsingMap getStepParsingMap() {
        return stepParsingMap;
    }

    public NodeMap getStepNodeMap() {
        return stepNodeMap;
    }

    private final NodeMap stepNodeMap = new NodeMap(STEP_MAP);

    public void mergeToStepNodeMap(LinkedListMultimap<?, ?> obj) {
        stepNodeMap.merge(obj);
    }

    public void put(Object key, Object value) {
        if (key == null || (key instanceof String && ((String) key).isBlank()))
            throw new RuntimeException("key cannot be null or blank");
        stepNodeMap.put(String.valueOf(key), value);
    }

    public void setStepParsingMap(ParsingMap stepParsingMap) {

        copyParsingMap(stepParsingMap);



        this.stepParsingMap.addMaps(stepNodeMap);

    }

    public void addToStepParsingMap(NodeMap... nodes) {
        this.stepParsingMap.addMaps(nodes);
    }

    public String evalWithStepMaps(String expression) {
        System.out.println("@@evalWithStepMaps: " + expression + "");
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
}
