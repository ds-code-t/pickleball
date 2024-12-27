package io.pickleball.customtypes;

public class DynamicStep {
    private final String stepText;

    public DynamicStep(String stepText) {
        this.stepText = stepText;
    }

    public String getStepText() {
        return stepText;
    }



    @Override
    public String toString() {
        return "MetaStep{stepText=" + stepText + '}';
    }
}
