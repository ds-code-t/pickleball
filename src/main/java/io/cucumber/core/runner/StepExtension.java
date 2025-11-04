package io.cucumber.core.runner;

import io.cucumber.core.stepexpression.Argument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.ParsingMap;

import java.util.List;
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
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;
import static tools.dscode.common.util.Reflect.setProperty;

public class StepExtension extends StepData {
    private static final Pattern pattern = Pattern.compile("@:([A-Z]+:[A-Z-a-z0-9]+)");
    boolean runMethodDirectly = false;


    public StepExtension(io.cucumber.core.runner.TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);

//        pickle = (io.cucumber.messages.types.Pickle) getProperty(testCase, "pickle");
        method = pickleStepTestStep.getMethod();
        definitionFlags = pickleStepTestStep.getDefinitionFlags();


        this.methodName = this.method == null ? "" : this.method.getName();
        System.out.println("@@methodName: " + methodName);
        System.out.println("@@methodName: " + methodName);

        if (definitionFlags.contains(DefinitionFlag.NO_LOGGING))
            invokeAnyMethod(pickleStepTestStep, "setNoLogging", true);

        if (isCoreStep && methodName.startsWith("flagStep_")) {
            this.isFlagStep = true;
            stepFlags.add(pickleStepTestStep.getStep().getText());
        }


        String metaText = pickleStepTestStep.getPickleStep().getMetaText();
        System.out.println("@@metaText: " + metaText);
        Matcher matcher = pattern.matcher(metaText);

        while (matcher.find()) {
            stepTags.add(matcher.group().substring(1).replaceAll("@:", ""));
        }

        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        setNestingLevel((int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count());

        List<Argument> argstests = pickleStepTestStep.getDefinitionMatch().getArguments();
        System.out.println("@@STEP pickleStepTestStep: " + pickleStepTestStep.getStepText());
        System.out.println("@@STEP Args: " + argstests.size());
        System.out.println("@@STEP methodName: " + methodName);
        System.out.println("@@STEP isCoreStep " + isCoreStep);
        System.out.println("@@STEP methodName.equals(\"dataTable\") " + methodName.equals("dataTable"));
        System.out.println("@@STEP isCoreStep && methodName.equals(\"dataTable\") " + (isCoreStep && methodName.equals("dataTable")));
        for (Argument arg : argstests) {
            System.out.println("@@arg: " + arg.getClass().getName() + "");
            System.out.println("@@arg: " + arg.getValue() + "");
        }

        if (isCoreStep && methodName.equals("docString")) {
            List<Argument> args = pickleStepTestStep.getDefinitionMatch().getArguments();
            String docStringName = (String) args.getFirst().getValue();
            docString = (DocString) args.getLast().getValue();
            if (docStringName != null && !docStringName.isBlank()) {
                getCurrentScenarioState().getParsingMap().getRootSingletonMap().put("DOCSTRING." + docStringName.trim(), docString);
            }
        } else if (isCoreStep && methodName.equals("dataTable")) {
            List<Argument> args = pickleStepTestStep.getDefinitionMatch().getArguments();
            String tableName = (String) args.getFirst().getValue();
            dataTable = (DataTable) args.getLast().getValue();
            System.out.println("@@dataTable!!\n" + dataTable);
            if (tableName != null && !tableName.isBlank()) {
                getCurrentScenarioState().getParsingMap().getRootSingletonMap().put("DATATABLE." + tableName.trim(), dataTable);
            }
        }
    }

    public Result run() {
        System.out.println("@@run: " + this);

        System.out.println("@@executionPickleStepTestStep args11::: " + pickleStepTestStep.getDefinitionMatch().getArguments().stream().map(Argument::getValue).toList());

        executingPickleStepTestStep = resolveAndClone(getStepParsingMap());
        executingPickleStepTestStep.getPickleStep().nestingLevel = getNestingLevel();
        io.cucumber.plugin.event.Result result = execute(executingPickleStepTestStep);
        System.out.println("@@result1: " + result);
        return result;
    }

    public Result execute(io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep) {
        System.out.println("@@execute: " + this);
        try {
            Object r = invokeAnyMethod(executionPickleStepTestStep, "run", getTestCase(), getEventBus(), getTestCaseState(), ExecutionMode.RUN);
//            System.out.println("@@pickleStepTestStep._lastResult : " + pickleStepTestStep._lastResult);
            TestStep testStep = executionPickleStepTestStep;
            System.out.println("@@testStep.getLastResult(); : " + testStep.getLastResult());
        } catch (Throwable t) {
            System.out.println("@@catch t: " + t.getMessage() + " " + t.getCause());
            t.printStackTrace();
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
        pickleStepTestStep.getDefinitionMatch().getArguments().stream().map(Argument::getValue).forEach(System.out::println);
        PickleStepTestStep clonePickleStepTestStep = resolvePickleStepTestStep(pickleStepTestStep, parsingMap);
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
