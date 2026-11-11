package tools.dscode.engine.adapter;

import io.cucumber.core.feature.FeatureWithLines;
import io.cucumber.core.feature.GluePath;
import io.cucumber.core.options.RuntimeOptionsBuilder;
import io.cucumber.core.runtime.Runtime;
import io.cucumber.plugin.EventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.PickleStepTestStep;
import io.cucumber.plugin.event.TestCaseFinished;
import io.cucumber.plugin.event.TestCaseStarted;
import io.cucumber.plugin.event.TestStepFinished;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs one feature through Cucumber. Used only by the adapter and the parity harness. */
public final class CucumberAdapterRun {
    private CucumberAdapterRun() {
    }

    public static RunResult run(Path featureFile, String gluePackage) {
        List<Case> cases = new ArrayList<>();
        Runtime runtime = Runtime.builder()
                .withRuntimeOptions(new RuntimeOptionsBuilder()
                        .addFeature(FeatureWithLines.parse(featureFile.toAbsolutePath().toString()))
                        .addGlue(GluePath.parse(gluePackage))
                        .setNoSummary()
                        .build())
                .withAdditionalPlugins(new EventListener() {
                    @Override
                    public void setEventPublisher(EventPublisher publisher) {
                        publisher.registerHandlerFor(TestCaseStarted.class, event ->
                                cases.add(new Case(event.getTestCase().getName())));
                        publisher.registerHandlerFor(TestStepFinished.class, event -> {
                            if (!(event.getTestStep() instanceof PickleStepTestStep step) || cases.isEmpty()) return;
                            cases.getLast().steps.add(new StepResult(
                                    map(event.getResult().getStatus().name()),
                                    "",
                                    step.getStepText(),
                                    "",
                                    event.getResult().getDuration() == null ? 0 : event.getResult().getDuration().toMillis(),
                                    ""
                            ));
                        });
                        publisher.registerHandlerFor(TestCaseFinished.class, event -> {
                            if (!cases.isEmpty()) cases.getLast().status = map(event.getResult().getStatus().name());
                        });
                    }
                })
                .build();
        runtime.run();
        List<ScenarioResult> scenarios = new ArrayList<>();
        for (Case one : cases) {
            scenarios.add(new ScenarioResult(one.status, one.name, one.steps, ""));
        }
        return RunResult.of(scenarios);
    }

    private static Status map(String name) {
        try {
            return Status.valueOf(name);
        } catch (IllegalArgumentException failure) {
            return Status.FAILED;
        }
    }

    private static final class Case {
        private final String name;
        private Status status = Status.PASSED;
        private final List<StepResult> steps = new ArrayList<>();

        private Case(String name) {
            this.name = name == null ? "" : name;
        }
    }
}
