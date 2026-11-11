package tools.dscode.engine.source;

import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.model.EngineScenario;
import tools.dscode.engine.model.EngineStep;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A runtime list of step strings. No file and no URI. */
public final class StepListScenarioSource implements ScenarioSource {
    private static final Pattern KEYWORD = Pattern.compile("^(Given|When|Then|And|But|\\*)\\s+(.*)$", Pattern.DOTALL);
    private final String name;
    private final List<String> steps;

    public StepListScenarioSource(String name, List<String> steps) {
        this.name = name == null || name.isBlank() ? "runtime" : name;
        this.steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @Override
    public String id() {
        return "steps";
    }

    @Override
    public List<EngineFeature> load() {
        List<EngineStep> parsed = new ArrayList<>();
        for (String raw : steps) {
            String line = raw == null ? "" : raw;
            Matcher matcher = KEYWORD.matcher(line);
            EngineStep step = matcher.matches()
                    ? EngineStep.of(matcher.group(1) + " ", matcher.group(2))
                    : EngineStep.of("* ", line);
            parsed.add(step.withMetadata("source", "runtime"));
        }
        EngineScenario scenario = EngineScenario.of(name, parsed);
        return List.of(new EngineFeature(name, "", "en", List.of(), List.of(), List.of(scenario), scenario.location()));
    }
}
