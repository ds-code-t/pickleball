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
import tools.dscode.common.mappings.MapConfigurations;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.coredefinitions.GeneralSteps;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.argumentToGherkinText;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.gherkinDataTableForInlineType;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.realGherkinArgument;
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
import static io.cucumber.core.runner.util.TableUtils.toRowsStringMultimap;
import static tools.dscode.common.GlobalConstants.ALWAYS_RUN;
import static tools.dscode.common.GlobalConstants.HARD_ERROR_STEP;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_HARD_FAILED;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_PASSING;
import static tools.dscode.common.GlobalConstants.RUN_IF_SCENARIO_SOFT_FAILED;
import static tools.dscode.common.browseroperations.BrowserAlerts.isPresent;
import static tools.dscode.common.domoperations.LeanWaits.safeWaitForPageReady;
import static tools.dscode.common.gherkinoperations.DynamicExecution.getCustomStep;
import static tools.dscode.common.mappings.MappingProcessor.getRunMap;
import static tools.dscode.common.reporting.logging.LogForwarder.getGlobalLogLevel;
import static tools.dscode.common.reporting.logging.LogForwarder.getParentEntryForStep;
import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;
import static tools.dscode.common.reporting.logging.LogForwarder.setDefaultEntry;
import static tools.dscode.common.util.GeneralUtils.stackTraceToString;
import static tools.dscode.common.util.Reflect.invokeAnyMethodOrThrow;
import static tools.dscode.common.util.debug.DebugUtils.parseDebugString;

public class StepExtension extends StepData {
    public io.cucumber.plugin.event.Result result = null;
    private static final Pattern pattern = Pattern.compile("@\\[([^\\[\\]]*)\\]");

    public boolean waitForPageReady = true;
    private Consumer<StepExtension> finalizerAction;
    private String pendingInlineChild;
    private static final Pattern FLAG_STEP_WITH_INLINE = Pattern.compile(
            "^(RUN IF SCENARIO HARD FAILED|RUN IF SCENARIO SOFT FAILED|RUN IF SCENARIO FAILED|RUN IF SCENARIO PASSING|ALWAYS RUN)\\s*:\\s*(.*)$"
    );
    private static final Set<String> BARE_FLAG_STEPS = Set.of(
            ALWAYS_RUN,
            RUN_IF_SCENARIO_PASSING,
            RUN_IF_SCENARIO_FAILED,
            RUN_IF_SCENARIO_HARD_FAILED,
            RUN_IF_SCENARIO_SOFT_FAILED
    );

    public StepExtension(
            io.cucumber.core.runner.TestCase testCase,
            io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep
    ) {
        super(testCase, pickleStepTestStep);
        method = pickleStepTestStep.getMethod();
        definitionFlags = pickleStepTestStep.getDefinitionFlags().stream().map(f -> {
            if (f.toString().startsWith("_")) {
                return DefinitionFlag.valueOf(f.toString().substring(1));
            }
            inheritableDefinitionFlags.add(f);
            return f;
        }).collect(Collectors.toCollection(ArrayList::new));

        methodName = method == null ? "" : method.getName();
        isDynamicStep = isCoreStep && methodName.startsWith("executeDynamicStep");
        isStepMarker = isCoreStep && methodName.equals("stepMarker");
        isCoreConditionalStep = isCoreStep && methodName.equals("runConditional");
        if (isStepMarker) {
            stepMarkerText = (String) arguments.getFirst().getValue();
            stepMarkerText = stepMarkerText == null ? "" : stepMarkerText.trim();
        }
        if (isCoreStep) {
            if (methodName.startsWith("flagStep_")) {
                recordFlagStep();
            } else if (methodName.equals("NEXT_SIBLING_STEP")) {
                nextSiblingDefinitionFlags = pickleStepTestStep.getDefinitionFlags().stream()
                        .filter(f -> !f.toString().startsWith("_"))
                        .collect(Collectors.toCollection(ArrayList::new));
            }
        }

        String metaText = pickleStepTestStep.getPickleStep().getMetaText();
        Matcher matcher = pattern.matcher(metaText);
        while (matcher.find()) {
            stepTags.add(matcher.group(1));
        }
        stepTags.stream()
                .filter(t -> t.startsWith("REF:"))
                .forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        debugStartStep = parseDebugString(stepTags);
        setNestingLevel((int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count());

        if (isCoreStep && methodName.equals("docString")) {
            dataArgumentStep = true;
            String docStringName = (String) arguments.getFirst().getValue();
            docString = (DocString) arguments.getLast().getValue();
            if (docStringName != null && !docStringName.isBlank()) {
                getRunMap().put(docStringName.trim(), docString);
            }
            dataContextStepNodeMap = new NodeMap(MapConfigurations.MapType.PHRASE_MAP);
            dataContextStepNodeMap.setDataSource(MapConfigurations.DataSource.DOC_STRING);
            dataContextStepNodeMap.put(DOCSTRING_KEY, docString);
        } else if (isCoreStep && methodName.equals("dataTable")) {
            dataArgumentStep = true;
            String tableName = (String) arguments.getFirst().getValue();
            dataTable = (DataTable) arguments.getLast().getValue();
            if (tableName != null && !tableName.isBlank()) {
                getRunMap().put(tableName.trim(), dataTable);
            }
            dataContextStepNodeMap = new NodeMap(MapConfigurations.MapType.PHRASE_MAP);
            dataContextStepNodeMap.setDataSource(MapConfigurations.DataSource.DATA_TABLE);
            dataContextStepNodeMap.put(TABLE_KEY, dataTable);
            dataContextStepNodeMap.merge(toRowsStringMultimap(dataTable));
        }
    }

    /**
     * Native frame. Cucumber runs keep using {@link #StepExtension(TestCase, PickleStepTestStep)}.
     */
    public StepExtension(String keyword, String text) {
        super(text == null ? "" : text.strip());
        this.nativeKeyword = keyword == null || keyword.isBlank() ? "* " : keyword;
        this.definitionFlags = new ArrayList<>();
        this.methodName = "";
        this.isDynamicStep = false;
        this.isStepMarker = false;
        this.isCoreConditionalStep = false;
    }

    /** Binds a glue method discovered by the native registry. Cucumber frames already have one. */
    public void attachNativeMethod(java.lang.reflect.Method bound) {
        method = bound;
        methodName = bound == null ? "" : bound.getName();
        if (bound != null) {
            codeLocation = bound.getDeclaringClass().getName() + "." + bound.getName();
            isCoreStep = codeLocation.startsWith(corePackagePath);
            tools.dscode.common.annotations.DefinitionFlags flags =
                    bound.getAnnotation(tools.dscode.common.annotations.DefinitionFlags.class);
            if (flags != null) {
                for (DefinitionFlag flag : flags.value()) {
                    if (flag.toString().startsWith("_")) {
                        definitionFlags.add(DefinitionFlag.valueOf(flag.toString().substring(1)));
                    } else {
                        inheritableDefinitionFlags.add(flag);
                        definitionFlags.add(flag);
                    }
                }
            }
        }
        isDynamicStep = isCoreStep && methodName.startsWith("executeDynamicStep");
        isStepMarker = isCoreStep && methodName.equals("stepMarker");
        isCoreConditionalStep = isCoreStep && methodName.equals("runConditional");
        if (isCoreStep && methodName.startsWith("flagStep_")) {
            recordFlagStep();
        }
    }

    private void recordFlagStep() {
        isFlagStep = true;
        String text = getUnmodifiedText();
        text = text == null ? "" : text.trim();
        if (BARE_FLAG_STEPS.contains(text)) {
            stepFlags.add(text);
            return;
        }
        Matcher flagMatcher = FLAG_STEP_WITH_INLINE.matcher(text);
        if (flagMatcher.matches()) {
            stepFlags.add(flagMatcher.group(1));
            String inline = flagMatcher.group(2).trim();
            if (!inline.isEmpty()) {
                if (getCurrentScenarioState() != null) {
                    addChildStep(createNewStepExtension(inline));
                } else {
                    pendingInlineChild = inline;
                }
            }
            return;
        }
        stepFlags.add(text);
    }

    private void attachPendingInlineChild() {
        if (pendingInlineChild == null) {
            return;
        }
        String inline = pendingInlineChild;
        pendingInlineChild = null;
        addChildStep(createNewStepExtension(inline));
    }

    /** Native {@code run} has no pickle path, so a flag inline child stored at match time is attached here. */
    public void attachPendingInlineChildIfAny() {
        attachPendingInlineChild();
    }

    public Object runAndGetReturnValue() {
        if (pickleStepTestStep == null) {
            return tools.dscode.engine.runtime.SharedStepRun.valueOf(this);
        }
        Object instanceOrNull;
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
            return method.invoke(target, arguments.stream().map(Argument::getValue).toArray());
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
            cause.printStackTrace();
            throw new RuntimeException(cause);
        }
    }

    public void addArg(Argument arg) {
        if (arg instanceof DocStringArgument || arg instanceof DataTableArgument) {
            arguments.removeIf(
                    a -> a instanceof DocStringArgument || a instanceof DataTableArgument);
        }
        argument = arg;
        arguments.add(arg);
    }

    public Object nativeReturned;

    public boolean hasFinalizerAction() {
        return finalizerAction != null;
    }

    /** Native frames have no pickle runner, so a finalizer callback runs here. */
    public boolean runNativeFinalizer() {
        if (finalizerAction == null) return false;
        finalizerAction.accept(this);
        return true;
    }

    public Result run() {
        if (pickleStepTestStep == null) {
            return tools.dscode.engine.runtime.SharedStepRun.run(this);
        }
        attachPendingInlineChild();
        result = null;
        ExecutionMode executionMode = ExecutionMode.RUN;

        if (finalizerAction != null) {
            executingPickleStepTestStep = pickleStepTestStep;
        } else if (logAndIgnore) {
            executingPickleStepTestStep = pickleStepTestStep;
            executionMode = ExecutionMode.SKIP;
        } else if (isDynamicStep) {
            if (!lineData.runningText.startsWith("-")) {
                overrideLoggingText = lineData.runningText;
            }
            executingPickleStepTestStep = pickleStepTestStep;
        } else {
            try (UntilPassTiming.Scope ignored = UntilPassTiming.open("step-resolve")) {
                executingPickleStepTestStep = resolveAndClone(getStepParsingMap());
            }
        }
        executingPickleStepTestStep.getPickleStep().nestingLevel = getNestingLevel();
        executingPickleStepTestStep.getPickleStep().overrideLoggingText = overrideLoggingText;
        if (definitionFlags.contains(DefinitionFlag.BLOCK_CONDITIONAL)
                || definitionFlags.contains(DefinitionFlag.DEBUG_LOGGING)
                || definitionFlags.contains(DefinitionFlag._DEBUG_LOGGING)) {
            stepLogLevel = Level.DEBUG;
        }
        if (!shouldLog()) {
            executingPickleStepTestStep.setNoLogging(true);
        }

        String stepText = executingPickleStepTestStep.getStep().getKeyword()
                + " "
                + executingPickleStepTestStep.getStepText().replaceFirst("^([:\\s]*)", "");
        try (UntilPassTiming.Scope ignored = UntilPassTiming.open("logging")) {
            stepEntry = getParentEntryForStep(this)
                    .logWithType("STEP", stepText, stepLogLevel)
                    .tags("Step")
                    .start();
            setDefaultEntry(getRunningStep().stepEntry);
        }
        lifecycle.fire(Phase.BEFORE_SCENARIO_STEP);
        result = finalizerAction == null
                ? execute(executingPickleStepTestStep, executionMode)
                : executeFinalizerAction();
        if (result.getError() != null) {
            stepEntry.error(stackTraceToString(result.getError())).timestamp();
        }

        if (waitForPageReady && webDriverUsed != null) {
            safeWaitForPageReady(webDriverUsed, Duration.ofSeconds(5), 200);
        }
        lifecycle.fire(Phase.AFTER_SCENARIO_STEP);
        finalizeStepEntryLogging();
        return result;
    }

    private Result executeFinalizerAction() {
        Instant start = Instant.now();
        try {
            finalizerAction.accept(this);
            return new Result(Status.PASSED, Duration.between(start, Instant.now()), null);
        } catch (Throwable throwable) {
            return new Result(
                    Status.FAILED,
                    Duration.between(start, Instant.now()),
                    throwable
            );
        }
    }

    public void finalizeStepEntryLogging() {
        try (UntilPassTiming.Scope ignoredReport = UntilPassTiming.open("report-attachment")) {
            if (webDriverUsed != null && webDriverUsed.getSessionId() != null) {
                if (isPresent(webDriverUsed)) {
                    stepEntry.info("Browser Alert is present, cannot take screenshot.");
                    stepEntry.stop();
                } else {
                    try (UntilPassTiming.Scope ignoredShot = UntilPassTiming.open("screenshot")) {
                        stepEntry.screenshot("After Step").stop();
                    }
                }
            } else {
                stepEntry.stop();
            }
        }
    }

    public Result execute(
            io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep,
            ExecutionMode executionMode
    ) {
        Instant start = Instant.now();
        try {
            try (UntilPassTiming.Scope ignored = UntilPassTiming.open("step-body")) {
                invokeAnyMethodOrThrow(
                        executionPickleStepTestStep,
                        "run",
                        getTestCase(),
                        getGlobalEventBus(),
                        getTestCaseState(),
                        executionMode);
            }
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

    public boolean shouldLog() {
        return stepLogLevel.ordinal() >= getGlobalLogLevel().ordinal();
    }

    @Override
    public void addDefinitionFlag(DefinitionFlag... flags) {
        for (DefinitionFlag flag : flags) {
            if (!flag.toString().startsWith("_")) {
                definitionFlags.add(flag);
            }
        }
    }

    public void setToLogAll() {
        if (pickleStepTestStep != null) pickleStepTestStep.setNoLogging(false);
        new ArrayList<>(definitionFlags).forEach(f -> {
            String flagString = f.toString().toLowerCase();
            if (flagString.contains("ignore") || flagString.contains("logging")) {
                definitionFlags.remove(f);
            }
        });
    }

    @Override
    public String toString() {
        return "SE: " + getStepText();
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
        if (newSteps == null || newSteps.isEmpty()) {
            return;
        }
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
        if (newSteps == null || newSteps.isEmpty()) {
            return;
        }
        StepBase nextStep = nextSibling;
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
        if (pickleStepTestStep == null) {
            return modifyStepExtension(newText, inheritedGherkinArgument());
        }
        return modifyStepExtension(newText, getGherkinArgumentText(pickleStepTestStep.getStep()));
    }

    public StepExtension modifyStepExtension(String newText, String gherkinArgument) {
        if (pickleStepTestStep == null) {
            StepExtension modifiedStep = new StepExtension(nativeKeyword, newText);
            modifiedStep.nativeInlineType = nativeInlineType;
            modifiedStep.nativeInlineText = nativeInlineText == null ? "" : nativeInlineText;
            modifiedStep.setStepParsingMap(getStepParsingMap());
            modifiedStep.parentStep = parentStep;
            modifiedStep.nestingLevel = nestingLevel;
            modifiedStep.lineData = lineData == null ? null : lineData.clone();
            tools.dscode.engine.runtime.SharedStepRun.attachRenderedArgument(modifiedStep, gherkinArgument);
            if (gherkinArgument != null && !gherkinArgument.isBlank()
                    && tools.dscode.engine.runtime.SharedStepRun.bound(modifiedStep) == null) {
                tools.dscode.engine.runtime.SharedStepRun.transfer(this, modifiedStep);
            }
            tools.dscode.engine.runtime.SharedStepRun.bindGlue(modifiedStep);
            return modifiedStep;
        }
        StepExtension modifiedStep = new StepExtension(
                testCase,
                getPickleStepTestStepFromStrings(
                        pickleStepTestStep,
                        pickleStepTestStep.getStep().getKeyword(),
                        newText,
                        gherkinArgument == null ? "" : gherkinArgument));
        modifiedStep.setStepParsingMap(getStepParsingMap());
        modifiedStep.parentStep = parentStep;
        modifiedStep.nestingLevel = nestingLevel;
        modifiedStep.lineData = lineData == null ? null : lineData.clone();
        return modifiedStep;
    }

    public String inheritedGherkinArgument() {
        if (pickleStepTestStep == null) {
            // The real table or doc string wins. A marker peeled off the end of
            // the line is nativeInline* and belongs on the last branch only.
            String rendered = tools.dscode.engine.runtime.SharedStepRun.renderedArgument(this);
            if (rendered != null && !rendered.isBlank()) return rendered;
            String inlineTable = gherkinDataTableForInlineType(nativeInlineType, nativeInlineText);
            if (inlineTable != null) return inlineTable;
            return "";
        }
        return realGherkinArgument(pickleStepTestStep.getStep());
    }

    public StepExtension createNewStepExtension(String stepText) {
        return createNewStepExtension(stepText, "");
    }

    public StepExtension createNewStepExtension(String stepText, String gherkinArgument) {
        if (pickleStepTestStep == null) {
            io.cucumber.messages.types.InlinePickleArgument.Extracted inline =
                    io.cucumber.messages.types.InlinePickleArgument.extract(stepText);
            String body = inline == null ? stepText : inline.stepText();
            StepExtension modifiedStep = new StepExtension(nativeKeyword, body);
            if (inline != null) {
                modifiedStep.nativeInlineType = inline.argumentType();
                modifiedStep.nativeInlineText = inline.argumentText() == null ? "" : inline.argumentText();
            }
            modifiedStep.setStepParsingMap(getStepParsingMap());
            modifiedStep.parentStep = parentStep;
            modifiedStep.nestingLevel = nestingLevel;
            tools.dscode.engine.runtime.SharedStepRun.attachRenderedArgument(modifiedStep, gherkinArgument);
            tools.dscode.engine.runtime.SharedStepRun.bindGlue(modifiedStep);
            return modifiedStep;
        }
        PickleStepTestStep newPickleStepTestStep = getPickleStepTestStepFromStrings(
                pickleStepTestStep.getStep().getKeyword(),
                stepText,
                gherkinArgument == null ? "" : gherkinArgument);
        StepExtension modifiedStep = new StepExtension(testCase, newPickleStepTestStep);
        modifiedStep.setStepParsingMap(getStepParsingMap());
        modifiedStep.parentStep = parentStep;
        modifiedStep.nestingLevel = nestingLevel;
        return modifiedStep;
    }

    /**
     * Creates an infrastructure finalizer backed by the existing placeholder
     * definition. The runner executes its callback only after the owning step's
     * full subtree has completed.
     */
    public StepExtension createFinalizerStep(
            String loggingText,
            Consumer<StepExtension> action
    ) {
        StepExtension finalizer = createNewStepExtension("|__");
        finalizer.finalizerAction = action;
        finalizer.overrideLoggingText = loggingText;
        finalizer.waitForPageReady = false;
        if (finalizer.pickleStepTestStep != null) finalizer.pickleStepTestStep.setNoLogging(true);
        return finalizer;
    }

    public String resolveStepFromString(String stepText) {
        StepExtension newStepExtension = createNewStepExtension(stepText);
        Object obj = newStepExtension.runAndGetReturnValue();
        logDebug("Return step '" + stepText + "' resolved to: " + obj);
        return obj == null ? null : obj.toString();
    }

    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap) {
        String errorMessage;
        try {
            return resolvePickleStepTestStep(pickleStepTestStep, parsingMap);
        } catch (Exception e) {
            try {
                errorMessage = GeneralSteps.registerStepThrowable(
                        new RuntimeException(
                                "Step: '" + pickleStepTestStep.getStepText() + "' failed",
                                e));
            } catch (Exception ignored) {
                errorMessage = GeneralSteps.registerStepThrowable(e);
            }
            return getCustomStep(HARD_ERROR_STEP + errorMessage).pickleStepTestStep;
        }
    }

    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap, String newText) {
        return resolvePickleStepTestStep(pickleStepTestStep, parsingMap, newText);
    }

    public PickleStepTestStep resolveAndClone(
            ParsingMap parsingMap,
            String newText,
            PickleStepArgument newPickleStepArgument
    ) {
        return resolvePickleStepTestStep(
                pickleStepTestStep, parsingMap, newText, newPickleStepArgument);
    }

    public StepExtension cloneWithOverrides(
            String newText,
            PickleStepArgument newPickleStepArgument
    ) {
        StepExtension modifiedStep = new StepExtension(
                testCase,
                getPickleStepTestStepFromStrings(
                        pickleStepTestStep.getStep().getKeyword(),
                        newText,
                        argumentToGherkinText(newPickleStepArgument)));
        modifiedStep.setStepParsingMap(getStepParsingMap());
        return modifiedStep;
    }

    public StepExtension cloneWithOverrides(String newText) {
        StepExtension modifiedStep = new StepExtension(
                testCase,
                getPickleStepTestStepFromStrings(
                        pickleStepTestStep.getStep().getKeyword(),
                        newText,
                        argumentToGherkinText(
                                pickleStepTestStep.getPickleStep()
                                        .getArgument()
                                        .orElse(null))));
        modifiedStep.setStepParsingMap(getStepParsingMap());
        return modifiedStep;
    }
}
