package tools.dscode.extensions;

import com.google.common.collect.LinkedListMultimap;
import io.cucumber.core.gherkin.Step;
import io.cucumber.core.runner.HookTestStep;
import io.cucumber.core.runner.PickleStepDefinitionMatch;
import io.cucumber.core.runner.PickleStepTestStep;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static tools.dscode.common.mappings.MapConfigurations.MapType.STEP_MAP;

public abstract class StepRelationships extends PickleStepTestStep {

    protected StepRelationships(
            UUID id, URI uri,
            Step step,
            List<HookTestStep> beforeStepHookSteps,
            List<HookTestStep> afterStepHookSteps,
            PickleStepDefinitionMatch definitionMatch
    ) {
        super(id, uri, step, beforeStepHookSteps, afterStepHookSteps, definitionMatch);
    }

    private List<StepExtension> childSteps = new ArrayList<>();
    private StepExtension parentStep;
    private StepExtension previousSibling;
    private StepExtension nextSibling;
    protected boolean isFlagStep = false;
    protected final List<String> stepFlags = new ArrayList<>();

    private int nestingLevel;

    public List<String> stepTags = new ArrayList<>();
    public List<String> bookmarks = new ArrayList<>();

    public enum ConditionalStates {
        SKIP_CHILDREN, SKIP, FALSE, TRUE
    }

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

    public NodeMap getStepNodeMap() {
        return stepNodeMap;
    }

    private final NodeMap stepNodeMap = new NodeMap(STEP_MAP);

    public void mergeToStepMap(LinkedListMultimap<?, ?> obj) {
        stepNodeMap.merge(obj);
    }

    public void put(Object key, Object value) {
        if (key == null || (key instanceof String && ((String) key).isBlank()))
            throw new RuntimeException("key cannot be null or blank");
        stepNodeMap.put(String.valueOf(key), value);
    }

    public void setStepParsingMap(ParsingMap stepParsingMap) {
        System.out.println("@@setStepParsingMap for " + this);
        this.stepParsingMap.copyParsingMap(stepParsingMap);
        // this.stepParsingMap = stepParsingMap;
        this.stepParsingMap.addMaps(stepNodeMap);
        System.out.println("@@setStepParsingMap " + stepParsingMap);
    }

    public void addToStepParsingMap(NodeMap... nodes) {
        System.out.println("@@this.stepParsingMap1: " + this.stepParsingMap);
        this.stepParsingMap.addMaps(nodes);
        System.out.println("@@this.stepParsingMap2: " + this.stepParsingMap);

    }

    public void setParentStep(StepExtension parentStep) {
        this.parentStep = parentStep;
    }

    private final ParsingMap stepParsingMap = new ParsingMap();

    public void initializeChildSteps() {
        // System.out.println("@@parent: " + this);
        System.out.println("@@parent-stepParsingMap: " + stepParsingMap);
        childSteps.forEach(this::initializeChildStep);
    }

    public void initializeChildStep(StepExtension child) {
        System.out.println("@@parent## " + this);
        System.out.println("@@parent##--stepParsingMap: " + stepParsingMap);
        // if (child.inheritFromParent)
        // child.setStepParsingMap(new ParsingMap(stepParsingMap));
        // System.out.println("@@child: " + child);
        // System.out.println("@@child-stepParsingMap: " +
        // child.getStepParsingMap());
    }

    public List<StepExtension> getChildSteps() {
        return childSteps;
    }

    public void clearChildSteps() {
        childSteps = new ArrayList<>();
    }

    public void setChildSteps(List<StepExtension> newChildren) {
        childSteps.clear();
        newChildren.forEach(this::addChildStep);
    }

    public void addChildStep(StepExtension child) {
        System.out.println("@@addChildStep-currentChildren: " + childSteps.size());
        System.out.println("@@addChildStep-getStepParsingMap: " + child.getStepParsingMap());
        child.setParentStep((StepExtension) this);
        childSteps.add(child);
        if (isFlagStep) {
            System.out.println("@@flag-step: " + this);
            System.out.println("@@flag-child-step: " + child);
            child.stepFlags.addAll(stepFlags);
        }
    }

    public static void replaceChildStep(StepExtension oldChild, StepExtension newChild) {
        StepExtension parentStep = oldChild.getParentStep();
        if (parentStep == null)
            return;
        newChild.setParentStep(parentStep);
        // newChild.setStepParsingMap(new
        // ParsingMap(parentStep.getStepParsingMap()));
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
        System.out.println("@@pairSiblings: ");
        System.out.println("@@sibling1: " + sibling1);
        System.out.println("@@sibling2: " + sibling2);
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
            // insertNextSibling.setStepParsingMap(new
            // ParsingMap(parentStep.getStepParsingMap()));
            System.out.println("@@nextSibling22: " + originalNextSibling);
            if (originalNextSibling == null)
                parentStep.getChildSteps().add(insertNextSibling);
            else
                parentStep.getChildSteps().add(parentStep.getChildSteps().indexOf(originalNextSibling),
                    insertNextSibling);
        }
    }

    protected StepExtension templateStep;
    protected boolean isTemplateStep = true;

    public static void copyRelationships(StepExtension copyFrom, StepExtension copyTo) {
        copyTo.getChildSteps().addAll(copyFrom.getChildSteps());
        copyTo.setParentStep(copyFrom.getParentStep());
        copyTo.setPreviousSibling(copyFrom.getPreviousSibling());
        // copyTo.setStepParsingMap(copyFrom.getStepParsingMap());
        copyTo.setNextSibling(copyFrom.getNextSibling());
        copyTo.setNestingLevel(copyFrom.getNestingLevel());
        copyTo.stepTags = copyFrom.stepTags;
        copyTo.isTemplateStep = false;
        copyTo.templateStep = copyFrom;
        copyTo.stepFlags.addAll(copyFrom.stepFlags);
    }

}
