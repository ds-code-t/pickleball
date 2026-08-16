package tools.dscode.studio.language;

import tools.dscode.studio.workspace.WorkspaceFileService;
import tools.dscode.studio.workspace.WorkspaceTextFile;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class WorkspaceLanguageService {
    public static final int DEFAULT_MAX_RESULTS = 100;
    public static final int MAX_RESULTS = 500;
    private final WorkspaceFileService files;
    private final JavaSourceParser javaParser = new JavaSourceParser();
    private final GherkinSourceParser gherkinParser = new GherkinSourceParser();

    public WorkspaceLanguageService(WorkspaceFileService files) {
        this.files = files;
    }

    public SourceOutline outline(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Source path must not be blank");
        }

        WorkspaceTextFile file = files.readText(path);
        if (file.path().endsWith(".java")) {
            return javaParser.parse(file.path(), file.content());
        }
        if (file.path().endsWith(".feature")) {
            return gherkinParser.parse(file.path(), file.content());
        }

        throw new IllegalArgumentException(
                "Source outline supports only .java and .feature files: " + file.path()
        );
    }

    public List<SourceSymbol> searchSymbols(
            String query,
            String language,
            List<String> kinds,
            Integer requestedMaxResults
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Symbol search query must not be blank");
        }

        SourceLanguage selectedLanguage = parseLanguage(language);
        Set<SourceSymbolKind> selectedKinds = parseKinds(kinds);
        int maxResults = maxResults(requestedMaxResults);
        String expected = query.toLowerCase(Locale.ROOT);

        return scan(selectedLanguage, selectedKinds, maxResults, symbol ->
                contains(symbol.name(), expected)
                        || contains(symbol.qualifiedName(), expected)
                        || contains(symbol.container(), expected));
    }

    public List<SourceSymbol> findDefinitions(
            String name,
            String language,
            List<String> kinds,
            Integer requestedMaxResults
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Definition name must not be blank");
        }

        SourceLanguage selectedLanguage = parseLanguage(language);
        Set<SourceSymbolKind> selectedKinds = parseKinds(kinds);
        int maxResults = maxResults(requestedMaxResults);

        return scan(selectedLanguage, selectedKinds, maxResults, symbol ->
                name.equals(symbol.name()) || name.equals(symbol.qualifiedName()));
    }

    private List<SourceSymbol> scan(
            SourceLanguage language,
            Set<SourceSymbolKind> kinds,
            int maxResults,
            SymbolPredicate predicate
    ) {
        List<String> suffixes = switch (language) {
            case JAVA -> List.of(".java");
            case GHERKIN -> List.of(".feature");
            case null -> List.of(".java", ".feature");
        };

        List<String> sourceFiles = files.findFilesBySuffix(".", suffixes, Integer.MAX_VALUE);
        List<SourceSymbol> matches = new ArrayList<>();

        for (String path : sourceFiles) {
            SourceOutline outline = outline(path);
            for (SourceSymbol symbol : outline.symbols()) {
                if ((kinds.isEmpty() || kinds.contains(symbol.kind())) && predicate.test(symbol)) {
                    matches.add(symbol);
                    if (matches.size() >= maxResults) {
                        return List.copyOf(matches);
                    }
                }
            }
        }

        return List.copyOf(matches);
    }

    private static boolean contains(String value, String expectedLowercase) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(expectedLowercase);
    }

    private static SourceLanguage parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SourceLanguage.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Unsupported source language: " + value + ". Expected JAVA or GHERKIN."
            );
        }
    }

    private static Set<SourceSymbolKind> parseKinds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }

        EnumSet<SourceSymbolKind> kinds = EnumSet.noneOf(SourceSymbolKind.class);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Source symbol kinds must not contain blank values");
            }
            try {
                kinds.add(SourceSymbolKind.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Unsupported source symbol kind: " + value);
            }
        }
        return Set.copyOf(kinds);
    }

    private static int maxResults(Integer requested) {
        int value = requested == null ? DEFAULT_MAX_RESULTS : requested;
        if (value < 1 || value > MAX_RESULTS) {
            throw new IllegalArgumentException(
                    "Symbol maxResults must be between 1 and " + MAX_RESULTS
            );
        }
        return value;
    }

    @FunctionalInterface
    private interface SymbolPredicate {
        boolean test(SourceSymbol symbol);
    }
}
