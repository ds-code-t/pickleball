package io.cucumber.core.runner;

import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.TestCase;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.coredefinitions.GeneralSteps;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;


public abstract class StepData {
    public io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep;
    public TestCase testCase;
    public List<StepData> childSteps = new ArrayList<>();
    public List<StepData> attachedSteps = new ArrayList<>();
    public StepData parentStep;
    public StepData previousSibling;
    public StepData nextSibling;
    public int nestingLevel = 0;
    public String codeLocation;
    public boolean isCoreStep;
    protected final List<String> stepFlags = new ArrayList<>();
    protected List<DefinitionFlag> definitionFlags;
    public List<String> stepTags = new ArrayList<>();
    public List<String> bookmarks = new ArrayList<>();
    public Method method;
    public String methodName;
    public boolean isFlagStep = false;
    public static final String corePackagePath = GeneralSteps.class.getPackageName() + ".";
    public boolean hardFail = false;
    public boolean softFail = false;
    public boolean skipped = false;

    public StepData initializeChildSteps() {
        if (childSteps.isEmpty()) return null;
        childSteps.forEach(child ->{
            child.parentStep = this;
            child.stepFlags.addAll(stepFlags);
        });
        return childSteps.getFirst();
    }


    StepData(TestCase testCase, io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep) {
        this.testCase = testCase;
        this.pickleStepTestStep = pickleStepTestStep;
        codeLocation = pickleStepTestStep.getCodeLocation();
        if (codeLocation == null)
            codeLocation = "";
        isCoreStep = codeLocation.startsWith(corePackagePath);
    }

    public abstract Result run();

    public abstract Result execute();

    public abstract void addDefinitionFlag(DefinitionFlag... flags);


}
