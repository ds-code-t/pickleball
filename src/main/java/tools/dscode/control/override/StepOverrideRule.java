package tools.dscode.control.override;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One REPLACE-mode Step Override rule. */
public final class StepOverrideRule implements AutoCloseable {
    private final String id;
    private final StepOverridePatternType patternType;
    private final String pattern;
    private final StepOverrideHandler handler;
    private final Pattern compiledRegex;

    public StepOverrideRule(
            String id,
            StepOverridePatternType patternType,
            String pattern,
            StepOverrideHandler handler
    ) {
        this.id = requireText(id, "id");
        this.patternType = Objects.requireNonNull(patternType, "patternType");
        this.pattern = requireText(pattern, "pattern");
        this.handler = Objects.requireNonNull(handler, "handler");

        if (patternType != StepOverridePatternType.REGEX) {
            throw new IllegalArgumentException("Unsupported Step Override pattern type: " + patternType);
        }
        this.compiledRegex = Pattern.compile(this.pattern);
    }

    public String id() {
        return id;
    }

    public StepOverridePatternType patternType() {
        return patternType;
    }

    public String pattern() {
        return pattern;
    }

    public StepOverrideHandler handler() {
        return handler;
    }

    List<String> matchCaptures(String stepText) {
        Matcher matcher = compiledRegex.matcher(stepText == null ? "" : stepText);
        if (!matcher.matches()) {
            return null;
        }

        List<String> captures = new ArrayList<>(matcher.groupCount());
        for (int i = 1; i <= matcher.groupCount(); i++) {
            captures.add(matcher.group(i));
        }
        return List.copyOf(captures);
    }

    @Override
    public void close() {
        if (handler instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // Removing an experimental override must not fail scenario cleanup.
            }
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
        return value;
    }
}
