package io.cucumber.core.runner;

import io.cucumber.plugin.event.Result;

import static tools.dscode.common.util.DebugUtils.printDebug;

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
        printDebug("@@mapToResult: " + ((io.cucumber.core.runner.PickleStepTestStep) step).getStepText() + "");
        printDebug("@@_lastResult: " + r);

        step._lastResult = r;
    }
}
