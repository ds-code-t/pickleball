package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.cucumber.core.runner.PredefinedSteps.getRootStep;
import static tools.dscode.common.util.DebugUtils.printDebug;

/**
 * Rewrites TestCase construction so that:
 *  - runtime sees a single root predefined step
 *  - original PickleStepTestSteps are wrapped as StepExtensions
 *  - attaches new CurrentScenarioState bound to TestCase
 */
public privileged aspect TestCase_Constructor_StepExtensionsRewrite {

    // Stores one StepExtension per original PickleStepTestStep
    public List<StepExtension> io.cucumber.core.runner.TestCase.stepExtensions;

    public ScenarioStep io.cucumber.core.runner.TestCase.rootScenarioStep;
    public ScenarioStep io.cucumber.core.runner.TestCase.getRootScenarioStep() {
       return rootScenarioStep;
    }
    // ✅ New field introduced as requested
    public CurrentScenarioState io.cucumber.core.runner.TestCase.currentScenarioState;

    pointcut ctorCall(UUID id,
                      List testSteps,
                      List beforeHooks,
                      List afterHooks,
                      Pickle pickle,
                      boolean dryRun) :
            call(io.cucumber.core.runner.TestCase.new(UUID, List, List, List, Pickle, boolean))
                    && args(id, testSteps, beforeHooks, afterHooks, pickle, dryRun);

    io.cucumber.core.runner.TestCase around(
            UUID id,
            List testSteps,
            List beforeHooks,
            List afterHooks,
            Pickle pickle,
            boolean dryRun
    ) : ctorCall(id, testSteps, beforeHooks, afterHooks, pickle, dryRun) {

        GlobalState.language = pickle.getLanguage();
        List original = testSteps;

        printDebug("@@TestCase_Constructor_StepExtensionsRewrite: Original Steps = " + original.size());

        // Replace Cucumber runtime execution list with rootStep only
        List replaced = new ArrayList(1);
        replaced.add(getRootStep() );

        printDebug("@@dryRun: " + dryRun);
        // Build TestCase normally but with replaced list
        TestCase tc = (TestCase) proceed(id, replaced, beforeHooks, afterHooks, pickle, dryRun);

        printDebug("@@tc.name: " + tc.getName());

        // Build extension list
        List<StepExtension> extensions = new ArrayList<>(original.size());
        for (Object o : original) {
            PickleStepTestStep step = (o instanceof PickleStepTestStep)
                    ? (PickleStepTestStep) o : null;
            if (step != null) {
                extensions.add(new StepExtension(tc, step));
            }
        }

        tc.stepExtensions = extensions;
        printDebug("@@stepExtensions: " + extensions.size());

        // ✅ New — assign CurrentScenarioState bound to this TestCase
        tc.currentScenarioState = new CurrentScenarioState(tc);
        tc.rootScenarioStep = ScenarioStep.createRootScenarioStep(tc);
        printDebug("@@tc.rootScenarioStep: " + tc.rootScenarioStep);
        printDebug("@@tc.getTestSteps().size(): " + tc.getTestSteps().size());
        printDebug("@tc.getTestSteps().getFirst().getStepText(): " + ((io.cucumber.plugin.event.PickleStepTestStep)tc.getTestSteps().getFirst()).getStepText());
        return tc;
    }
}
