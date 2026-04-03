package io.cucumber.core.runner;


public class GeneralGherkinUtils {

    public static String getKeyWord(StepExtension stepExtension)
    {
        return stepExtension.pickleStepTestStep.getStep().getKeyword();
    }

    public static String getKeyWord(PickleStepTestStep pickleStepTestStep)
    {
        return pickleStepTestStep.getStep().getKeyword();
    }


    public static String getStepText(StepExtension stepExtension)
    {
        return stepExtension.pickleStepTestStep.getStepText();
    }

    public static String getStepText(PickleStepTestStep pickleStepTestStep)
    {
        return pickleStepTestStep.getStepText();
    }


}
