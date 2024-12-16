package io.cucumber.plugin.event;

import org.apiguardian.api.API;

import java.time.Instant;
import java.util.Objects;

    @API(status = API.Status.STABLE)
    public final class ComponentScenarioFinished extends TestCaseEvent {

        private final TestStep testStep;
        private final Result result;

        public ComponentScenarioFinished(Instant timeInstant, TestCase testCase, TestStep testStep, Result result) {
            super(timeInstant, testCase);
            this.testStep = Objects.requireNonNull(testStep);
            this.result = Objects.requireNonNull(result);
        }

        public Result getResult() {
            return result;
        }

        public TestStep getTestStep() {
            return testStep;
        }

    }
