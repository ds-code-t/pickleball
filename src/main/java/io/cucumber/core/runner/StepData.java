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
        System.out.println("@@initializeChildSteps: " + this);
        System.out.println("@@childSteps.size1: " + childSteps.size());
        if (childSteps.isEmpty()) {
            if (grandChildrenSteps.isEmpty())
                return null;
            childSteps.addAll(grandChildrenSteps);
            grandChildrenSteps = new ArrayList<>();
        }

        StepBase lastChild = null;
        System.out.println("@@childSteps.size2: " + childSteps.size());
        for (StepBase child : childSteps) {
            System.out.println("@@child: " + child + "");

            child.childSteps.addAll(grandChildrenSteps);
            System.out.println("@@child1: " + child + "");
            child.parentStep = this;
            System.out.println("@@child2: " + child + "");
            child.stepFlags.addAll(stepFlags);
            System.out.println("@@child3: " + child + "");
            if (lastChild != null) {
                lastChild.nextSibling = child;
                System.out.println("@@child3.5: " + child + "");
                child.previousSibling = lastChild;
            }
            System.out.println("@@child4: " + child + "");
            child.setStepParsingMap(getStepParsingMap());
            System.out.println("@@child5: " + child + "");
            lastChild = child;
            System.out.println("@@child6: " + child + "");
        }
        System.out.println("@@childSteps.getFirst " + childSteps.getFirst());
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
