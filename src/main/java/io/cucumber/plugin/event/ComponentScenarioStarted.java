package io.cucumber.plugin.event;

import org.apiguardian.api.API;

import java.time.Instant;
import java.util.Objects;

import static io.pickleball.cucumberutilities.CucumberObjectFactory.createPickleStepTestStep;


@API(status = API.Status.STABLE)
public final class ComponentScenarioStarted extends TestCaseEvent {

    private final TestStep testStep;

    public ComponentScenarioStarted(Instant timeInstant, TestCase parentTestCase, TestCase childTestCase) {
        super(timeInstant, parentTestCase);
        System.out.println("@@\"Scenario: \" + childTestCase.getName() ::= " + "Scenario: " + childTestCase.getName());
        PickleStepTestStep newStep = createPickleStepTestStep("zScenario " + childTestCase.getName());
        System.out.println("@@newStep-started: " + newStep.getStep().getText());
        this.testStep = Objects.requireNonNull(newStep);
    }

    public TestStep getTestStep() {
        return testStep;
    }

}
