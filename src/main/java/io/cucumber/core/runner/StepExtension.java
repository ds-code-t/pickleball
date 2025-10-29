package io.cucumber.core.runner;

import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCase;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.annotations.DefinitionFlags;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static io.cucumber.core.runner.GlobalState.getEventBus;
import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static tools.dscode.common.util.Reflect.getProperty;
import static tools.dscode.common.util.Reflect.invokeAnyMethod;

public class StepExtension extends StepRelationships  {

    public List<DefinitionFlag> definitionFlags;
    public PickleStepTestStep pickleStepTestStep;
    public TestCase testCase;
    public Method method;

    public StepExtension(TestCase testCase, PickleStepTestStep pickleStepTestStep) {
        getProperty(pickleStepTestStep, "stepExtension");
        this.testCase = testCase;
        this.pickleStepTestStep = pickleStepTestStep;
        try {
            this.method = (Method) getProperty(pickleStepTestStep, "definitionMatch.stepDefinition.stepDefinition.method");
            DefinitionFlags annotation = method.getAnnotation(DefinitionFlags.class);
            definitionFlags = annotation == null ? new ArrayList<>() : Arrays.stream(annotation.value()).toList();
        } catch (Exception e) {
            this.method = null;
            this.definitionFlags = new ArrayList<>();
        }
        if(definitionFlags.contains(DefinitionFlag.NO_LOGGING))
            invokeAnyMethod(pickleStepTestStep, "setNoLogging", true);
    }

    public StepExtension run() {
        invokeAnyMethod(pickleStepTestStep, "run", testCase, getEventBus(), getTestCaseState(), ExecutionMode.RUN);
        return this;
    }

}
