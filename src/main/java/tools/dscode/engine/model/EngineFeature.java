package tools.dscode.engine.model;

import java.util.List;

/**
 * Feature container. Background steps are stored once and also copied onto
 * each scenario by the parser when a run needs them in order.
 */
public record EngineFeature(
        String name,
        String description,
        String language,
        List<String> tags,
        List<EngineStep> background,
        List<EngineScenario> scenarios,
        SourceLocation location
) {
    public EngineFeature {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        language = language == null || language.isBlank() ? "en" : language;
        tags = tags == null ? List.of() : List.copyOf(tags);
        background = background == null ? List.of() : List.copyOf(background);
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        location = location == null ? SourceLocation.unknown() : location;
    }

    public static EngineFeature of(String name, List<EngineScenario> scenarios) {
        return new EngineFeature(name, "", "en", List.of(), List.of(), scenarios, SourceLocation.unknown());
    }
}
