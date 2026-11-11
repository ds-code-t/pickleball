package tools.dscode.engine.source;

import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.parse.GherkinEngineParser;

import java.util.List;

/** Gherkin text that is not a file. */
public final class MemoryScenarioSource implements ScenarioSource {
    private final String text;

    public MemoryScenarioSource(String text) {
        this.text = text == null ? "" : text;
    }

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public List<EngineFeature> load() {
        return List.of(GherkinEngineParser.parse("memory:", text));
    }
}
