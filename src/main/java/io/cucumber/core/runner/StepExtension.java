package io.cucumber.core.runner;

import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.util.debug.DebugUtils;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.argumentToGherkinText;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.CurrentScenarioState.getScenarioLogRoot;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getGlobalEventBus;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.resolvePickleStepTestStep;
import static tools.dscode.common.browseroperations.BrowserAlerts.isPresent;
import static tools.dscode.common.util.Reflect.invokeAnyMethodOrThrow;
import static tools.dscode.common.util.debug.DebugUtils.parseDebugString;

public class StepExtension extends StepData {
    private static final Pattern pattern = Pattern.compile("@\\[([^\\[\\]]*)\\]");


    public StepExtension(io.cucumber.core.runner.TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);

//        pickle = (io.cucumber.messages.types.Pickle) getProperty(testCase, "pickle");
        method = pickleStepTestStep.getMethod();
        definitionFlags = pickleStepTestStep.getDefinitionFlags().stream().map(f -> {
            if (f.toString().startsWith("_"))
                return DefinitionFlag.valueOf(f.toString().substring(1));
            else
                inheritableDefinitionFlags.add(f);
            return f;
        }).collect(Collectors.toCollection(ArrayList::new));


        this.methodName = this.method == null ? "" : this.method.getName();
        this.isDynamicStep = isCoreStep && methodName.equals("executeDynamicStep");
        this.isCoreConditionalStep = isCoreStep && methodName.equals("runConditional");

        if (definitionFlags.contains(DefinitionFlag.NO_LOGGING))
            pickleStepTestStep.setNoLogging(true);

        if (isCoreStep) {
            if (methodName.startsWith("flagStep_")) {
                this.isFlagStep = true;
                stepFlags.add(pickleStepTestStep.getStep().getText());
            } else if (methodName.equals("NEXT_SIBLING_STEP")) {
                nextSiblingDefinitionFlags = pickleStepTestStep.getDefinitionFlags().stream().filter(f -> !f.toString().startsWith("_")).collect(Collectors.toCollection(ArrayList::new));

            }
        }

        String metaText = pickleStepTestStep.getPickleStep().getMetaText();
        Matcher matcher = pattern.matcher(metaText);

        while (matcher.find()) {
            stepTags.add(matcher.group(1));
        }
        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        debugStartStep = parseDebugString(stepTags);
        setNestingLevel((int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count());


        if (isCoreStep && methodName.equals("docString")) {
//            List<Argument> args = pickleStepTestStep.getDefinitionMatch().getArguments();
            String docStringName = (String) arguments.getFirst().getValue();
            docString = (DocString) arguments.getLast().getValue();
            if (docStringName != null && !docStringName.isBlank()) {
                getCurrentScenarioState().getParsingMap().getRootSingletonMap().put("DOCSTRING." + docStringName.trim(), docString);
            }
        } else if (isCoreStep && methodName.equals("dataTable")) {
//            List<Argument> args = pickleStepTestStep.getDefinitionMatch().getArguments();
            String tableName = (String) arguments.getFirst().getValue();
            dataTable = (DataTable) arguments.getLast().getValue();
            if (tableName != null && !tableName.isBlank()) {
                getCurrentScenarioState().getParsingMap().getRootSingletonMap().put("DATATABLE." + tableName.trim(), dataTable);
            }
        }

    }

    public Object runAndGetReturnValue() {
        Object instanceOrNull = null;
        try {
            instanceOrNull = method.getDeclaringClass().getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
            cause.printStackTrace();
            throw new RuntimeException(cause);
        }
        Object target = java.lang.reflect.Modifier.isStatic(method.getModifiers())
                ? null
                : instanceOrNull;

        try {
            return method.invoke(target, arguments.stream().map(arg -> arg.getValue()).toArray());
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
            cause.printStackTrace();
            throw new RuntimeException(cause);
        }
    }

    public Result run() {

        ExecutionMode executionMode = ExecutionMode.RUN;

        if (logAndIgnore) {
            executingPickleStepTestStep = pickleStepTestStep;
            executionMode = ExecutionMode.SKIP;
        } else if (isDynamicStep) {
            overrideLoggingText = lineData.runningText;
            executingPickleStepTestStep = pickleStepTestStep;
        } else {
            executingPickleStepTestStep = resolveAndClone(getStepParsingMap());
        }
        executingPickleStepTestStep.getPickleStep().nestingLevel = getNestingLevel();
        executingPickleStepTestStep.getPickleStep().overrideLoggingText = overrideLoggingText;
        stepEntry = getScenarioLogRoot().logInfo("STEP: " + executingPickleStepTestStep.getStepText()).start();
        lifecycle.fire(Phase.BEFORE_SCENARIO_STEP);
        io.cucumber.plugin.event.Result result = execute(executingPickleStepTestStep, executionMode);

        if (result.getError() != null) {
            stepEntry.logError("Exception: " + result.getError().getClass().getName())
                    .field("message", result.getError().getMessage())
                    .field("trace", Arrays.stream(result.getError().getStackTrace())
                            .map(StackTraceElement::toString)
                            .toList())
                    .timestamp();
        }

        lifecycle.fire(Phase.AFTER_SCENARIO_STEP);
        if (webDriverUsed != null) {
            if (isPresent(webDriverUsed)) {
                stepEntry.info("Browser Alert is present, cannot take screenshot.");
                stepEntry.stop();
            } else {
                stepEntry.screenshot("After Step").stop();
            }
        } else {
            stepEntry.stop();
        }


        return result;

    }

    public Result execute(io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep, ExecutionMode executionMode) {

        Instant start = Instant.now();
        try {
            Object r = invokeAnyMethodOrThrow(executionPickleStepTestStep, "run", getTestCase(), getGlobalEventBus(), getTestCaseState(), executionMode);

        } catch (Throwable t) {

            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;

            Duration duration = Duration.between(start, Instant.now());

            return new Result(Status.FAILED, duration, cause);
        }

        return executionPickleStepTestStep.getLastResult();
    }

    public void runPickleStepDefinitionMatch() {
        try {
            pickleStepTestStep.getDefinitionMatch().runStep(getTestCaseState());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }


    @Override
    public void addDefinitionFlag(DefinitionFlag... flags) {

        for (DefinitionFlag flag : flags) {
            if (flag == DefinitionFlag.NO_LOGGING)
                pickleStepTestStep.setNoLogging(true);
            if (!flag.toString().startsWith("_"))
                this.definitionFlags.add(flag);
        }


    }

    @Override
    public String toString() {
        return "SE: " + pickleStepTestStep.getStep().getText();
    }


    public List<StepExtension> insertStepsByString(List<String> newStepStrings) {
        List<StepExtension> newSteps = new ArrayList<>();
        for(String newStepString : newStepStrings) {
            newSteps.add(modifyStepExtension(newStepString));
        }
        insertSteps(newSteps);
        return newSteps;
    }

    public void insertSteps(List<StepExtension> newSteps) {
        if(newSteps == null || newSteps.isEmpty()) return;
        StepBase nextStep = this.nextSibling;
        StepExtension lastStep = this;
        for(StepExtension newStep : newSteps) {
            lastStep.nextSibling = newStep;
            newStep.previousSibling = lastStep;
        }
        if(nextStep != null) {
            nextStep.previousSibling = lastStep;
            lastStep.nextSibling = nextStep;
        }
        lastStep.childSteps.addAll(childSteps);
        lastStep.grandChildrenSteps.addAll(grandChildrenSteps);
        childSteps.clear();
        grandChildrenSteps.clear();
    }

    public StepExtension modifyStepExtension(String newText) {
        StepExtension modifiedStep = new StepExtension(testCase, getPickleStepTestStepFromStrings(pickleStepTestStep, pickleStepTestStep.getStep().getKeyword(), newText, getGherkinArgumentText(pickleStepTestStep.getStep())));


        modifiedStep.setStepParsingMap(getStepParsingMap());
        modifiedStep.parentStep = parentStep;
        modifiedStep.inheritedLineData = inheritedLineData.clone();

        modifiedStep.nestingLevel = nestingLevel;
//        modifiedStep.pickleStepTestStep.getPickleStep().nestingLevel = getNestingLevel();
        return modifiedStep;
    }


    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap) {


        PickleStepTestStep clonePickleStepTestStep = resolvePickleStepTestStep(pickleStepTestStep, parsingMap);
        if (definitionFlags.contains(DefinitionFlag.NO_LOGGING))
            clonePickleStepTestStep.setNoLogging(true);
        return clonePickleStepTestStep;
    }

    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap, String newText) {
        return resolvePickleStepTestStep(pickleStepTestStep, parsingMap, newText);
    }

    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap, String newText, PickleStepArgument newPickleStepArgument) {
        return resolvePickleStepTestStep(pickleStepTestStep, parsingMap, newText, newPickleStepArgument);
    }

    public StepExtension cloneWithOverrides(String newText, PickleStepArgument newPickleStepArgument) {
        StepExtension modifiedStep = new StepExtension(testCase, getPickleStepTestStepFromStrings(pickleStepTestStep.getStep().getKeyword(), newText, argumentToGherkinText(newPickleStepArgument)));
        modifiedStep.setStepParsingMap(getStepParsingMap());
        return modifiedStep;
    }

    public StepExtension cloneWithOverrides(String newText) {
        StepExtension modifiedStep = new StepExtension(testCase, getPickleStepTestStepFromStrings(pickleStepTestStep.getStep().getKeyword(), newText, argumentToGherkinText(pickleStepTestStep.getPickleStep().getArgument().orElse(null))));
        modifiedStep.setStepParsingMap(getStepParsingMap());
        return modifiedStep;
    }

}
