package io.cucumber.core.runner;


import io.cucumber.core.stepexpression.ExpressionArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.messages.types.PickleStep;
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
import static tools.dscode.common.reporting.logging.LogForwarder.logWarn;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.datetime.DurationFormattingUtils.parseDuration;
import static tools.dscode.common.variables.RunVars.resolveFromVars;
import static tools.dscode.common.variables.RunVars.resolveFromVarsOrDefault;


public abstract class StepData extends StepMapping {
    public Level stepLogLevel = Level.INFO;

    public static Duration globalTimeoutSeconds;
    // Negative is unlimited. Zero is not: the limit is exceeded once elapsed time is greater than zero.

    public static int globalMaxIterations;
    // Negative is unlimited. Zero is already at the limit, so the loop makes no pass.

    static {
        Object stepMaxTime = resolveFromVars("stepMaxTime");
        Object legacySeconds = resolveFromVars("stepRepeatMaxTime");
        if (isSet(legacySeconds)) {
            logWarn("pkb_stepRepeatMaxTime is deprecated. Use pkb_stepMaxTime (minutes, or a unit such as 90s).");
        }
        globalTimeoutSeconds = parseStepMaxTime(stepMaxTime, legacySeconds);
        globalMaxIterations = Integer.parseInt(String.valueOf(resolveFromVarsOrDefault("stepRepeatMaxCount", 100)));
    }

    /**
     * {@code pkb_stepMaxTime} wins. A bare integer is minutes. A unit such as
     * {@code 90s} is a duration. The deprecated alias is seconds. When both
     * keys are absent the cap is 60 minutes.
     */
    static Duration parseStepMaxTime(Object stepMaxTime, Object legacySeconds) {
        if (isSet(stepMaxTime)) {
            return parseMaxTime(stepMaxTime, true);
        }
        if (isSet(legacySeconds)) {
            return parseMaxTime(legacySeconds, false);
        }
        return Duration.ofMinutes(60);
    }

    static String formatStepMaxTime(Duration duration) {
        long millis = duration.toMillis();
        if (duration.getNano() % 1_000_000 != 0 || millis % 1000 != 0) {
            return millis + "ms";
        }
        long seconds = millis / 1000;
        if (seconds % 60 != 0) {
            return seconds + "s";
        }
        return (seconds / 60) + "m";
    }

    public static String stepMaxTimeExceededMessage() {
        return "Step exceeded pkb_stepMaxTime (" + formatStepMaxTime(globalTimeoutSeconds) + ")";
    }

    /**
     * Milliseconds to sleep before the next until pass. Null start, a negative
     * cap, or no cap sleeps the full 400. Otherwise only the time left, and
     * never more than 400.
     */
    static long untilPauseMillis(Instant startTime, Duration cap) {
        if (cap == null || cap.isNegative() || startTime == null) {
            return 400;
        }
        Duration left = cap.minus(Duration.between(startTime, Instant.now()));
        if (left.isNegative() || left.isZero()) {
            return 0;
        }
        return Math.min(400L, left.toMillis());
    }

    private static boolean isSet(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private static Duration parseMaxTime(Object raw, boolean minutesIfBare) {
        String text = String.valueOf(raw).trim();
        if (text.matches("-?\\d+")) {
            long amount = Long.parseLong(text);
            return minutesIfBare ? Duration.ofMinutes(amount) : Duration.ofSeconds(amount);
        }
        return parseDuration(text);
    }


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
        return repetitionLimitReached(runCount, maxIterations);
    }

    /**
     * A negative limit is unlimited. Zero is already exceeded. A positive N is
     * reached when {@code runCount} completed passes is at least N.
     */
    static boolean repetitionLimitReached(int runCount, int maxIterations) {
        if (maxIterations < 0) return false;
        return runCount >= maxIterations;
    }

    public boolean checkGlobalMax() {
        if(reachedGlobalMaxDuration()){
            logError(stepMaxTimeExceededMessage());
        } else if(reachedGlobalMaxRepetition()){
            logError("Until loop exhausted pkb_stepRepeatMaxCount " + globalMaxIterations);
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

    public String getInlineArgumentType() {
        PickleStep pickleStep = getPickleStep();
        return pickleStep == null ? "" : pickleStep.getInlineArgumentType();
    }

    public String getInlineArgumentText() {
        PickleStep pickleStep = getPickleStep();
        return pickleStep == null ? "" : pickleStep.getInlineArgumentText();
    }

    public boolean hasInlineArgument() {
        PickleStep pickleStep = getPickleStep();
        return pickleStep != null && pickleStep.hasInlineArgument();
    }

    private PickleStep getPickleStep() {
        return pickleStepTestStep == null ? null : pickleStepTestStep.getPickleStep();
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

        boolean isBlockConditionalStep = lineData != null && lineData.isBlockConditionalStep;

        for (StepBase child : childSteps) {

            child.childSteps.addAll(grandChildrenSteps);

            child.parentStep = isBlockConditionalStep? this.parentStep: this;
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
