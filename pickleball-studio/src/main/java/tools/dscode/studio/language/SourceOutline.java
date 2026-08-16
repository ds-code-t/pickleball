package tools.dscode.studio.language;

import java.util.List;

public record SourceOutline(
        String path,
        SourceLanguage language,
        List<SourceSymbol> symbols,
        List<SourceDiagnostic> diagnostics
) {
    public SourceOutline {
        symbols = List.copyOf(symbols);
        diagnostics = List.copyOf(diagnostics);
    }
}
