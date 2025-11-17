package io.cucumber.core.runner;

import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.plugin.event.Result;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.ParsingMap;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.argumentToGherkinText;
import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.GlobalState.getCurrentScenarioState;
import static io.cucumber.core.runner.GlobalState.getEventBus;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.resolvePickleStepTestStep;
import static tools.dscode.common.util.DebugUtils.printDebug;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;
import static tools.dscode.common.util.Reflect.invokeAnyMethodOrThrow;

public class StepExtension extends StepData {
    private static final Pattern pattern = Pattern.compile("@\\[([^\\[\\]]*)\\]");
    boolean runMethodDirectly = false;

    public boolean debugStartStep = false;

    public StepExtension(io.cucumber.core.runner.TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);

//        pickle = (io.cucumber.messages.types.Pickle) getProperty(testCase, "pickle");
        method = pickleStepTestStep.getMethod();
        definitionFlags = pickleStepTestStep.getDefinitionFlags();


        this.methodName = this.method == null ? "" : this.method.getName();

        if (definitionFlags.contains(DefinitionFlag.NO_LOGGING))
            invokeAnyMethod(pickleStepTestStep, "setNoLogging", true);

        if (isCoreStep && methodName.startsWith("flagStep_")) {
            this.isFlagStep = true;
            stepFlags.add(pickleStepTestStep.getStep().getText());
        }


        String metaText = pickleStepTestStep.getPickleStep().getMetaText();
        Matcher matcher = pattern.matcher(metaText);

        while (matcher.find()) {
            stepTags.add(matcher.group(1));
        }

        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        debugStartStep = stepTags.stream().anyMatch(t -> t.startsWith("DEBUG"));
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
        try {
            executingPickleStepTestStep = resolveAndClone(getStepParsingMap());
            executingPickleStepTestStep.getPickleStep().nestingLevel = getNestingLevel();
            executingPickleStepTestStep.getPickleStep().overrideLoggingText = overrideLoggingText;
            io.cucumber.plugin.event.Result result = execute(executingPickleStepTestStep);
            return result;
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException ? t.getCause() : t;
            cause.printStackTrace();
            throw new RuntimeException(cause);
        }
    }

    public Result execute(io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep) {
        try {
            Object r = invokeAnyMethodOrThrow(executionPickleStepTestStep, "run", getTestCase(), getEventBus(), getTestCaseState(), ExecutionMode.RUN);
        } catch (Throwable e) {
            throw new RuntimeException(e);
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
        Arrays.stream(flags).toList().forEach(f -> printDebug("@@flag: " + f + ""));
        for (DefinitionFlag flag : flags) {
            if (flag == DefinitionFlag.NO_LOGGING)
                pickleStepTestStep.setNoLogging(true);
            this.definitionFlags.add(flag);
        }
    }

    @Override
    public String toString() {
        return "SE: " + pickleStepTestStep.getStep().getText();
    }

    public StepExtension modifyStepExtension(String newText) {
        return new StepExtension(testCase, getPickleStepTestStepFromStrings(pickleStepTestStep, pickleStepTestStep.getStep().getKeyword(), newText, getGherkinArgumentText(pickleStepTestStep.getStep())));
    }


    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap) {
        System.out.println("\n@@resolveAndClone: " + pickleStepTestStep.getUri());
        PickleStepTestStep clonePickleStepTestStep = resolvePickleStepTestStep(pickleStepTestStep, parsingMap);
        printDebug("@@getStepLine: " + clonePickleStepTestStep.getStepLine());
        return clonePickleStepTestStep;
    }

    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap, String newText) {
        return resolvePickleStepTestStep(pickleStepTestStep, parsingMap, newText);
    }

    public PickleStepTestStep resolveAndClone(ParsingMap parsingMap, String newText, PickleStepArgument newPickleStepArgument) {
        return resolvePickleStepTestStep(pickleStepTestStep, parsingMap, newText, newPickleStepArgument);
    }

    public StepExtension cloneWithOverrides(String newText, PickleStepArgument newPickleStepArgument) {
        return new StepExtension(testCase, getPickleStepTestStepFromStrings(pickleStepTestStep.getStep().getKeyword(), newText, argumentToGherkinText(newPickleStepArgument)));
    }

    public StepExtension cloneWithOverrides(String newText) {
        return new StepExtension(testCase, getPickleStepTestStepFromStrings(pickleStepTestStep.getStep().getKeyword(), newText, argumentToGherkinText(pickleStepTestStep.getPickleStep().getArgument().orElse(null))));
    }

//    public  PickleStepTestStep resolveAndClone(String stepText,  ParsingMap parsingMap) {
//        PickleStepArgument pickleStepArgument = pickleStepTestStep.getPickleStep().getArgument().orElse(null);
//        return resolveAndClone( stepText, pickleStepArgument, parsingMap);
//    }

//    public  PickleStepTestStep resolveAndClone( ParsingMap parsingMap) {
//        PickleStepArgument pickleStepArgument = pickleStepTestStep.getPickleStep().getArgument().orElse(null);
//        return resolveAndClone(pickleStepTestStep.getStepText(), pickleStepArgument, parsingMap);
//    }


//    public  PickleStepTestStep resolveAndClone( String stepText, PickleStepArgument pickleStepArgument, ParsingMap parsingMap) {
//        String newStepText = parsingMap.resolveWholeText(stepText);
//        PickleStepArgument newPickleStepArgument = null;
//        if (pickleStepArgument != null) {
//            UnaryOperator<String> external = parsingMap::resolveWholeText;
//            newPickleStepArgument = PickleStepArgUtils.transformPickleArgument(pickleStepArgument, external);
//        }
//            return clonePickleStepTestStep(pickleStepTestStep, newStepText, newPickleStepArgument);
//    }

}
