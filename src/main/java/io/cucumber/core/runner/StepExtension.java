package io.cucumber.core.runner;

import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.DataTableArgument;
import io.cucumber.core.stepexpression.DocStringArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.Phase;
import tools.dscode.common.exceptions.StepCreationException;
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.exceptions.SoftRuntimeException;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.argumentToGherkinText;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.CurrentScenarioState.getScenarioLogRoot;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getGlobalEventBus;
import static io.cucumber.core.runner.GlobalState.getRunningStep;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static io.cucumber.core.runner.GlobalState.lifecycle;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.resolvePickleStepTestStep;
import static io.cucumber.core.runner.util.TableUtils.DOCSTRING_KEY;
import static io.cucumber.core.runner.util.TableUtils.TABLE_KEY;
import static io.cucumber.core.runner.util.TableUtils.toRowsMultimap;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.browseroperations.BrowserAlerts.isPresent;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.gherkinoperations.DynamicExecution.getCustomStep;
import static tools.dscode.common.mappings.MappingProcessor.getDataTableMap;
import static tools.dscode.common.mappings.MappingProcessor.getDocStringMap;
import static tools.dscode.common.mappings.ParsingMap.getRunningParsingMap;
import static tools.dscode.common.reporting.logging.LogForwarder.closestEntryToScenario;
import static tools.dscode.common.reporting.logging.LogForwarder.stepDebug;
import static tools.dscode.common.util.GeneralUtils.stackTraceToString;
import static tools.dscode.common.util.Reflect.getProperty;
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
        System.out.println("@@metaText::: " + metaText);
        Matcher matcher = pattern.matcher(metaText);

        while (matcher.find()) {
            stepTags.add(matcher.group(1));
        }
        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        debugStartStep = parseDebugString(stepTags);
        setNestingLevel((int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count());


        if (isCoreStep && methodName.equals("docString")) {
            dataArgumentStep = true;
            String docStringName = (String) arguments.getFirst().getValue();
            docString = (DocString) arguments.getLast().getValue();
            if (docStringName != null && !docStringName.isBlank()) {
                getDocStringMap().put(DOCSTRING_KEY, Map.of(docStringName.trim(), docString));
            }
            dataContextStepNodeMap = new NodeMap(MapConfigurations.MapType.PHRASE_MAP);
            dataContextStepNodeMap.setDataSource(MapConfigurations.DataSource.DOC_STRING);
            dataContextStepNodeMap.put("DOCSTRING", docString);
        } else if (isCoreStep && methodName.equals("dataTable")) {
            dataArgumentStep = true;
            String tableName = (String) arguments.getFirst().getValue();
            dataTable = (DataTable) arguments.getLast().getValue();
            if (tableName != null && !tableName.isBlank()) {
                getDataTableMap().put(TABLE_KEY, Map.of(tableName.trim(), toRowsMultimap(dataTable)));
            }
            dataContextStepNodeMap = new NodeMap(MapConfigurations.MapType.PHRASE_MAP);
            dataContextStepNodeMap.setDataSource(MapConfigurations.DataSource.DATA_TABLE);
            dataContextStepNodeMap.put(TABLE_KEY, toRowsMultimap(dataTable));
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

    public void addArg(Argument arg) {
        if (arg instanceof DocStringArgument || arg instanceof DataTableArgument) {
            arguments.removeIf(a -> a instanceof DocStringArgument || a instanceof DataTableArgument);
        }
        argument = arg;
        arguments.add(arg);
    }

    public Result run() {
        if (stepEntry == null) {
            stepEntry = closestEntryToScenario();
        }
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
        if (!definitionFlags.contains(DefinitionFlag.NO_LOGGING)) {
            stepEntry = getScenarioLogRoot().logWithType("STEP", executingPickleStepTestStep.getStepText()).start();
        }
        lifecycle.fire(Phase.BEFORE_SCENARIO_STEP);
        io.cucumber.plugin.event.Result result = execute(executingPickleStepTestStep, executionMode);

        if (result.getError() != null) {
//            stepEntry.error("Exception: " + result.getError().getClass().getName())
            stepEntry.error(stackTraceToString(result.getError()))
                    .field("message", result.getError().getMessage())
                    .field("trace", Arrays.stream(result.getError().getStackTrace())
                            .map(StackTraceElement::toString)
                            .toList())
                    .timestamp();
        }
        if (webDriverUsed != null) {
            safeWaitForPageReady(webDriverUsed, Duration.ofSeconds(5), 200);
        }
        lifecycle.fire(Phase.AFTER_SCENARIO_STEP);

        if (!definitionFlags.contains(DefinitionFlag.NO_LOGGING)) {
            if (webDriverUsed != null && webDriverUsed.getSessionId() != null) {
                if (isPresent(webDriverUsed)) {
                    stepEntry.info("Browser Alert is present, cannot take screenshot.");
                    stepEntry.stop();
                } else {
                    stepEntry.screenshot("After Step").stop();
                }
            } else {
                stepEntry.stop();
            }
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

    public List<StepExtension> insertChildStepsByString(List<String> newStepStrings) {
        List<StepExtension> newSteps = new ArrayList<>();
        for (String newStepString : newStepStrings) {
            newSteps.add(modifyStepExtension(newStepString));
        }
        insertChildSteps(newSteps);
        return newSteps;
    }

    public void insertChildSteps(List<StepExtension> newSteps) {
        if (newSteps == null || newSteps.isEmpty()) return;
        StepExtension lastStep = newSteps.getLast();
        lastStep.childSteps.addAll(childSteps);
        lastStep.grandChildrenSteps.addAll(grandChildrenSteps);
        childSteps.clear();
        grandChildrenSteps.clear();
        childSteps.addAll(newSteps);
    }

    public List<StepExtension> insertStepsByString(List<String> newStepStrings) {
        List<StepExtension> newSteps = new ArrayList<>();
        for (String newStepString : newStepStrings) {
            newSteps.add(modifyStepExtension(newStepString));
        }
        insertSteps(newSteps);
        return newSteps;
    }


    public void insertSteps(List<StepExtension> newSteps) {
        if (newSteps == null || newSteps.isEmpty()) return;
        StepBase nextStep = this.nextSibling;
        StepExtension lastStep = this;
        for (StepExtension newStep : newSteps) {
            lastStep.nextSibling = newStep;
            newStep.previousSibling = lastStep;
        }
        if (nextStep != null) {
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
        modifiedStep.nestingLevel = nestingLevel;
        modifiedStep.lineData = lineData.clone();
        return modifiedStep;
    }

    public StepExtension createNewStepExtension(String stepText) {
        PickleStepTestStep newPickleStepTestStep = getPickleStepTestStepFromStrings(pickleStepTestStep.getStep().getKeyword(), stepText, "");
        StepExtension modifiedStep = new StepExtension(testCase, newPickleStepTestStep);
        modifiedStep.setStepParsingMap(getStepParsingMap());
        modifiedStep.parentStep = parentStep;
        modifiedStep.nestingLevel = nestingLevel;
        return modifiedStep;
    }

    public String resolveStepFromString(String stepText) {
        StepExtension newStepExtension = createNewStepExtension(stepText);
        Object obj = newStepExtension.runAndGetReturnValue();
        stepDebug("Return step '" + stepText + "' resolved to: " + obj + "");
        if (obj == null) return null;
        return obj.toString();
    }


    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap) {
        PickleStepTestStep clonePickleStepTestStep;
        try {
            clonePickleStepTestStep = resolvePickleStepTestStep(pickleStepTestStep, parsingMap);
        }
        catch (Exception e) {
            clonePickleStepTestStep = getCustomStep(HARD_ERROR_STEP + e.getMessage()).pickleStepTestStep;
            return clonePickleStepTestStep;
        }
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
