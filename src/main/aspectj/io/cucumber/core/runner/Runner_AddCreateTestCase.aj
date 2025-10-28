// Runner_AddCreateTestCase.aj
// AspectJ 1.9+ / Java 21
//
// - Makes Runner implement RunnerExtras
// - Introduces public TestCase createTestCase(Pickle) into Runner

package io.cucumber.core.runner;

public privileged aspect Runner_AddCreateTestCase {

    // At weave time, Runner will implement this interface
    declare parents: io.cucumber.core.runner.Runner implements io.cucumber.core.runner.RunnerExtras;

    // Introduced method body
    public io.cucumber.core.runner.TestCase
            io.cucumber.core.runner.Runner.createTestCase(io.cucumber.core.gherkin.Pickle pickle) {
        try {
            // Prepare worlds / glue
            this.buildBackendWorlds();
            this.glue.prepareGlue(this.localeForPickle(pickle));
            this.snippetGenerators =
                    this.createSnippetGeneratorsForPickle(this.glue.getStepTypeRegistry());

            // Delegate to existing factory
            return this.createTestCaseForPickle(pickle);
        } finally {
            // Cleanup
            this.glue.removeScenarioScopedGlue();
            this.disposeBackendWorlds();
        }
    }
}
