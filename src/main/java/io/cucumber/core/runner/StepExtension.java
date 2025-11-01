package io.cucumber.core.runner;

import io.cucumber.core.runner.util.PickleStepArgUtils;
import io.cucumber.messages.types.PickleStepArgument;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;
import tools.dscode.common.mappings.ParsingMap;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.gherkin.messages.NGherkinFactory.getGherkinArgumentText;
import static io.cucumber.core.runner.GlobalState.getEventBus;
import static io.cucumber.core.runner.GlobalState.getTestCase;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.getPickleStepTestStepFromStrings;
import static io.cucumber.core.runner.NPickleStepTestStepFactory.resolvePickleStepTestStep;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class StepExtension extends StepData {
    private static final Pattern pattern = Pattern.compile("@:([A-Z]+:[A-Z-a-z0-9]+)");

    public StepExtension(TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);
        System.out.println("@@StepExtension1: " + pickleStepTestStep.getStepText());
        try {
            this.method = (Method) getProperty(pickleStepTestStep, "definitionMatch.stepDefinition.stepDefinition.method");
            DefinitionFlags annotation = method.getAnnotation(DefinitionFlags.class);
            definitionFlags = annotation == null ? new ArrayList<>() : Arrays.stream(annotation.value()).toList();
        } catch (Exception e) {
            this.method = null;
            this.definitionFlags = new ArrayList<>();
        }
        this.methodName = this.method == null ? "" : this.method.getName();
        System.out.println("@@methodName: " + methodName);

        if (definitionFlags.contains(DefinitionFlag.NO_LOGGING))
            invokeAnyMethod(pickleStepTestStep, "setNoLogging", true);

        if (isCoreStep && methodName.startsWith("flagStep_")) {
            this.isFlagStep = true;
            stepFlags.add(pickleStepTestStep.getStep().getText());
        }


        String metaText = pickleStepTestStep.getPickleStep().getMetaText();
        Matcher matcher = pattern.matcher(metaText);

        while (matcher.find()) {
            stepTags.add(matcher.group().substring(1).replaceAll("@:", ""));
        }

        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        nestingLevel = (int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count();

    }

    public Result run() {
        System.out.println("@@run: " + this);
//            PickleStepTestStep executionPickleStepTestStep = resolveAndClone(getStepParsingMap());

            if(this instanceof ScenarioStep){
                System.out.println("@@ScenarioStep - execute:  : " + this);
                io.cucumber.plugin.event.Result result = execute(pickleStepTestStep);
                System.out.println("@@result1: " + result);
                return result;
            }
            PickleStepTestStep executionPickleStepTestStep = resolveAndClone(getStepParsingMap());
            io.cucumber.plugin.event.Result result = execute(executionPickleStepTestStep);
            System.out.println("@@executionPickleStepTestStep1: " + executionPickleStepTestStep.getStepText());
            System.out.println("@@executionPickleStepTestStep2: " + executionPickleStepTestStep.getDefinitionMatch());
            System.out.println("@@executionPickleStepTestStep3: " + executionPickleStepTestStep.getDefinitionMatch().getCodeLocation());
            System.out.println("@@executionPickleStepTestStep4: " + executionPickleStepTestStep.getDefinitionMatch().getLocation());
            System.out.println("@@result1: " + result);
            return result;
    }

    public Result execute(io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep) {
        System.out.println("@@execute: " + this);
        System.out.println("@@getTestCase(): " + getTestCase());
        System.out.println("@@getEventBus(): " + getEventBus());
        System.out.println("@@getTestCaseState(): " + getTestCaseState());
        try {
            Object r = invokeAnyMethod(executionPickleStepTestStep, "run", getTestCase(), getEventBus(), getTestCaseState(), ExecutionMode.RUN);
            System.out.println("@@r: " + r);
            System.out.println("@@r getClass: " + r.getClass());
            System.out.println("@@getTestCaseState .getStatus(): " + getTestCaseState().getStatus());
        }
        catch (Throwable t)
        {
            System.out.println("@@catch t: " + t.getMessage() + " " + t.getCause()  );
            t.printStackTrace();
        }
        return pickleStepTestStep.getLastResult();
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


    public  PickleStepTestStep resolveAndClone( ParsingMap parsingMap) {
        return resolvePickleStepTestStep(pickleStepTestStep, parsingMap);
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
