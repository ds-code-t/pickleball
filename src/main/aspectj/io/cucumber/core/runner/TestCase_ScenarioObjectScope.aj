package io.cucumber.core.runner;

import io.cucumber.core.eventbus.EventBus;
import tools.dscode.Values;

/**
 * One scenario-object map per Cucumber scenario, including its hooks.
 * Cleared before {@code @Before} and after {@code @After}, so the next
 * scenario on this thread starts empty. The scenario thread local is
 * removed in the same finally when it still belongs to this test case,
 * so a later verb on the thread is not inside that finished scenario.
 */
public aspect TestCase_ScenarioObjectScope {
    void around(TestCase testCase, EventBus bus):
            execution(void io.cucumber.core.runner.TestCase.run(EventBus))
                    && this(testCase)
                    && args(bus) {
        Values.clearScenarioObjects();
        try {
            proceed(testCase, bus);
        } finally {
            Values.clearScenarioObjects();
            CurrentScenarioState live = CurrentScenarioState.currentScenarioState.get();
            if (live != null && live == testCase.currentScenarioState) {
                CurrentScenarioState.currentScenarioState.remove();
            }
        }
    }
}
