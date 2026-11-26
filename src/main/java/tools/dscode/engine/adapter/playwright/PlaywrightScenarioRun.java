package tools.dscode.engine.adapter.playwright;

import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.ForeignStepHosts;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;
import tools.dscode.engine.model.ScenarioResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.model.StepResult;

import java.util.ArrayList;
import java.util.List;

/** Runs a scenario whose keyword is {@code Playwright}. The step text stays the source line. */
public final class PlaywrightScenarioRun {
    private PlaywrightScenarioRun() {
    }

    public static ScenarioResult run(EngineScenario scenario) {
        ExecutionContext context = ExecutionContext.open(scenario);
        List<StepResult> results = new ArrayList<>();
        try {
            for (EngineStep step : scenario.steps()) {
                long started = System.nanoTime();
                String definition = step.metadata().getOrDefault("definition", "");
                try {
                    Object returned = ForeignStepHosts.invoke(definition, step.text(), List.of(), PlaywrightTestIngest.HOST);
                    long millis = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
                    results.add(new StepResult(
                            Status.PASSED,
                            step.keyword(),
                            step.text(),
                            "",
                            millis,
                            returned == null ? "" : String.valueOf(returned)));
                } catch (RuntimeException failure) {
                    long millis = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
                    String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    results.add(new StepResult(Status.FAILED, step.keyword(), step.text(), message, millis, ""));
                    break;
                }
            }
        } finally {
            context.close();
        }
        return ScenarioResult.fromSteps(scenario.name(), results);
    }
}
