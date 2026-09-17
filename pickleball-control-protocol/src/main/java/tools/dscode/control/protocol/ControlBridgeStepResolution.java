package tools.dscode.control.protocol;

/**
 * Result of resolving one Gherkin step to a Java definition without executing it.
 *
 * <p>{@code kind} is {@code CONSUMER_GLUE}, {@code DYNAMIC}, {@code OVERRIDE}, or
 * {@code UNMATCHED}. All strings are null-safe empty defaults.
 */
public record ControlBridgeStepResolution(
        String kind,
        String pattern,
        String className,
        String methodName,
        String sourcePath,
        String snippet,
        String detail
) {
    public static final String CONSUMER_GLUE = "CONSUMER_GLUE";
    public static final String DYNAMIC = "DYNAMIC";
    public static final String OVERRIDE = "OVERRIDE";
    public static final String UNMATCHED = "UNMATCHED";

    public ControlBridgeStepResolution {
        kind = kind == null ? "" : kind;
        pattern = pattern == null ? "" : pattern;
        className = className == null ? "" : className;
        methodName = methodName == null ? "" : methodName;
        sourcePath = sourcePath == null ? "" : sourcePath;
        snippet = snippet == null ? "" : snippet;
        detail = detail == null ? "" : detail;
    }

    public static ControlBridgeStepResolution unmatched(String detail) {
        return new ControlBridgeStepResolution(UNMATCHED, "", "", "", "", "", detail);
    }
}
