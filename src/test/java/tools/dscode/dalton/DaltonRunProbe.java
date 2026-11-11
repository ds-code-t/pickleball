package tools.dscode.dalton;

import io.cucumber.java.en.Given;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestStepFinished;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test-only step and Cucumber listener. Records what a real run actually did. */
public final class DaltonRunProbe implements ConcurrentEventListener {
    public static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());
    public static final List<String> REACHED = Collections.synchronizedList(new ArrayList<>());

    public DaltonRunProbe() {
    }

    public static void reset() {
        EVENTS.clear();
        REACHED.clear();
    }

    @Given("^dalton probe records (.*)$")
    public void records(String value) {
        String kept = value == null ? "" : value;
        REACHED.add(kept);
        DaltonRunTrace.append("reached " + kept);
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestCaseFinished.class, event ->
                EVENTS.add("scenario " + event.getResult().getStatus() + " " + event.getTestCase().getName()));
        publisher.registerHandlerFor(TestStepFinished.class, event -> {
            if (event.getTestStep() instanceof PickleStepTestStep step && step.getStep() != null) {
                EVENTS.add("step " + event.getResult().getStatus() + " " + step.getStep().getText());
            }
        });
    }
}
