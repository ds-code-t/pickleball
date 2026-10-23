package io.cucumber.core.runner;

import tools.dscode.Values;

/**
 * One scenario-object map per Cucumber scenario, including its hooks.
 * Cleared before {@code @Before} and after {@code @After}, so the next
 * scenario on this thread starts empty.
 */
public aspect TestCase_ScenarioObjectScope {
    void around(): execution(void io.cucumber.core.runner.TestCase.run(io.cucumber.core.eventbus.EventBus)) {
        Values.clearScenarioObjects();
        try {
            proceed();
        } finally {
            Values.clearScenarioObjects();
        }
    }
}
