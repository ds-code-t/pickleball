package tools.dscode.common.mappings;

import com.google.common.collect.LinkedListMultimap;

import static tools.dscode.common.evaluations.AviatorUtil.eval;
import static tools.dscode.common.mappings.MapConfigurations.MapType.STEP_MAP;

public class StepMapping {

    private final ParsingMap stepParsingMap = new ParsingMap();

    public ParsingMap getStepParsingMap() {
        return stepParsingMap;
    }

    public NodeMap getStepNodeMap() {
        return stepNodeMap;
    }

    private final NodeMap stepNodeMap = new NodeMap(STEP_MAP);

    public void mergeToStepMap(LinkedListMultimap<?, ?> obj) {
        stepNodeMap.merge(obj);
    }

    public void put(Object key, Object value) {
        if (key == null || (key instanceof String && ((String) key).isBlank()))
            throw new RuntimeException("key cannot be null or blank");
        stepNodeMap.put(String.valueOf(key), value);
    }

    public void setStepParsingMap(ParsingMap stepParsingMap) {
        System.out.println("@@setStepParsingMap for " + this);
        this.stepParsingMap.copyParsingMap(stepParsingMap);
        // this.stepParsingMap = stepParsingMap;
        this.stepParsingMap.addMaps(stepNodeMap);
        System.out.println("@@setStepParsingMap " + stepParsingMap);
    }

    public void addToStepParsingMap(NodeMap... nodes) {
        System.out.println("@@this.stepParsingMap1: " + this.stepParsingMap);
        this.stepParsingMap.addMaps(nodes);
        System.out.println("@@this.stepParsingMap2: " + this.stepParsingMap);
    }

    public String evalWithStepMaps(String expression) {
        return String.valueOf(eval(expression, getStepParsingMap()));
    }
}
