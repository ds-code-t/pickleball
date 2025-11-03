// File: src/main/aspectj/io/cucumber/core/runner/PickleStepTestStep_RunInterceptor.aj
package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.plugin.event.TestCase;
import tools.dscode.common.annotations.DefinitionFlag;

import static io.cucumber.core.runner.GlobalState.getTestCaseState;
import static tools.dscode.common.GlobalConstants.ROOT_STEP;


public privileged aspect PickleStepTestStep_RunInterceptor {

    /**
     * Match the concrete run(...) method on PickleStepTestStep:
     *   ExecutionMode run(TestCase testCase, EventBus bus, TestCaseState state, ExecutionMode executionMode)
     */
    pointcut runInvocation(PickleStepTestStep self,
                           TestCase testCase,
                           EventBus bus,
                           TestCaseState state,
                           ExecutionMode mode) :
            execution(io.cucumber.core.runner.ExecutionMode io.cucumber.core.runner.PickleStepTestStep.run(..))
                    && this(self)
                    && args(testCase, bus, state, mode);

    /**
     * Around advice: if this is the ROOT step, bypass normal execution and
     * directly run the definition with current state; then return null.
     * Otherwise, proceed as usual.
     */
    io.cucumber.core.runner.ExecutionMode
            around(PickleStepTestStep self,
                   TestCase testCase,
                   EventBus bus,
                   TestCaseState state,
                   ExecutionMode mode) : runInvocation(self, testCase, bus, state, mode) {

        String text = self.getStepText();
        System.out.println("@@text=: " + text + "");
        System.out.println("@@ROOT_STEP=: " + ROOT_STEP + "");
//        if (text != null && text.equals(ROOT_STEP)) {
        if (self.getDefinitionFlags().contains(DefinitionFlag.RUN_METHOD_DIRECTLY)) {
            System.out.println("@@getDefinitionMatch-runStep:");
            // Run the underlying definition directly and do NOT execute the normal run body
            System.out.println("@@getTestCaseState(): " + getTestCaseState() );
            try {
                self.getDefinitionMatch().runStep(getTestCaseState()); // equivalent to runStep(getTestCaseState())
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
            return ExecutionMode.RUN; // per your requirement
        }

        // Not the root step: run as normal
        return proceed(self, testCase, bus, state, mode);
    }
}
