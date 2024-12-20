package io.cucumber.plugin.event;

import io.cucumber.core.runner.PickleStepTestStep;
import org.apiguardian.api.API;

import java.time.Instant;
import java.util.Objects;

import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;

@API(status = API.Status.STABLE)
public final class ComponentScenarioFinished extends TestCaseEvent {

    private final TestStep testStep;
    private final Result result;

    public ComponentScenarioFinished(Instant timeInstant, TestCase parentTestCase, TestCase childTestCase, Result result) {
        super(timeInstant, parentTestCase);
        PickleStepTestStep newStep = createPickleStepTestStep("zScenario " +childTestCase.getName());
        System.out.println("@@newStep-ended: " + newStep.getStep().getText());
        this.testStep = Objects.requireNonNull(newStep);
        this.result = Objects.requireNonNull(result);
    }

    public Result getResult() {
        return result;
    }

    public TestStep getTestStep() {
        return testStep;
    }

}
