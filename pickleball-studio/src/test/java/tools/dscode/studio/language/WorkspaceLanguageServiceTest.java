package tools.dscode.studio.language;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.studio.workspace.WorkspaceFileService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceLanguageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void outlinesJavaDefinitionsAndFindsSymbols() throws Exception {
        Files.createDirectories(tempDir.resolve("src/main/java/example"));
        Files.writeString(tempDir.resolve("src/main/java/example/Sample.java"), """
                package example;

                public class Sample {
                    private int value;

                    public Sample() {
                    }

                    public String greet(String name) {
                        return "hello " + name;
                    }

                    interface Nested {
                    }
                }
                """);

        WorkspaceLanguageService language = service();
        SourceOutline outline = language.outline("src/main/java/example/Sample.java");

        assertEquals(SourceLanguage.JAVA, outline.language());
        assertTrue(outline.diagnostics().isEmpty(), outline.diagnostics().toString());
        assertTrue(has(outline, SourceSymbolKind.JAVA_CLASS, "example.Sample"));
        assertTrue(has(outline, SourceSymbolKind.JAVA_FIELD, "example.Sample#value"));
        assertTrue(has(outline, SourceSymbolKind.JAVA_CONSTRUCTOR, "example.Sample#Sample()"));
        assertTrue(has(outline, SourceSymbolKind.JAVA_METHOD, "example.Sample#greet(String)"));
        assertTrue(has(outline, SourceSymbolKind.JAVA_INTERFACE, "example.Sample.Nested"));

        List<SourceSymbol> search = language.searchSymbols("greet", "JAVA", null, 10);
        assertEquals(List.of("example.Sample#greet(String)"), qualifiedNames(search));

        List<SourceSymbol> definitions = language.findDefinitions(
                "example.Sample",
                "java",
                List.of("java_class"),
                10
        );
        assertEquals(List.of("example.Sample"), qualifiedNames(definitions));
    }

    @Test
    void outlinesGherkinDefinitionsUsingCurrentCucumberGrammar() throws Exception {
        Files.createDirectories(tempDir.resolve("features"));
        Files.writeString(tempDir.resolve("features/navigation.feature"), """
                Feature: Navigation
                  Background: Open site
                    Given the site is open

                  Rule: Signed in users
                    Scenario Outline: Open page
                      When I open <page>
                      Then the page is visible

                      Examples: Pages
                        | page |
                        | home |

                    Scenario: Sign out
                      When I sign out
                """);

        WorkspaceLanguageService language = service();
        SourceOutline outline = language.outline("features/navigation.feature");

        assertEquals(SourceLanguage.GHERKIN, outline.language());
        assertTrue(outline.diagnostics().isEmpty(), outline.diagnostics().toString());
        assertTrue(has(outline, SourceSymbolKind.GHERKIN_FEATURE, "Navigation"));
        assertTrue(has(outline, SourceSymbolKind.GHERKIN_BACKGROUND, "Navigation / Open site"));
        assertTrue(has(outline, SourceSymbolKind.GHERKIN_RULE, "Navigation / Signed in users"));
        assertTrue(has(
                outline,
                SourceSymbolKind.GHERKIN_SCENARIO_OUTLINE,
                "Navigation / Signed in users / Open page"
        ));
        assertTrue(has(
                outline,
                SourceSymbolKind.GHERKIN_EXAMPLES,
                "Navigation / Signed in users / Open page / Pages"
        ));
        assertTrue(has(
                outline,
                SourceSymbolKind.GHERKIN_SCENARIO,
                "Navigation / Signed in users / Sign out"
        ));

        List<SourceSymbol> search = language.searchSymbols(
                "open page",
                "GHERKIN",
                List.of("GHERKIN_SCENARIO_OUTLINE"),
                10
        );
        assertEquals(
                List.of("Navigation / Signed in users / Open page"),
                qualifiedNames(search)
        );

        List<SourceSymbol> definitions = language.findDefinitions("Sign out", null, null, 10);
        assertEquals(
                List.of("Navigation / Signed in users / Sign out"),
                qualifiedNames(definitions)
        );
    }

    @Test
    void reportsSyntaxDiagnosticsWithoutCompilingOrExecutingSources() throws Exception {
        Files.writeString(tempDir.resolve("Broken.java"), "class Broken { void run( }\n");
        Files.writeString(tempDir.resolve("broken.feature"), "Scenario: Missing feature\n");

        WorkspaceLanguageService language = service();

        assertFalse(language.outline("Broken.java").diagnostics().isEmpty());
        assertFalse(language.outline("broken.feature").diagnostics().isEmpty());
    }

    @Test
    void symbolSearchSkipsGeneratedBuildDirectories() throws Exception {
        Files.writeString(tempDir.resolve("Visible.java"), "class VisibleSymbol {}\n");
        Files.createDirectories(tempDir.resolve("build/generated"));
        Files.writeString(tempDir.resolve("build/generated/Hidden.java"), "class HiddenSymbol {}\n");

        WorkspaceLanguageService language = service();

        assertEquals(1, language.searchSymbols("VisibleSymbol", null, null, 10).size());
        assertTrue(language.searchSymbols("HiddenSymbol", null, null, 10).isEmpty());
    }

    private WorkspaceLanguageService service() {
        return new WorkspaceLanguageService(new WorkspaceFileService(tempDir));
    }

    private static boolean has(SourceOutline outline, SourceSymbolKind kind, String qualifiedName) {
        return outline.symbols().stream()
                .anyMatch(symbol -> symbol.kind() == kind && qualifiedName.equals(symbol.qualifiedName()));
    }

    private static List<String> qualifiedNames(List<SourceSymbol> symbols) {
        return symbols.stream().map(SourceSymbol::qualifiedName).toList();
    }
}
