package tools.dscode.studio.language;

import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialectProvider;
import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Examples;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.Location;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.Scenario;
import io.cucumber.messages.types.Step;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@SuppressWarnings("deprecation")
final class GherkinSourceParser {

    SourceOutline parse(String path, String content) {
        GherkinParser parser = GherkinParser.builder()
                .includeSource(false)
                .includeGherkinDocument(true)
                .includePickles(false)
                .build();

        List<Envelope> envelopes = parser
                .parse(path, content.getBytes(StandardCharsets.UTF_8))
                .toList();

        List<SourceDiagnostic> diagnostics = envelopes.stream()
                .flatMap(envelope -> envelope.getParseError().stream())
                .map(this::diagnostic)
                .sorted(Comparator
                        .comparing((SourceDiagnostic diagnostic) -> diagnostic.line() == null
                                ? Integer.MAX_VALUE
                                : diagnostic.line())
                        .thenComparing(diagnostic -> diagnostic.column() == null
                                ? Integer.MAX_VALUE
                                : diagnostic.column()))
                .toList();

        List<SourceSymbol> symbols = new ArrayList<>();
        envelopes.stream()
                .flatMap(envelope -> envelope.getGherkinDocument().stream())
                .findFirst()
                .flatMap(GherkinDocument::getFeature)
                .ifPresent(feature -> collectFeature(path, feature, symbols));

        symbols.sort(Comparator
                .comparingInt((SourceSymbol symbol) -> symbol.location().line())
                .thenComparingInt(symbol -> symbol.location().column())
                .thenComparing(SourceSymbol::qualifiedName));

        return new SourceOutline(path, SourceLanguage.GHERKIN, symbols, diagnostics);
    }

    private void collectFeature(
            String path,
            Feature feature,
            List<SourceSymbol> symbols
    ) {
        String featureName = displayName(feature.getName(), feature.getKeyword());
        symbols.add(symbol(
                path,
                SourceSymbolKind.GHERKIN_FEATURE,
                featureName,
                featureName,
                null,
                feature.getLocation()
        ));

        GherkinDialect dialect = new GherkinDialectProvider(feature.getLanguage()).getDefaultDialect();

        feature.getChildren().forEach(child -> {
            child.getBackground().ifPresent(background ->
                    collectBackground(path, featureName, background, symbols));
            child.getScenario().ifPresent(scenario ->
                    collectScenario(path, featureName, scenario, dialect, symbols));
            child.getRule().ifPresent(rule ->
                    collectRule(path, featureName, rule, dialect, symbols));
        });
    }

    private void collectRule(
            String path,
            String featureName,
            Rule rule,
            GherkinDialect dialect,
            List<SourceSymbol> symbols
    ) {
        String ruleName = displayName(rule.getName(), rule.getKeyword());
        String qualifiedName = featureName + " / " + ruleName;
        symbols.add(symbol(
                path,
                SourceSymbolKind.GHERKIN_RULE,
                ruleName,
                qualifiedName,
                featureName,
                rule.getLocation()
        ));

        rule.getChildren().forEach(child -> {
            child.getBackground().ifPresent(background ->
                    collectBackground(path, qualifiedName, background, symbols));
            child.getScenario().ifPresent(scenario ->
                    collectScenario(path, qualifiedName, scenario, dialect, symbols));
        });
    }

    private void collectBackground(
            String path,
            String container,
            Background background,
            List<SourceSymbol> symbols
    ) {
        String name = displayName(background.getName(), background.getKeyword());
        String qualifiedName = container + " / " + name;
        symbols.add(symbol(
                path,
                SourceSymbolKind.GHERKIN_BACKGROUND,
                name,
                qualifiedName,
                container,
                background.getLocation()
        ));
        collectSteps(path, qualifiedName, background.getSteps(), symbols);
    }

    private void collectScenario(
            String path,
            String container,
            Scenario scenario,
            GherkinDialect dialect,
            List<SourceSymbol> symbols
    ) {
        String name = displayName(scenario.getName(), scenario.getKeyword());
        String qualifiedName = container + " / " + name;
        boolean outline = dialect.getScenarioOutlineKeywords().stream()
                .map(String::trim)
                .anyMatch(keyword -> keyword.equals(scenario.getKeyword().trim()));

        symbols.add(symbol(
                path,
                outline
                        ? SourceSymbolKind.GHERKIN_SCENARIO_OUTLINE
                        : SourceSymbolKind.GHERKIN_SCENARIO,
                name,
                qualifiedName,
                container,
                scenario.getLocation()
        ));

        collectSteps(path, qualifiedName, scenario.getSteps(), symbols);

        for (Examples examples : scenario.getExamples()) {
            String examplesName = displayName(examples.getName(), examples.getKeyword());
            symbols.add(symbol(
                    path,
                    SourceSymbolKind.GHERKIN_EXAMPLES,
                    examplesName,
                    qualifiedName + " / " + examplesName,
                    qualifiedName,
                    examples.getLocation()
            ));
        }
    }

    private void collectSteps(
            String path,
            String container,
            List<Step> steps,
            List<SourceSymbol> symbols
    ) {
        for (Step step : steps) {
            String keyword = step.getKeyword().trim();
            String qualifiedName = container + " / " + keyword + " " + step.getText();
            symbols.add(symbol(
                    path,
                    SourceSymbolKind.GHERKIN_STEP,
                    step.getText(),
                    qualifiedName,
                    container,
                    step.getLocation()
            ));
        }
    }

    private static String displayName(String name, String keyword) {
        return name == null || name.isBlank() ? keyword.trim() : name;
    }

    private static SourceSymbol symbol(
            String path,
            SourceSymbolKind kind,
            String name,
            String qualifiedName,
            String container,
            Location location
    ) {
        return new SourceSymbol(
                SourceLanguage.GHERKIN,
                kind,
                name,
                qualifiedName,
                container,
                new SourceLocation(
                        path,
                        Math.toIntExact(location.getLine()),
                        Math.toIntExact(location.getColumn().orElse(1L))
                )
        );
    }

    private SourceDiagnostic diagnostic(ParseError error) {
        Location location = error.getSource().getLocation().orElse(null);
        return new SourceDiagnostic(
                "ERROR",
                error.getMessage(),
                location == null ? null : Math.toIntExact(location.getLine()),
                location == null
                        ? null
                        : location.getColumn().map(Math::toIntExact).orElse(null)
        );
    }
}
