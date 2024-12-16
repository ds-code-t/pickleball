package io.cucumber.plugin.event;

import org.apiguardian.api.API;

import java.time.Instant;
import java.util.Objects;



@API(status = API.Status.STABLE)
public final class ComponentScenarioStarted extends TestCaseEvent {

    private final TestStep testStep;

    public ComponentScenarioStarted(Instant timeInstant, TestCase testCase, TestStep testStep) {
        super(timeInstant, testCase);
        this.testStep = Objects.requireNonNull(testStep);
    }

    public TestStep getTestStep() {
        return testStep;
    }

}
