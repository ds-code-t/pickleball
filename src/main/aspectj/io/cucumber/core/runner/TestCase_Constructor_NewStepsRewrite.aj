// TestCase_Constructor_NewStepsRewrite.aj
// AspectJ 1.9+ / Java 21
//
// Intercepts the *call* to TestCase's constructor (not execution), so we can
// replace the testSteps argument and still return the constructed instance.

package io.cucumber.core.runner;

import java.sql.SQLOutput;

public privileged aspect TestCase_Constructor_NewStepsRewrite {

    // ITD: a place to keep the original steps list the caller provided
    public java.util.List<io.cucumber.core.runner.PickleStepTestStep>
            io.cucumber.core.runner.TestCase.newSteps;

    /**
     * Intercept the CALL to:
     *   new TestCase(UUID, List, List, List, Pickle, boolean)
     * Steps:
     *  - stash the caller's original 'testSteps' into tc.newSteps
     *  - replace ctor arg 'testSteps' with [PredefinedSteps.rootStep]
     *  - return the constructed TestCase
     */
    io.cucumber.core.runner.TestCase around(
            java.util.UUID id,
            java.util.List testSteps,         // erased type is fine
            java.util.List beforeHooks,
            java.util.List afterHooks,
            io.cucumber.core.gherkin.Pickle pickle,
            boolean dryRun
    ):
            call(io.cucumber.core.runner.TestCase.new(
                    java.util.UUID,
                            java.util.List,
                            java.util.List,
                            java.util.List,
                            io.cucumber.core.gherkin.Pickle,
                            boolean
                    )) &&
                    args(id, testSteps, beforeHooks, afterHooks, pickle, dryRun) {

        // Preserve the original list the caller passed
        java.util.List original = testSteps;

        System.out.println("@@TestCase_Constructor_NewStepsRewrite: " + original.size() + "");

        // Supply a single predefined step instead
        java.util.List replaced = new java.util.ArrayList(1);
        replaced.add(io.cucumber.core.runner.PredefinedSteps.rootStep);

        // Construct with the replaced steps list
        io.cucumber.core.runner.TestCase tc =
                (io.cucumber.core.runner.TestCase)
                        proceed(id, replaced, beforeHooks, afterHooks, pickle, dryRun);
        System.out.println("@@tc. name: " + tc.getName());
        // Attach the original steps to our ITD field
        // noinspection unchecked
        tc.newSteps = (java.util.List<io.cucumber.core.runner.PickleStepTestStep>) original;

        return tc;
    }
}
