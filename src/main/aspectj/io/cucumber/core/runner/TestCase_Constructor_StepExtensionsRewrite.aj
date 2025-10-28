package io.cucumber.core.runner;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.plugin.event.PickleStepTestStep;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rewrites TestCase construction so that:
 *  - runtime sees a single root predefined step
 *  - original PickleStepTestSteps are wrapped as StepExtensions
 *  - attaches new CurrentScenarioState bound to TestCase
 */
public privileged aspect TestCase_Constructor_StepExtensionsRewrite {

    // Stores one StepExtension per original PickleStepTestStep
    public List<StepExtension> io.cucumber.core.runner.TestCase.stepExtensions;

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

        List original = testSteps;

        System.out.println("@@TestCase_Constructor_StepExtensionsRewrite: Original Steps = " + original.size());

        // Replace Cucumber runtime execution list with rootStep only
        List replaced = new ArrayList(1);
        replaced.add(io.cucumber.core.runner.PredefinedSteps.rootStep);

        // Build TestCase normally but with replaced list
        TestCase tc = (TestCase) proceed(id, replaced, beforeHooks, afterHooks, pickle, dryRun);

        System.out.println("@@tc.name: " + tc.getName());

        // Build extension list
        List<StepExtension> extensions = new ArrayList<>(original.size());
        for (Object o : original) {
            PickleStepTestStep step = (o instanceof PickleStepTestStep)
                    ? (PickleStepTestStep) o : null;
            if (step != null) {
                extensions.add(new StepExtension(pickle, step));
            }
        }

        tc.stepExtensions = extensions;
        System.out.println("@@stepExtensions: " + extensions.size());

        // ✅ New — assign CurrentScenarioState bound to this TestCase
        tc.currentScenarioState = new CurrentScenarioState(tc);

        return tc;
    }
}
