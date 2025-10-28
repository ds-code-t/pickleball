// Runner_AddCreateStepForText.aj
// AspectJ 1.9+ / Java 21
//
// - Declares that Runner implements RunnerExtras
// - Introduces public PickleStepTestStep createStepForText(Pickle, String)
// - Prepares worlds/glue, matches the step, wires before/after step hooks, and cleans up

package io.cucumber.core.runner;

public privileged aspect Runner_AddCreateStepForText {

    // Ensure Runner implements the public interface you call from Java.
    // (Interface must declare createStepForText(Pickle,String).)
    declare parents: io.cucumber.core.runner.Runner
            implements io.cucumber.core.runner.RunnerExtras;

    /**
     * Create a fully matched PickleStepTestStep for the step (by text) contained in the given pickle.
     * This method:
     *  - Locates the step in pickle.getSteps() whose text equals stepText
     *  - Prepares backend worlds & glue for that pickle's locale
     *  - Uses Runner internals to match the step and gather before/after step hooks
     *  - Constructs the PickleStepTestStep
     *  - Cleans up scenario-scoped glue and worlds
     */
    public io.cucumber.core.runner.PickleStepTestStep
            io.cucumber.core.runner.Runner.createStepForText(
            io.cucumber.core.gherkin.Pickle pickle,
            String stepText
    ) {

        // Find the step by its *raw text* (no keyword). Caller should pass raw text.
        io.cucumber.core.gherkin.Step step = null;
        java.util.List<io.cucumber.core.gherkin.Step> steps = pickle.getSteps();
        for (io.cucumber.core.gherkin.Step s : steps) {
            if (java.util.Objects.equals(s.getText(), stepText)) {
                step = s;
                break;
            }
        }
        if (step == null) {
            throw new IllegalArgumentException("Step text not found in pickle: " + stepText);
        }

        try {
            // Prepare worlds and glue for matching (mirrors Runner code paths).
            this.buildBackendWorlds();
            this.glue.prepareGlue(this.localeForPickle(pickle));
            this.snippetGenerators =
                    this.createSnippetGeneratorsForPickle(this.glue.getStepTypeRegistry());

            // Match definition + gather hooks using Runner internals.
            io.cucumber.core.runner.PickleStepDefinitionMatch match =
                    this.matchStepToStepDefinition(pickle, step);

            java.util.List<io.cucumber.core.runner.HookTestStep> afterStepHookSteps =
                    this.createAfterStepHooks(pickle.getTags());

            java.util.List<io.cucumber.core.runner.HookTestStep> beforeStepHookSteps =
                    this.createBeforeStepHooks(pickle.getTags());

            // Construct the fully wired step.
            return new io.cucumber.core.runner.PickleStepTestStep(
                    this.bus.generateId(),
                    pickle.getUri(),
                    step,
                    beforeStepHookSteps,
                    afterStepHookSteps,
                    match
            );

        } finally {
            // Always clean up.
            this.glue.removeScenarioScopedGlue();
            this.disposeBackendWorlds();
        }
    }
}
