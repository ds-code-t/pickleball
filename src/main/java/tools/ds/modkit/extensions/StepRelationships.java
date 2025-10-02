package tools.ds.modkit.extensions;

import tools.ds.modkit.mappings.ParsingMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public abstract class StepRelationships {

    private final List<StepExtension> childSteps = new ArrayList<>();
    private StepExtension parentStep;
    private StepExtension previousSibling;
    private StepExtension nextSibling;
    protected boolean isFlagStep = false;
    protected final List<String> stepFlags = new ArrayList<>();

    private int nestingLevel;

    public List<String> stepTags = new ArrayList<>();
    public List<String> bookmarks = new ArrayList<>();

    public enum ConditionalStates { SKIP_CHILDREN, SKIP , FALSE, TRUE}

    private final List<ConditionalStates> conditionalStates = new ArrayList<>();

    public List<ConditionalStates> getConditionalStates() {
        return conditionalStates;
    }

    public void addConditionalStates(ConditionalStates... states) {
        this.conditionalStates.addAll(Arrays.stream(states).toList());
    }

    public int getNestingLevel() {
        return nestingLevel;
    }

    public void setNestingLevel(int nestingLevel) {
        this.nestingLevel = nestingLevel;
    }


    public ParsingMap getStepParsingMap() {
        return stepParsingMap;
    }

    public void setStepParsingMap(ParsingMap stepParsingMap) {
        this.stepParsingMap = stepParsingMap;
    }

    public void setParentStep(StepExtension parentStep) {
        this.parentStep = parentStep;
    }

    private ParsingMap stepParsingMap;

    public List<StepExtension> getChildSteps() {
        return childSteps;
    }


    public void addChildStep(StepExtension child) {
        child.setParentStep((StepExtension) this);
        childSteps.add(child);
        child.setStepParsingMap(new ParsingMap(stepParsingMap));
        if (isFlagStep) {
            System.out.println("@@flag-step: "+ this);
            System.out.println("@@flag-child-step: "+ child);
            child.stepFlags.addAll(stepFlags);
        }
    }


    public static void replaceChildStep(StepExtension oldChild, StepExtension newChild) {
        StepExtension parentStep = oldChild.getParentStep();
        if (parentStep == null)
            return;
        newChild.setParentStep(parentStep);
        newChild.setStepParsingMap(new ParsingMap(parentStep.getStepParsingMap()));
        if (parentStep.isFlagStep)
            newChild.stepFlags.addAll(parentStep.stepFlags);
        parentStep.getChildSteps().set(parentStep.getChildSteps().indexOf(oldChild), newChild);
    }


    public StepExtension getParentStep() {
        return parentStep;
    }


    public StepExtension getPreviousSibling() {
        return previousSibling;
    }

    protected void setPreviousSibling(StepExtension previousSibling) {
        this.previousSibling = previousSibling;
    }

    protected void setNextSibling(StepExtension nextSibling) {
        this.nextSibling = nextSibling;
    }

    public static void pairSiblings(StepExtension sibling1, StepExtension sibling2) {
        sibling1.setNextSibling(sibling2);
        sibling2.setPreviousSibling(sibling1);
    }

    public static void InsertNextSiblings(StepExtension sibling1, StepExtension sibling2) {
        sibling1.insertNextSibling(sibling2);
    }


    public StepExtension getNextSibling() {
        return nextSibling;
    }

    public void insertNextSibling(StepExtension insertNextSibling) {
        StepExtension originalNextSibling = getNextSibling();
        if (originalNextSibling != null)
            insertNextSibling.setNextSibling(originalNextSibling);
        System.out.println("@@nextSibling11: " + originalNextSibling);
        setNextSibling(insertNextSibling);
        if (parentStep != null) {
            insertNextSibling.setParentStep(parentStep);
            insertNextSibling.setStepParsingMap(new ParsingMap(parentStep.getStepParsingMap()));
            System.out.println("@@nextSibling22: " + originalNextSibling);
            if (originalNextSibling == null)
                parentStep.getChildSteps().add(insertNextSibling);
            else
                parentStep.getChildSteps().add(parentStep.getChildSteps().indexOf(originalNextSibling), insertNextSibling);
        }
    }

    protected StepExtension templateStep;
    protected boolean isTemplateStep = true;

    public static void copyRelationships(StepExtension copyFrom, StepExtension copyTo)
    {
        copyTo.getChildSteps().addAll(copyFrom.getChildSteps());
        copyTo.setParentStep(copyFrom.getParentStep());
        copyTo.setPreviousSibling(copyFrom.getPreviousSibling());
        copyTo.setStepParsingMap(copyFrom.getStepParsingMap());
        copyTo.setNextSibling(copyFrom.getNextSibling());
        copyTo.setNestingLevel(copyFrom.getNestingLevel());
        copyTo.stepTags = copyFrom.stepTags;
        copyTo.isTemplateStep = false;
        copyTo.templateStep = copyFrom;
        copyTo.stepFlags.addAll(copyFrom.stepFlags);
    }


}
