package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.plugin.event.TestCase;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Adds a noLogging flag to TestStep. When true, suppresses event emission
 * for start/finish calls without modifying Cucumber source.
 */
public privileged aspect TestStepNoLoggingAspect {

    /* ======================================================
     * Introduced property
     * ====================================================== */
    public boolean io.cucumber.core.runner.TestStep.noLogging = false;

    public boolean io.cucumber.core.runner.TestStep.isNoLogging() {
        return noLogging;
    }

    public void io.cucumber.core.runner.TestStep.setNoLogging(boolean v) {
        noLogging = v;
    }

    /* ======================================================
     * Intercept PRIVATE method executions and route arguments
     * ====================================================== */

    // emitTestStepStarted(TestCase, EventBus, UUID, Instant)
    pointcut emitStartedExec(
            TestStep step,
            TestCase tc,
            EventBus bus,
            UUID execId,
            Instant start
    ) :
            execution(void io.cucumber.core.runner.TestStep.emitTestStepStarted(..))
                    && this(step)
                    && args(tc, bus, execId, start);

    // emitTestStepFinished(TestCase, EventBus, UUID, Instant, Duration, Result)
    pointcut emitFinishedExec(
            TestStep step,
            TestCase tc,
            EventBus bus,
            UUID execId,
            Instant stop,
            Duration duration,
            io.cucumber.plugin.event.Result result
    ) :
            execution(void io.cucumber.core.runner.TestStep.emitTestStepFinished(..))
                    && this(step)
                    && args(tc, bus, execId, stop, duration, result);

    /* ======================================================
     * Advices — skip if noLogging == true
     * ====================================================== */

    void around(TestStep step,
                TestCase tc,
                EventBus bus,
                UUID execId,
                Instant start)
            : emitStartedExec(step, tc, bus, execId, start)
            {
                System.out.println("@@emitStartedExec: " + ((io.cucumber.core.runner.PickleStepTestStep) step).getStepText() + "");
                System.out.println("@@step.noLogging: " + step.noLogging);
                if (step.noLogging) {
                    return; // suppressed
                }
                proceed(step, tc, bus, execId, start);
            }

    void around(TestStep step,
                TestCase tc,
                EventBus bus,
                UUID execId,
                Instant stop,
                Duration duration,
                io.cucumber.plugin.event.Result result)
            : emitFinishedExec(step, tc, bus, execId, stop, duration, result)
            {
                System.out.println("@@emitFinishedExec: " + ((io.cucumber.core.runner.PickleStepTestStep) step).getStepText() + "");
                System.out.println("@@step.noLogging: " + step.noLogging);

                if (step.noLogging) {
                    return; // suppressed
                }
                proceed(step, tc, bus, execId, stop, duration, result);
            }
}
