package tools.dscode.extensions;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.PickleStepTestStep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static tools.dscode.common.GlobalConstants.META_FLAG;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;
import static tools.dscode.util.cucumberutils.StepBuilder.createStepExtension;

public class ScenarioStep extends StepExtension {

    public ScenarioStep(Pickle pickle, boolean isRoot, int nestingLevel) {
        super(createStepExtension((PickleStepTestStep) pickle.getSteps().getFirst(),
            isRoot ? ROOT_STEP : META_FLAG + "SCENARIO: " + pickle.getName()));
        setNestingLevel(nestingLevel);
        isScenarioNameStep = true;
        List<StepExtension> steps = new ArrayList<>();
        pickle.getSteps().forEach(step -> steps.add(new StepExtension((PickleStepTestStep) step, pickle)));
        int size = steps.size();

        Map<Integer, StepExtension> nestingMap = new HashMap<>();
        nestingMap.put(nestingLevel, this);
        int lastNestingLevel = 0;
        int startingNesting = nestingLevel + 1;
        for (int s = 0; s < size; s++) {
            StepExtension currentStep = steps.get(s);
            currentStep.setNestingLevel(currentStep.getNestingLevel() + startingNesting);
            // int currentNesting = currentStep.nestingLevel + startingNesting;
            int currentNesting = currentStep.getNestingLevel();

            StepExtension parentStep = nestingMap.get(currentNesting - 1);

            StepExtension previousSibling = currentNesting > lastNestingLevel ? null
                    : nestingMap.get(currentNesting);

            if (previousSibling != null) {
                pairSiblings(previousSibling, currentStep);
            }

            if (parentStep != null) {
                parentStep.addChildStep(currentStep);
                // currentStep.setParentStep(parentStep);
            }

            nestingMap.put(currentNesting, currentStep);
            lastNestingLevel = currentNesting;

        }
    }

}
