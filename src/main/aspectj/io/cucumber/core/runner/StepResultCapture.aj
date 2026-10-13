package io.cucumber.core.runner;

import io.cucumber.plugin.event.Result;

/**
 * Capture the step Result as soon as it is created inside TestStep.run(..),
 * independent of any logging suppression.
 */
public privileged aspect StepResultCapture {

    // ---- Per-step storage + accessors (ITDs) ----
    private Result io.cucumber.core.runner.TestStep._lastResult;
    public  Result io.cucumber.core.runner.TestStep.getLastResult()   { return _lastResult; }
    public  void   io.cucumber.core.runner.TestStep.clearLastResult() { _lastResult = null; }

    // ---- Hook the factory that builds the Result inside TestStep.run(..) ----
    pointcut mapToResult(TestStep step) :
            execution(io.cucumber.plugin.event.Result io.cucumber.core.runner.TestStep.mapStatusToResult(..))
                    && this(step);

    // After the Result is created, stash it on the step instance
    after(TestStep step) returning (Result r) : mapToResult(step) {
        step._lastResult = r;
    }

    /**
     * Removes failed, pending, and undefined results so a check can observe a
     * hard failure after cleanup without Cucumber failing the scenario.
     * Passing and skipped results stay.
     */
    public void io.cucumber.core.runner.TestCaseState.dropNonPassingStepResults() {
        java.util.Iterator<io.cucumber.plugin.event.Result> results = stepResults.iterator();
        while (results.hasNext()) {
            io.cucumber.plugin.event.Status status = results.next().getStatus();
            if (status != io.cucumber.plugin.event.Status.PASSED
                    && status != io.cucumber.plugin.event.Status.SKIPPED) {
                results.remove();
            }
        }
    }
}
