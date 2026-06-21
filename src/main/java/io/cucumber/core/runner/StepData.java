package io.cucumber.core.runner;


import io.cucumber.core.stepexpression.ExpressionArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.plugin.event.Result;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.RemoteWebDriver;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.mappings.StepMapping;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.common.treeparsing.parsedComponents.PhraseData;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


import static tools.dscode.common.reporting.logging.LogForwarder.logError;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.variables.RunVars.resolveFromVarsOrDefault;


public abstract class StepData extends StepMapping {
    public Level stepLogLevel = Level.INFO;

    public static Duration globalTimeoutSeconds =
            Duration.ofSeconds(Long.parseLong(String.valueOf(resolveFromVarsOrDefault("stepRepeatMaxTime", 3600)))); // 0 = no time limit

    public static int globalMaxIterations =
            Integer.parseInt(String.valueOf(resolveFromVarsOrDefault("stepRepeatMaxCount", 100)));


    public boolean reachedMaxDuration() {
        return reachedGlobalMaxDuration() || reachedStepMaxDuration();
    }

    public boolean reachedGlobalMaxDuration() {
        return reachedDurationLimit(globalTimeoutSeconds);
    }

    public boolean reachedStepMaxDuration() {
        if (stepTimeoutSeconds == null) return false;
        return reachedDurationLimit(stepTimeoutSeconds);
    }

    private boolean reachedDurationLimit(Duration maxDuration) {
        if (maxDuration == null) return false;
        if (maxDuration.isNegative()) return false;
        if (startTime == null) return false;

        return Duration.between(startTime, Instant.now()).compareTo(maxDuration) > 0;
    }

    public boolean reachedMaxRepetition() {
        return reachedGlobalMaxRepetition() || reachedStepMaxRepetition();
    }

    public boolean reachedGlobalMaxRepetition() {
        return reachedRepetitionLimit(globalMaxIterations);
    }

    public boolean reachedStepMaxRepetition() {
        if (stepMaxIterations == null) return false;
        return reachedRepetitionLimit(stepMaxIterations);
    }

    private boolean reachedRepetitionLimit(int maxIterations) {
        if (maxIterations < 0) return false;
        return runCount > maxIterations;
    }

    public boolean checkGlobalMax() {
        if(reachedGlobalMaxDuration()){
            logError("Global Max Duration Reached of " + globalTimeoutSeconds + " seconds for Step " + pickleStepTestStep.getStepText());
        } else if(reachedGlobalMaxRepetition()){
            logError("Global Max " + globalMaxIterations + " repetitions reached for Step " + pickleStepTestStep.getStepText());
        }
        else
        {
            return false;
        }
        return true;
    }

//    public boolean noStepLogging() {
//        return (definitionFlags.contains(DefinitionFlag.DEBUG_LOGGING) || definitionFlags.contains(DefinitionFlag._DEBUG_LOGGING)) && !isDebugLoggingEnabled();
//    }


    public Entry stepEntry;

    public RemoteWebDriver webDriverUsed = null;

    public int getNestingLevel() {
        return nestingLevel;
    }

    public void setNestingLevel(int nestingLevel) {
        this.nestingLevel = nestingLevel;
    }


    public DocString getDocString() {
        if(docString == null && parentStep != null)
            return parentStep.getDocString();
        return docString;
    }

    public DataTable getDataTable() {
        if(dataTable == null && parentStep != null)
            return parentStep.getDataTable();
        return dataTable;
    }


//    public List<ConditionalStates> getConditionalStates() {
//        return conditionalStates;
//    }
//
//    public void addConditionalStates(ConditionalStates... states) {
//        this.conditionalStates.addAll(Arrays.stream(states).toList());
//    }


    public StepBase initializeChildSteps() {
        ParsingMap stepParsingMap = inheritancePhrase == null ? getStepParsingMap() : inheritancePhrase.getPhraseParsingMap();
        if (childSteps.isEmpty()) {
            if (grandChildrenSteps.isEmpty())
                return null;
            childSteps.addAll(grandChildrenSteps);
            grandChildrenSteps = new ArrayList<>();
        }

        StepBase lastChild = null;

        for (StepBase child : childSteps) {

            child.childSteps.addAll(grandChildrenSteps);

            child.parentStep = this;
            child.nestingLevel = nestingLevel + 1;

            child.stepFlags.addAll(stepFlags);

            if (lastChild != null) {
                lastChild.nextSibling = child;

                child.previousSibling = lastChild;
            }

            child.setStepParsingMap(stepParsingMap);
            lastChild = child;

        }

        return childSteps.getFirst();
    }

    protected List<StepData> replacementSteps = new ArrayList<>();
    public void addReplacementStep(StepData replacement) {
        replacementSteps.add(replacement);
    }


    public void addChildStep(StepData child) {
        StepBase lastChild = childSteps.isEmpty() ? null : childSteps.getLast();
        if (lastChild != null) {
            lastChild.nextSibling = child;
            child.previousSibling = lastChild;
        }
        child.parentStep = this;
        childSteps.add(child);
    }

    public void insertChildNesting() {
        grandChildrenSteps.addAll(childSteps);
        childSteps = new ArrayList<>();
    }


    StepData(TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
//        isRootStep = pickleStepTestStep.getStepText().equals(ROOT_STEP);
        this.testCase = testCase;

        this.pickleStepTestStep = pickleStepTestStep;
        codeLocation = pickleStepTestStep.getCodeLocation();
        if (codeLocation == null)
            codeLocation = "";
        isCoreStep = codeLocation.startsWith(corePackagePath);
        arguments = pickleStepTestStep == null || pickleStepTestStep.getDefinitionMatch() == null ? new ArrayList<>() : pickleStepTestStep.getDefinitionMatch().getArguments();
        argument = arguments.isEmpty() || arguments.getLast() instanceof ExpressionArgument ? null : arguments.getLast();

        if(pickleStepTestStep.unresolvedText == null)
            pickleStepTestStep.unresolvedText = getUnmodifiedText();

    }

    public abstract Result run();

    public abstract Result execute(io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep, ExecutionMode executionMode);

    public void copyDefinitionFlags(StepData stepData) {
        addDefinitionFlag(stepData.inheritableDefinitionFlags.toArray(new DefinitionFlag[0]));
    }

    ;

    public abstract void addDefinitionFlag(DefinitionFlag... flags);


    public String getUnmodifiedText() {
//        return pickleStepTestStep.getStep().step .getOriginalText();
        return (String) getProperty(getProperty(pickleStepTestStep.getStep(), "pickleStep"), "text");
    }

}
