package tools.dscode.studio.language;

public record SourceSymbol(
        SourceLanguage language,
        SourceSymbolKind kind,
        String name,
        String qualifiedName,
        String container,
        SourceLocation location
) {
}
