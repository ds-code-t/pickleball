package tools.dscode.workbench.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.sync.WorkbenchManifest;
import tools.dscode.workbench.sync.WorkbenchSyncMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumerFeatureCatalogTest {
    @TempDir
    Path project;

    @Test
    void discoversFeaturesFromProjectOwnedTestResourcesAndSupportsSelectionSearch() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features.resolve("workflow"));
        Files.writeString(features.resolve("login.feature"), """
                Feature: Sign in
                  Scenario: Valid password
                    Given a user
                  Scenario: Locked account
                    Given a lock
                """);
        Files.writeString(features.resolve("workflow/nested.feature"), """
                Feature: Nested workflow
                  Scenario: IF ELSE branch
                    * IF: true
                    : * Then stay
                """);

        ConsumerFeatureCatalog catalog = ConsumerFeatureCatalog.scan(project, null);
        assertEquals(2, catalog.features().size());
        assertEquals("Nested workflow", catalog.featuresForBrowse().stream()
                .filter(feature -> feature.featureName().equals("Nested workflow"))
                .findFirst()
                .orElseThrow()
                .featureName());

        catalog.setBrowseMode(ConsumerFeatureCatalog.BrowseMode.FILE_PATH);
        assertTrue(catalog.featuresForBrowse().stream()
                .anyMatch(feature -> feature.browseLabel(catalog.browseMode()).contains("workflow/nested.feature")));

        assertEquals(3, catalog.visibleScenarios().size());

        catalog.toggleFeature(features.resolve("login.feature"));
        assertEquals(2, catalog.visibleScenarios().size());
        assertTrue(catalog.visibleScenarios().stream().allMatch(scenario -> scenario.featureName().equals("Sign in")));

        catalog.filter().setNameQuery("locked");
        assertEquals(1, catalog.visibleScenarios().size());
        assertEquals("Locked account", catalog.visibleScenarios().getFirst().name());

        catalog.clearFeatureSelection();
        catalog.filter().setNameQuery("if else");
        assertEquals(1, catalog.visibleScenarios().size());
        assertEquals("IF ELSE branch", catalog.visibleScenarios().getFirst().name());
        assertTrue(catalog.visibleScenarios().getFirst().lines().getFirst().startsWith("Feature:"));
    }

    @Test
    void nameTagAndOptionalFeatureFiltersComposeAndInheritFeatureTags() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("auth.feature"), """
                @feature-auth @shared
                Feature: Sign in

                  @smoke @login
                  Scenario: Valid password
                    Given a user

                  @wip
                  Scenario: Locked account
                    Given a lock

                  @outline
                  Scenario Outline: Search term
                    When search <term>
                    @fast
                    Examples:
                      | term |
                      | a    |
                    @slow @db
                    Examples:
                      | term |
                      | b    |
                """);
        Files.writeString(features.resolve("other.feature"), """
                @other
                Feature: Other

                  Scenario: Unrelated
                    Given stay
                """);

        ConsumerFeatureCatalog catalog = ConsumerFeatureCatalog.scan(project, null);
        assertEquals(4, catalog.candidateScenarios().size());
        assertEquals(4, catalog.visibleScenarios().size());

        ConsumerFeatureCatalog.ScenarioEntry valid = named(catalog, "Valid password");
        assertEquals(List.of("feature-auth", "shared", "smoke", "login"), valid.effectiveTags());
        assertEquals(List.of("smoke", "login"), valid.tags());

        ConsumerFeatureCatalog.ScenarioEntry locked = named(catalog, "Locked account");
        assertTrue(locked.effectiveTags().contains("feature-auth"));
        assertTrue(locked.effectiveTags().contains("wip"));

        ConsumerFeatureCatalog.ScenarioEntry search = named(catalog, "Search term");
        assertTrue(search.effectiveTags().containsAll(List.of("feature-auth", "shared", "outline", "fast", "slow", "db")));

        catalog.filter().setNameMatchMode(ScenarioFilter.NameMatchMode.STARTS_WITH);
        catalog.filter().setNameQuery("valid");
        assertEquals(List.of("Valid password"), names(catalog));

        catalog.filter().setNameMatchMode(ScenarioFilter.NameMatchMode.ENDS_WITH);
        catalog.filter().setNameQuery("account");
        assertEquals(List.of("Locked account"), names(catalog));

        catalog.filter().setNameMatchMode(ScenarioFilter.NameMatchMode.FULL_MATCH);
        catalog.filter().setNameQuery("search term");
        assertEquals(List.of("Search term"), names(catalog));

        catalog.filter().setNameMatchMode(ScenarioFilter.NameMatchMode.CONTAINS);
        catalog.filter().setNameQuery("");
        catalog.filter().setIncludeTagsQuery("@smoke @login");
        assertEquals(List.of("Valid password"), names(catalog));

        catalog.filter().setIncludeTagsQuery("");
        catalog.filter().setExcludeTagsQuery("wip");
        assertEquals(List.of("Unrelated", "Search term", "Valid password"), names(catalog));

        catalog.filter().setIncludeTagsQuery("shared");
        catalog.filter().setExcludeTagsQuery("@wip");
        assertEquals(List.of("Search term", "Valid password"), names(catalog));

        catalog.filter().setIncludeTagsQuery("@slow");
        catalog.filter().setExcludeTagsQuery("");
        assertEquals(List.of("Search term"), names(catalog));

        catalog.filter().setIncludeTagsQuery("");
        catalog.filter().setNameQuery("lock");
        assertEquals(List.of("Locked account"), names(catalog));

        catalog.clearFeatureSelection();
        catalog.filter().setNameQuery("");
        catalog.toggleFeature(features.resolve("other.feature"));
        assertEquals(List.of("Unrelated"), names(catalog));
        assertEquals(1, catalog.candidateScenarios().size());

        catalog.clearFeatureSelection();
        assertEquals(4, catalog.candidateScenarios().size());
        assertEquals(4, catalog.visibleScenarios().size());
    }

    @Test
    void ruleTagsAreInheritedWithFeatureTags() throws Exception {
        Path features = project.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("rules.feature"), """
                @feature-tag
                Feature: Rules

                  @rule-a
                  Rule: First

                    @own
                    Scenario: Inside rule
                      Given stay
                """);

        ConsumerFeatureCatalog catalog = ConsumerFeatureCatalog.scan(project, null);
        ConsumerFeatureCatalog.ScenarioEntry inside = named(catalog, "Inside rule");
        assertEquals(List.of("own"), inside.tags());
        assertTrue(inside.effectiveTags().containsAll(List.of("feature-tag", "rule-a", "own")));
        catalog.filter().setIncludeTagsQuery("feature-tag rule-a own");
        assertEquals(List.of("Inside rule"), names(catalog));
    }

    private static ConsumerFeatureCatalog.ScenarioEntry named(ConsumerFeatureCatalog catalog, String name) {
        return catalog.visibleScenarios().stream()
                .filter(scenario -> scenario.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static List<String> names(ConsumerFeatureCatalog catalog) {
        return catalog.visibleScenarios().stream()
                .map(ConsumerFeatureCatalog.ScenarioEntry::name)
                .toList();
    }

    @Test
    void prefersClasspathFeaturesConfigurationWithoutCrawlingUnrelatedDirectories() throws Exception {
        Files.createDirectories(project.resolve("src/test/resources/features"));
        Files.writeString(project.resolve("src/test/resources/features/configured.feature"), """
                Feature: Configured
                  Scenario: Only this
                    Given stay in project features
                """);
        Files.createDirectories(project.resolve("unrelated/features"));
        Files.writeString(project.resolve("unrelated/features/outside.feature"), """
                Feature: Outside
                  Scenario: Must not appear
                    Given ignored
                """);
        Files.createDirectories(project.resolve("src/test/resources"));
        Files.writeString(project.resolve("src/test/resources/pickleball.properties"),
                "pkb_features=classpath:features\n");

        WorkbenchManifest manifest = new WorkbenchManifest(
                WorkbenchManifest.CURRENT_SCHEMA,
                project.toString(),
                "MAVEN",
                "mvn",
                List.of(project.resolve("src/test/resources").toString()),
                List.of(),
                List.of(),
                project.resolve("live").toString(),
                "2026-01-01T00:00:00Z",
                "fp",
                List.of(),
                "2.1.9",
                "21",
                "/usr/lib/jvm",
                WorkbenchSyncMode.FULL.name(),
                "java-fp",
                "resource-fp",
                "build-fp",
                "dep-fp"
        );

        ConsumerFeatureCatalog catalog = ConsumerFeatureCatalog.scan(project, manifest);
        assertEquals(1, catalog.features().size());
        assertEquals("Configured", catalog.features().getFirst().featureName());
        assertEquals("Only this", catalog.visibleScenarios().getFirst().name());
    }
}
