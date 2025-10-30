package io.cucumber.core.runner;

import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.cucumber.core.runner.GlobalState.getEventBus;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class StepExtension extends StepData {
    private static final Pattern pattern = Pattern.compile("@:([A-Z]+:[A-Z-a-z0-9]+)");

    public StepExtension(TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        super(testCase, pickleStepTestStep);
        try {
            this.method = (Method) getProperty(pickleStepTestStep, "definitionMatch.stepDefinition.stepDefinition.method");
            DefinitionFlags annotation = method.getAnnotation(DefinitionFlags.class);
            definitionFlags = annotation == null ? new ArrayList<>() : Arrays.stream(annotation.value()).toList();
        } catch (Exception e) {
            this.method = null;
            this.definitionFlags = new ArrayList<>();
        }
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
            stepTags.add(matcher.group().substring(1).replaceAll("@:", ""));
        }

        stepTags.stream().filter(t -> t.startsWith("REF:")).forEach(t -> bookmarks.add(t.replaceFirst("REF:", "")));
        nestingLevel = (int) matcher.replaceAll("").chars().filter(ch -> ch == ':').count();

    }

    public Result run() {
        System.out.println("@@run: " + this);
        io.cucumber.plugin.event.Result result = execute();
        System.out.println("@@result: " + result );
        return result;
    }

    public Result execute() {
        invokeAnyMethod(pickleStepTestStep, "run", testCase, getEventBus(), getTestCaseState(), ExecutionMode.RUN);
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
}
