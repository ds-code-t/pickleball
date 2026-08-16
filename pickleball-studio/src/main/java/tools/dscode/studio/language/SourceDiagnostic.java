package tools.dscode.studio.language;

public record SourceDiagnostic(
        String severity,
        String message,
        Integer line,
        Integer column
) {
}
