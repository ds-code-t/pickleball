package io.cucumber.core.runner;

import java.util.ArrayList;
import java.util.List;

public class StepRelationships {
    public List<StepRelationships> childSteps = new ArrayList<>();
    public StepRelationships parentStep;
    public StepRelationships previousSibling;
    public StepRelationships nextSibling;
    public int nestingLevel = 0;

}
