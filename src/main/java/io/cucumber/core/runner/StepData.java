package io.cucumber.core.runner;


import io.cucumber.core.stepexpression.Argument;
import io.cucumber.core.stepexpression.ExpressionArgument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import io.cucumber.plugin.event.Result;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.StepMapping;

import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.coredefinitions.GeneralSteps;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


public abstract class StepData extends StepMapping {

    public int getNestingLevel() {
        return nestingLevel;
    }

    public void setNestingLevel(int nestingLevel) {
        this.nestingLevel = nestingLevel;
    }


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


    public List<ConditionalStates> getConditionalStates() {
        return conditionalStates;
    }

    public void addConditionalStates(ConditionalStates... states) {
        this.conditionalStates.addAll(Arrays.stream(states).toList());
    }


    public StepBase initializeChildSteps() {


        if (childSteps.isEmpty()) {
            if (grandChildrenSteps.isEmpty())
                return null;
            childSteps.addAll(grandChildrenSteps);
            grandChildrenSteps = new ArrayList<>();
        }

        StepBase lastChild = null;

        for (StepBase child : childSteps) {


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
        StepBase lastChild = childSteps.size() == 0 ? null : childSteps.getLast();
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
        isDynamicStep = isCoreStep && methodName.equals("exeexecuteDynamicStep");
        arguments = pickleStepTestStep == null || pickleStepTestStep.getDefinitionMatch() == null ? new ArrayList<>() : pickleStepTestStep.getDefinitionMatch().getArguments();
        argument = arguments.isEmpty() || arguments.getLast() instanceof ExpressionArgument ? null : arguments.getLast();
    }

    public abstract Result run();

    public abstract Result execute(io.cucumber.core.runner.PickleStepTestStep executionPickleStepTestStep);

    public void copyDefinitionFlags(StepData stepData) {
        addDefinitionFlag(stepData.inheritableDefinitionFlags.toArray(new DefinitionFlag[0]));
    }

    ;

    public abstract void addDefinitionFlag(DefinitionFlag... flags);


}
