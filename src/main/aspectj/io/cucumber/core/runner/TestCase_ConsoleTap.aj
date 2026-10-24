package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import tools.dscode.launcher.WorkbenchConsoleTap;

/**
 * Marks System.out / System.err for the scenario that is running, and only
 * when this JVM is a Workbench discover or confirm. A normal mvn test proceeds
 * with the streams it already had.
 */
public aspect TestCase_ConsoleTap {

    declare precedence: TestCase_ConsoleTap, TestCase_ScenarioObjectScope;

    pointcut runCase(TestCase testCase, EventBus bus):
            execution(void io.cucumber.core.runner.TestCase.run(EventBus))
                    && this(testCase)
                    && args(bus);

    void around(TestCase testCase, EventBus bus): runCase(testCase, bus) {
        if (!WorkbenchConsoleTap.active()) {
            proceed(testCase, bus);
            return;
        }
        WorkbenchConsoleTap.enter(testCase.getName());
        try {
            proceed(testCase, bus);
        } finally {
            WorkbenchConsoleTap.leave();
        }
    }
}
