package io.cucumber.core.runner;

//import io.cucumber.messages.types.Pickle;

import io.cucumber.core.gherkin.Pickle;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.ParsingMap;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.GlobalState.getGivenKeyword;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.createPickleStepTestStepsFromPickle;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static tools.dscode.common.GlobalConstants.SCENARIO_STEP;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.setProperty;

public class ScenarioStep extends StepExtension {

    private static final String DEFAULT_START_STEP_MARKER = "startstep";
    private static final String END_STEP_MARKER = "endstep";
    private final Pickle sourcePickle;
    private final String sourceScenarioName;
    private final URI sourceFeatureUri;
    private StepExtension startStepMarkerStep;

    public StepExtension getStartStepMarkerStep() {
        return startStepMarkerStep;
    }

    public Pickle getSourcePickle() {
        return sourcePickle;
    }

    public String getSourceScenarioName() {
        return sourceScenarioName;
    }

    public String getSourceFeaturePath() {
        return sourceFeatureUri == null ? "" : sourceFeatureUri.toString();
    }

    public static ScenarioStep createRootScenarioStep(
            io.cucumber.core.runner.TestCase testCase
    ) {
        String pickleName = testCase.getName();
        if (pickleName == null || pickleName.isBlank()) {
            pickleName = "UNNAMED SCENARIO";
        }
        Pickle sourcePickle = (Pickle) getProperty(testCase, "pickle");
        io.cucumber.core.runner.PickleStepTestStep scenarioPickleStepTestStep =
                getPickleStepTestStepFromStrings(
                        sourcePickle,
                        getGivenKeyword(),
                        SCENARIO_STEP + pickleName,
                        null
                );
        ScenarioStep scenarioStep = new ScenarioStep(
                testCase,
                scenarioPickleStepTestStep,
                sourcePickle
        );
        setProperty(testCase, "rootScenarioStep", scenarioStep);
        scenarioStep.initializeScenarioSteps(
                (List<StepExtension>) getProperty(testCase, "stepExtensions"),
                null,
                DEFAULT_START_STEP_MARKER
        );
        scenarioStep.getDefaultStepNodeMap().put("SCENARIO NAME", pickleName);
        scenarioStep.getDefaultStepNodeMap().put("ROOT SCENARIO NAME", pickleName);
        return scenarioStep;
    }

    public static ScenarioStep createScenarioStep(Pickle pickle) {
        return createScenarioStep(pickle, null);
    }

    public static ScenarioStep createScenarioStep(
            Pickle pickle,
            ParsingMap parsingMap
    ) {
        return createScenarioStep(
                pickle,
                parsingMap,
                DEFAULT_START_STEP_MARKER
        );
    }

    public static ScenarioStep createScenarioStep(
            Pickle pickle,
            ParsingMap parsingMap,
            String startStepMarker
    ) {
        io.cucumber.core.runner.TestCase topLevel = GlobalState.getTestCase();
        String pickleName = parsingMap == null
                ? pickle.getName()
                : parsingMap.resolveWholeText(pickle.getName());
        if (pickleName == null || pickleName.isBlank()) {
            pickleName = "UNNAMED SCENARIO";
        }
        String scenarioName = SCENARIO_STEP + pickleName;
        io.cucumber.core.runner.PickleStepTestStep scenarioPickleStepTestStep =
                getPickleStepTestStepFromStrings(
                        pickle,
                        getGivenKeyword(),
                        scenarioName,
                        null
                );
        ScenarioStep scenarioStep = new ScenarioStep(
                topLevel,
                scenarioPickleStepTestStep,
                pickle
        );
        if (parsingMap != null) {
            scenarioStep.stepParsingMap.clear();
            scenarioStep.stepParsingMap.getMaps().putAll(parsingMap.getMaps());
        }
        scenarioStep.initializeScenarioSteps(
                createPickleStepTestStepsFromPickle(pickle).stream()
                        .map(step -> new StepExtension(getTestCase(), step))
                        .toList(),
                parsingMap,
                startStepMarker
        );
        scenarioStep.getDefaultStepNodeMap().put("SCENARIO NAME", pickleName);
        return scenarioStep;
    }

    private ScenarioStep(
            TestCase testCase,
            io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep,
            Pickle sourcePickle
    ) {
        super(testCase, pickleStepTestStep);
        this.sourcePickle = sourcePickle;
        this.sourceScenarioName = sourcePickle == null
                ? ""
                : sourcePickle.getName();
        this.sourceFeatureUri = sourcePickle == null
                ? null
                : sourcePickle.getUri();
    }

    private void initializeScenarioSteps(
            List<StepExtension> inputSteps,
            ParsingMap parsingMap,
            String startStepMarker
    ) {
        List<StepExtension> steps = new ArrayList<>();
        String resolvedStartMarker = normalizeStartMarker(
                resolveMarkerText(startStepMarker, parsingMap)
        );
        startStepMarkerStep = null;
        boolean endStep = false;
        for (StepExtension step : inputSteps) {
            if (step.isStepMarker) {
                String markerText = resolveMarkerText(
                        step.stepMarkerText,
                        parsingMap
                );
                if (matchesStepMarker(markerText, resolvedStartMarker)) {
                    startStepMarkerStep = step;
                    steps.clear();
                } else if (matchesStepMarker(markerText, END_STEP_MARKER)) {
                    endStep = true;
                }
            }
            if (steps.isEmpty()) {
                for (int i = 1; i < step.getNestingLevel() + 1; i++) {
                    StepExtension nestingPlaceholder =
                            step.modifyStepExtension("|__");
                    nestingPlaceholder.setNestingLevel(i - 1);
                    steps.add(nestingPlaceholder);
                }
            }
            steps.add(step);
            if (endStep) {
                break;
            }
        }

        int size = steps.size();
        Map<Integer, StepExtension> nestingMap = new HashMap<>();
        nestingMap.put(getNestingLevel(), this);
        int lastNestingLevel = 0;
        int startingNesting = getNestingLevel() + 1;
        for (int s = 0; s < size; s++) {
            StepExtension currentStep = steps.get(s);
            currentStep.setNestingLevel(
                    currentStep.getNestingLevel() + startingNesting
            );
            int currentNesting = currentStep.getNestingLevel();
            StepExtension parentStep = nestingMap.get(currentNesting - 1);
            StepExtension previousSibling = currentNesting > lastNestingLevel
                    ? null
                    : nestingMap.get(currentNesting);
            if (currentStep.dataArgumentStep) {
                if (previousSibling != null) {
                    previousSibling.dataTable = currentStep.dataTable;
                    previousSibling.docString = currentStep.docString;
                    previousSibling.dataContextStepNodeMap =
                            currentStep.dataContextStepNodeMap;
//                    previousSibling.getStepParsingMap()
//                            .addMaps(currentStep.dataContextStepNodeMap);
                }
                continue;
            }
            if (previousSibling != null) {
                currentStep.previousSibling = previousSibling;
                previousSibling.nextSibling = currentStep;
                if (previousSibling.nextSiblingDefinitionFlags != null) {
                    currentStep.addDefinitionFlag(
                            previousSibling.nextSiblingDefinitionFlags.toArray(
                                    new DefinitionFlag[0]
                            )
                    );
                }
            }
            if (parentStep != null) {
                parentStep.childSteps.add(currentStep);
                currentStep.parentStep = parentStep;
            }
            nestingMap.put(currentNesting, currentStep);
            lastNestingLevel = currentNesting;
        }

        for (int s = 0; s < size; s++) {
            StepExtension currentStep = steps.get(s);
            if (currentStep.isDynamicStep
                    && currentStep.nextSibling != null
                    && currentStep.nextSibling.isDynamicStep
                    && currentStep.getUnmodifiedText().trim().endsWith(",")) {
                StepExtension nextStep = currentStep;
                String newStepText = ",";
                List<StepBase> childList = new ArrayList<>();
                int indexOfCurrentStep =
                        currentStep.parentStep.childSteps.indexOf(currentStep);
                while (newStepText.endsWith(",")
                        && nextStep != null
                        && nextStep.isDynamicStep) {
                    currentStep.parentStep.childSteps.remove(nextStep);
                    s++;
                    newStepText += nextStep
                            .getUnmodifiedText()
                            .trim()
                            .substring(1);
                    childList.addAll(nextStep.childSteps);
                    nextStep = (StepExtension) nextStep.nextSibling;
                }
                s--;
                StepExtension newStep = new StepExtension(
                        testCase,
                        getPickleStepTestStepFromStrings(
                                pickleStepTestStep,
                                pickleStepTestStep.getStep().getKeyword(),
                                newStepText,
                                getGherkinArgumentText(
                                        pickleStepTestStep.getStep()
                                )
                        )
                );
                newStep.setStepParsingMap(getStepParsingMap());
                newStep.parentStep = currentStep.parentStep;
                currentStep.parentStep.childSteps.add(
                        indexOfCurrentStep,
                        newStep
                );
                newStep.childSteps.addAll(childList);
                if (currentStep.previousSibling != null) {
                    newStep.previousSibling = currentStep.previousSibling;
                    currentStep.previousSibling.nextSibling = newStep;
                }
                if (nextStep != null) {
                    newStep.nextSibling = nextStep;
                    nextStep.previousSibling = newStep;
                }
            }
        }
    }

    static boolean matchesStepMarker(
            String markerText,
            String expectedMarkerText
    ) {
        String marker = normalizeMarker(markerText);
        String expected = normalizeMarker(expectedMarkerText);
        return !expected.isBlank() && marker.equals(expected);
    }

    static String resolveMarkerText(
            String markerText,
            ParsingMap parsingMap
    ) {
        if (markerText == null || markerText.isBlank()) {
            return "";
        }
        String resolved = parsingMap == null
                ? markerText
                : parsingMap.resolveWholeText(markerText);
        return normalizeMarker(resolved);
    }

    private static String normalizeStartMarker(String markerText) {
        String normalized = normalizeMarker(markerText);
        return normalized.isBlank() ? DEFAULT_START_STEP_MARKER : normalized;
    }

    private static String normalizeMarker(String markerText) {
        return markerText == null
                ? ""
                : markerText.trim().toLowerCase(Locale.ROOT);
    }
}
