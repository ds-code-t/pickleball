package io.cucumber.core.runner;


import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.ExpressionArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.plugin.event.Result;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.StepMapping;

import tools.dscode.common.treeparsing.parsedComponents.PhraseData;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.coredefinitions.GeneralSteps;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static tools.dscode.common.util.DebugUtils.printDebug;


public abstract class StepData extends StepMapping {

    public LineData lineData;

    public io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep;
    public io.cucumber.core.runner.PickleStepTestStep executingPickleStepTestStep;
    public TestCase testCase;
    //    public Pickle pickle;
    public List<StepData> childSteps = new ArrayList<>();
    public List<StepData> grandChildrenSteps = new ArrayList<>();

    public List<StepData> attachedSteps = new ArrayList<>();
    public StepData parentStep;
    public StepData previousSibling;
    public StepData nextSibling;
    public String overrideLoggingText = null;
    List<Argument> arguments;
    public Argument argument;

    public int getNestingLevel() {
        return nestingLevel;
    }

    public void setNestingLevel(int nestingLevel) {
        this.nestingLevel = nestingLevel;
    }

    private int nestingLevel = 0;
    public String codeLocation;
    public boolean isCoreStep;
    protected final List<String> stepFlags = new ArrayList<>();
    protected List<DefinitionFlag> definitionFlags;
    protected List<DefinitionFlag> nextSiblingDefinitionFlags;
    public List<String> stepTags = new ArrayList<>();
    public List<String> bookmarks = new ArrayList<>();
    public Method method;
    public String methodName;
    public boolean isFlagStep = false;
    public static final String corePackagePath = GeneralSteps.class.getPackageName() + ".";
    public boolean hardFail = false;
    public boolean softFail = false;
    public boolean skipped = false;

    public DocString getDocString() {
        if (docString == null && nextSibling != null)
            docString = nextSibling.getDocString();
        if (docString == null)
            docString = getDocStringFromParent();
        return docString;
    }

    public DocString getDocStringFromParent() {
        if (docString == null && parentStep != null)
            docString = parentStep.getDocStringFromParent();
        return docString;
    }


    public DataTable getDataTable() {
        if (dataTable == null && nextSibling != null)
            dataTable = nextSibling.getDataTable();
        if (dataTable == null)
            dataTable = getDataTableFromParent();
        return dataTable;
    }

    public DataTable getDataTableFromParent() {
        if (dataTable == null && parentStep != null)
            dataTable = parentStep.getDataTableFromParent();
        return dataTable;
    }


    public DocString docString;
    public DataTable dataTable;


    public enum ConditionalStates {
        SKIP, FALSE, TRUE
    }

    private final List<ConditionalStates> conditionalStates = new ArrayList<>();

    public List<ConditionalStates> getConditionalStates() {
        return conditionalStates;
    }

    public void addConditionalStates(ConditionalStates... states) {
        this.conditionalStates.addAll(Arrays.stream(states).toList());
    }


    public StepData initializeChildSteps() {
        if (childSteps.isEmpty()) {
            if (grandChildrenSteps.isEmpty())
                return null;
            childSteps.addAll(grandChildrenSteps);
            grandChildrenSteps = new ArrayList<>();
        }

        StepData lastChild = null;
        for (StepData child : childSteps) {
            child.childSteps.addAll(grandChildrenSteps);
            child.parentStep = this;
            child.stepFlags.addAll(stepFlags);
            if (lastChild != null) {
                lastChild.nextSibling = child;
                child.previousSibling = lastChild;
            }
            child.setStepParsingMap(getStepParsingMap());
            lastChild = child;
        }
        return childSteps.getFirst();
    }

    public void addChildStep(StepData child) {
        StepData lastChild = childSteps.size() == 0 ? null : childSteps.getLast();
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
        this.testCase = testCase;
        this.pickleStepTestStep = pickleStepTestStep;
        codeLocation = pickleStepTestStep.getCodeLocation();
        if (codeLocation == null)
            codeLocation = "";
        isCoreStep = codeLocation.startsWith(corePackagePath);

        arguments = pickleStepTestStep == null || pickleStepTestStep.getDefinitionMatch() == null ? new ArrayList<>() : pickleStepTestStep.getDefinitionMatch().getArguments();
        argument = arguments.isEmpty() || arguments.getLast() instanceof ExpressionArgument ? null : arguments.getLast();
    }

    public abstract Result run();

    public abstract Result execute(io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep);

    public abstract void addDefinitionFlag(DefinitionFlag... flags);


}
