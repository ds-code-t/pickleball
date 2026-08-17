
package com.example.pickleball;

import org.junit.jupiter.api.Test;
import tools.dscode.common.control.ControlDecision;
import tools.dscode.common.control.ControlHook;
import tools.dscode.common.control.ControlHookHandler;
import tools.dscode.common.control.ControlRuntime;
import tools.dscode.control.api.ControlCallResult;
import tools.dscode.control.api.DynamicControl;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ControlRuntimeObserverChecks {

    @Test
    void observerIsAdditiveToExistingThreadHandler() {
        ControlApiTestSteps.reset();
        AtomicInteger observedBeforeSteps = new AtomicInteger();

        ControlHookHandler observer = event -> {
            if (event.hook() == ControlHook.BEFORE_STEP) {
                observedBeforeSteps.incrementAndGet();
            }
            return ControlDecision.CONTINUE;
        };

        ControlRuntime.addObserver(observer);
        try {
            ControlCallResult<Object> result = ControlRuntime.withThreadHandler(
                    event -> event.hook() == ControlHook.BEFORE_STEP
                            ? ControlDecision.SKIP
                            : ControlDecision.CONTINUE,
                    () -> DynamicControl.executeStep("CONTROL API TEST STEP")
            );

            assertTrue(result.successful(), () -> String.valueOf(result.error()));
            assertEquals(0, ControlApiTestSteps.invocationCount());
            assertTrue(observedBeforeSteps.get() > 0);
        } finally {
            ControlRuntime.removeObserver(observer);
        }
    }

    @Test
    void observerTriggeredControlWorkStillReachesPrimaryHandlerWithoutObserverRecursion() {
        ControlApiTestSteps.reset();
        AtomicInteger observerCalls = new AtomicInteger();
        AtomicInteger primaryBeforeSteps = new AtomicInteger();
        AtomicReference<ControlCallResult<Object>> nestedResult = new AtomicReference<>();

        ControlHookHandler observer = event -> {
            if (event.hook() == ControlHook.BEFORE_SERVICE_CALL) {
                observerCalls.incrementAndGet();
                nestedResult.set(DynamicControl.executeStep("CONTROL API TEST STEP"));
            }
            return ControlDecision.CONTINUE;
        };

        ControlRuntime.addObserver(observer);
        try {
            ControlRuntime.withThreadHandler(
                    event -> {
                        if (event.hook() == ControlHook.BEFORE_STEP) {
                            primaryBeforeSteps.incrementAndGet();
                        }
                        return ControlDecision.CONTINUE;
                    },
                    () -> {
                        ControlRuntime.fire(
                                ControlHook.BEFORE_SERVICE_CALL,
                                "observer-test",
                                null
                        );
                        return null;
                    }
            );

            assertEquals(1, observerCalls.get());
            assertTrue(nestedResult.get().successful(), () -> String.valueOf(nestedResult.get().error()));
            assertTrue(primaryBeforeSteps.get() > 0);
            assertEquals(1, ControlApiTestSteps.invocationCount());
        } finally {
            ControlRuntime.removeObserver(observer);
        }
    }
}
