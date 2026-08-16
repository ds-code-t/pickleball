package tools.dscode.studio.language;

public record SourceLocation(
        String path,
        int line,
        int column
) {
}
