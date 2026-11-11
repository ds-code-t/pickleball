package tools.dscode.engine.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One step. Text is the step body without the keyword. Metadata is an open
 * bag (background flag, runtime-written marker, source kind) and is not Gherkin.
 */
public record EngineStep(
        String keyword,
        String text,
        StepArgument argument,
        SourceLocation location,
        Map<String, String> metadata,
        boolean fromBackground
) {
    public EngineStep {
        keyword = keyword == null ? "" : keyword;
        text = text == null ? "" : text;
        argument = argument == null ? StepArgument.none() : argument;
        location = location == null ? SourceLocation.unknown() : location;
        metadata = metadata == null || metadata.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public static EngineStep of(String keyword, String text) {
        return new EngineStep(keyword, text, StepArgument.none(), SourceLocation.unknown(), Map.of(), false);
    }

    public EngineStep withMetadata(String key, String value) {
        LinkedHashMap<String, String> copy = new LinkedHashMap<>(metadata);
        copy.put(key, value == null ? "" : value);
        return new EngineStep(keyword, text, argument, location, copy, fromBackground);
    }
}
