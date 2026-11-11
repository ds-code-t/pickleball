package tools.dscode.engine.source;

import tools.dscode.engine.model.EngineFeature;

import java.util.List;

/** Where scenarios come from. None of the implementations require Cucumber. */
public interface ScenarioSource {
    String id();

    List<EngineFeature> load();
}
