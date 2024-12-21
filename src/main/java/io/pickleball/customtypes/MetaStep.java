package io.pickleball.customtypes;

public class MetaStep {
    private final String stepText;

    public MetaStep(String stepText) {
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
