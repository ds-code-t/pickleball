package io.cucumber.core.runner;


public class GeneralGherkinUtils {

    public static String getKeyWord(StepExtension stepExtension)
    {
        if (stepExtension == null || stepExtension.pickleStepTestStep == null) {
            return stepExtension == null || stepExtension.nativeKeyword == null ? "" : stepExtension.nativeKeyword;
        }
        return stepExtension.pickleStepTestStep.getStep().getKeyword();
    }

    public static String getKeyWord(PickleStepTestStep pickleStepTestStep)
    {
        return pickleStepTestStep.getStep().getKeyword();
    }


    public static String getStepText(StepExtension stepExtension)
    {
        return stepExtension == null ? "" : stepExtension.getStepText();
    }

    public static String getStepText(PickleStepTestStep pickleStepTestStep)
    {
        return pickleStepTestStep.getStepText();
    }


}
