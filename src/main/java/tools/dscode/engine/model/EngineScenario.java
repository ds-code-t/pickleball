package tools.dscode.engine.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One scenario or one expanded outline row. {@code examples} is empty for a
 * plain scenario. An unexpanded outline keeps its examples tables and its
 * template steps (placeholders not substituted).
 */
public record EngineScenario(
        String keyword,
        String name,
        String description,
        List<String> tags,
        List<EngineStep> steps,
        List<ExampleTable> examples,
        SourceLocation location,
        Map<String, String> metadata
) {
    public static final String RULE_NAME = "rule";

    public EngineScenario {
        keyword = keyword == null || keyword.isBlank() ? "Scenario" : keyword;
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        steps = steps == null ? List.of() : List.copyOf(steps);
        examples = examples == null ? List.of() : List.copyOf(examples);
        location = location == null ? SourceLocation.unknown() : location;
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static EngineScenario of(String name, List<EngineStep> steps) {
        return new EngineScenario("Scenario", name, "", List.of(), steps, List.of(), SourceLocation.unknown(), Map.of());
    }

    public EngineScenario withSteps(List<EngineStep> next) {
        return new EngineScenario(keyword, name, description, tags, next, examples, location, metadata);
    }

    public List<EngineStep> allSteps() {
        return new ArrayList<>(steps);
    }
}
