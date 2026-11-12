package tools.dscode.engine.runtime;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.ScenarioStep;
import io.cucumber.core.runner.StepExtension;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.engine.EngineLog;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.StepMatch;
import tools.dscode.engine.match.StepRegistry;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;

/** Builds a native {@link ScenarioStep} from a resolved neutral scenario. */
public final class NativeModularRun {
    private NativeModularRun() {
    }

    public static ScenarioStep materialize(
            EngineScenario scenario,
            Map<String, String> passedValues,
            boolean includeBackground,
            String startMarker,
            BiConsumer<ScenarioStep, Map<String, String>> initializer
    ) {
        ScenarioStep parent = new ScenarioStep(scenario == null ? "" : scenario.name());
        ParsingMap parsingMap = new ParsingMap();
        NodeMap passed = new NodeMap(MapConfigurations.MapType.PASSED_MAP);
        if (passedValues != null) passed.merge(passedValues);
        parsingMap.addMaps(passed);
        if (scenario != null) {
            String headers = scenario.metadata().get("exampleHeaders");
            String values = scenario.metadata().get("exampleValues");
            if (headers != null && values != null) {
                NodeMap examples = new NodeMap(MapConfigurations.MapType.EXAMPLE_MAP);
                examples.merge(Arrays.asList(headers.split("\t", -1)), Arrays.asList(values.split("\t", -1)));
                parsingMap.addMaps(examples);
            }
        }
        ExecutionContext context = ExecutionContext.current();
        StepRegistry registry = context == null ? null : registry(context);
        CurrentScenarioState state = context == null ? null : context.legacy();
        List<StepExtension> frames = new ArrayList<>();
        if (scenario != null) {
            for (EngineStep step : scenario.steps()) {
                if (!includeBackground && step.fromBackground()) continue;
                StepExtension frame = state == null
                        ? new StepExtension(step.keyword(), step.text())
                        : NativeScenarioRun.frame(step, registry, state);
                if (frame.isStepMarker && registry != null) {
                    StepMatch match = registry.match(step.text());
                    if (!match.captures().isEmpty() && match.captures().getFirst() != null) {
                        frame.stepMarkerText = match.captures().getFirst();
                    }
                }
                frames.add(frame);
            }
        }
        parent.installNativeSteps(frames, parsingMap, startMarker);
        parent.setStepParsingMap(getRunningParsingMap());
        if (initializer != null) initializer.accept(parent, passedValues);
        EngineLog.log(null, "run", "modular", parent.getStepText());
        return parent;
    }

    private static StepRegistry registry(ExecutionContext context) {
        Object registry = context.resources().get("registry");
        return registry instanceof StepRegistry steps ? steps : null;
    }
}
