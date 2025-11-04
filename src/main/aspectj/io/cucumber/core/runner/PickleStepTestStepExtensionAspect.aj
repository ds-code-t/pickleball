package io.cucumber.core.runner;


/**
 * Adds a public StepExtension property to PickleStepTestStep.
 *
 * Field is nullable and not initialized by default.
 * Access it as: somePickleStepTestStep.stepExtension
 */
public aspect PickleStepTestStepExtensionAspect {

    // Introduced public field (null by default)
    public io.cucumber.messages.types.PickleStep io.cucumber.core.runner.PickleStepTestStep.getPickleStep() {
        return (io.cucumber.messages.types.PickleStep) tools.dscode.common.util.Reflect.getProperty(getStep(), "pickleStep");
    }



//    public StepExtension io.cucumber.core.runner.PickleStepTestStep.stepExtension;
    // (Optional) convenience accessors — uncomment if you prefer methods.
    // public StepExtension io.cucumber.core.runner.PickleStepTestStep.getStepExtension() { return stepExtension; }
    // public void io.cucumber.core.runner.PickleStepTestStep.setStepExtension(StepExtension ext) { this.stepExtension = ext; }
}
