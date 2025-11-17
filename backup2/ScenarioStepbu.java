//package io.cucumber.core.runner;
//
//import io.cucumber.core.gherkin.Pickle;
//import io.cucumber.core.gherkin.messages.GherkinMessagesStep;
//
//import java.util.ArrayList;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//
//import static tools.dscode.common.GlobalConstants.META_FLAG;
//import static tools.dscode.common.GlobalConstants.ROOT_STEP;
//import static io.cucumber.core.runner.ScenarioState.getBus;
//import static io.cucumber.core.runner.util.cucumberutils.StepBuilder.createPickleStepTestStep;
//import static io.cucumber.core.runner.util.cucumberutils.StepBuilder.updatePickleStepTestStep;
//
//public class ScenarioStep extends StepExtension {
//
//    public static ScenarioStep createScenarioStep(
//            TestCaseExtension testCaseExtension, boolean isRoot
//    ) {
//        PickleStepTestStep modelStep = (PickleStepTestStep) testCaseExtension.getTestSteps().getFirst();
//        String stepText = isRoot ? ROOT_STEP
//                : META_FLAG + "SCENARIO: " +
//                        testCaseExtension.pickle.getName();
//        PickleStepTestStep newPickleStepTestStep = updatePickleStepTestStep(modelStep, stepText, null);
//        printDebug("@@newPickleStepTestStep1: " + newPickleStepTestStep.getStepText());
//        printDebug("@@newPickleStepTestStep2: " + ((GherkinMessagesStep) newPickleStepTestStep.step).getText());
//        System.out.println(
//            "@@newPickleStepTestStep3: " + ((GherkinMessagesStep) newPickleStepTestStep.step).pickleStep.text);
//        ScenarioStep scenarioStep = new ScenarioStep(newPickleStepTestStep, testCaseExtension.pickle, isRoot, 0);
//        printDebug("@@scenarioStep getStepLine11 " + scenarioStep.getStepLine());
//        printDebug("@@scenarioStep getStepLine22: " + scenarioStep.delegate.getStepLine());
//        testCaseExtension.registerStep(scenarioStep.delegate);
//        // putTestStepById(scenarioStep);
//        return scenarioStep;
//    }
//
//    private ScenarioStep(PickleStepTestStep modelStep, Pickle pickle, boolean isRoot, int nestingLevel) {
//        super(modelStep);
//        // super(createPickleStepTestStepFromGherkinMessagesStep((GherkinMessagesStep)
//        // pickle.getSteps().getFirst(),
//        // isRoot ? "a starting total of 2"
//        // : META_FLAG + "SCENARIO: " +
//        // pickle.getName(),
//        // null));
//        // isRoot ? ROOT_STEP : META_FLAG + "SCENARIO: " + pickle.getName(),
//        // null));
//
//        setNestingLevel(nestingLevel);
//        isScenarioNameStep = true;
//        List<StepExtension> steps = new ArrayList<>();
//        pickle.getSteps().forEach(step -> steps.add(
//            new StepExtension(
//                createPickleStepTestStep((GherkinMessagesStep) step, getBus().generateId(), pickle.getUri()))));
//
//        // delegate =
//        // createPickleStepTestStepFromGherkinMessagesStep((GherkinMessagesStep)
//        // pickle.getSteps().getFirst(),
//        // isRoot ? ROOT_STEP : META_FLAG + "SCENARIO: " + pickle.getName(),
//        // null);
//
//        int size = steps.size();
//
//        Map<Integer, StepExtension> nestingMap = new HashMap<>();
//        nestingMap.put(nestingLevel, this);
//        int lastNestingLevel = 0;
//        int startingNesting = nestingLevel + 1;
//        for (int s = 0; s < size; s++) {
//            StepExtension currentStep = steps.get(s);
//            currentStep.setNestingLevel(currentStep.getNestingLevel() + startingNesting);
//            int currentNesting = currentStep.getNestingLevel();
//
//            StepExtension parentStep = nestingMap.get(currentNesting - 1);
//
//            StepExtension previousSibling = currentNesting > lastNestingLevel ? null
//                    : nestingMap.get(currentNesting);
//
//            if (previousSibling != null) {
//                pairSiblings(previousSibling, currentStep);
//            }
//
//            if (parentStep != null) {
//                parentStep.addChildStep(currentStep);
//                // currentStep.setParentStep(parentStep);
//            }
//
//            nestingMap.put(currentNesting, currentStep);
//            lastNestingLevel = currentNesting;
//
//        }
//    }
//
//}
