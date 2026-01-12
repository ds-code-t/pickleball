package io.cucumber.core.runner;

import io.cucumber.core.stepexpression.Argument;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;
import tools.dscode.common.annotations.DefinitionFlag;
import tools.dscode.common.mappings.NodeMap;
import tools.dscode.common.mappings.ParsingMap;
import tools.dscode.common.treeparsing.preparsing.LineData;
import tools.dscode.coredefinitions.GeneralSteps;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static tools.dscode.common.mappings.MapConfigurations.MapType.STEP_MAP;


public abstract class StepBase implements Cloneable {
    public boolean isDynamicStep;
    public boolean isCoreConditionalStep;
    public boolean logAndIgnore = false;
    public boolean isClone = false;
    public List<StepBase> clones = new ArrayList<>();

    protected boolean runMethodDirectly = false;
    public boolean debugStartStep = false;
    public LineData inheritedLineData;
    public LineData lineData;
    public io.cucumber.core.runner.PickleStepTestStep pickleStepTestStep;
    public io.cucumber.core.runner.PickleStepTestStep executingPickleStepTestStep;
    public TestCase testCase;
    //    public Pickle pickle;
    public List<StepBase> childSteps = new ArrayList<>();
    public List<StepBase> grandChildrenSteps = new ArrayList<>();
    public List<StepBase> attachedSteps = new ArrayList<>();
    public StepBase parentStep;
    public StepBase previousSibling;
    public StepBase nextSibling;
    public String overrideLoggingText = null;
    protected List<Argument> arguments;
    public Argument argument;

    protected final ParsingMap stepParsingMap = new ParsingMap();
    protected final NodeMap stepNodeMap = new NodeMap(STEP_MAP);


    protected int nestingLevel = 0;
    public String codeLocation;
    public boolean isCoreStep;
    protected List<String> stepFlags = new ArrayList<>();
    protected List<DefinitionFlag> inheritableDefinitionFlags = new ArrayList<>();
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
    protected List<ConditionalStates> conditionalStates = new ArrayList<>();

    public DocString docString;
    public DataTable dataTable;


    public enum ConditionalStates {
        SKIP, FALSE, TRUE
    }


    public abstract void setStepParsingMap(ParsingMap stepParsingMap);

    protected abstract DocString getDocString();

    protected abstract DocString getDocStringFromParent();

    protected abstract DataTable getDataTable();

    protected abstract DataTable getDataTableFromParent();

    public abstract Collection<ConditionalStates> getConditionalStates();


    @Override
    public StepBase clone() {
        try {
            // 1. Shallow copy of this StepBase
            StepBase copy = (StepBase) super.clone();
            copy.clones = new ArrayList<>();
            copy.isClone = true;
            // Clone LineData shallowly (new instance)
            if (this.lineData != null) {
                copy.lineData = this.lineData.clone();
            }

            // 2. Deep-clone StepBase lists
            copy.childSteps = deepCloneSteps(this.childSteps);
            copy.grandChildrenSteps = deepCloneSteps(this.grandChildrenSteps);
            copy.attachedSteps = deepCloneSteps(this.attachedSteps);

            // 3. Shallow-copy non-StepBase lists (new list, same elements)
            copy.inheritableDefinitionFlags = shallowCopyList(this.inheritableDefinitionFlags);
            copy.definitionFlags = shallowCopyList(this.definitionFlags);
            copy.nextSiblingDefinitionFlags = shallowCopyList(this.nextSiblingDefinitionFlags);

            copy.stepTags = shallowCopyList(this.stepTags);
            copy.bookmarks = shallowCopyList(this.bookmarks);
            copy.arguments = shallowCopyList(this.arguments);
            copy.stepFlags = shallowCopyList(this.stepFlags);
            copy.conditionalStates = shallowCopyList(this.conditionalStates);

            if (nextSibling != null) {
                copy.nextSibling = nextSibling.clone();
                copy.nextSibling.previousSibling = copy;
            }


            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("StepBase should be cloneable", e);
        }
    }

    private static List<StepBase> deepCloneSteps(List<StepBase> source) {
        if (source == null) {
            return null;
        }
        List<StepBase> result = new ArrayList<>(source.size());
        for (StepBase step : source) {
            result.add(step != null ? step.clone() : null);
        }
        return result;
    }

    private static <T> List<T> shallowCopyList(List<T> source) {
        return (source == null) ? null : new ArrayList<>(source);
    }


}
