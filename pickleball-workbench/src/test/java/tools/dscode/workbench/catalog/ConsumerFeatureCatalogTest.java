package tools.dscode.workbench.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.sync.WorkbenchManifest;

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

        catalog.setScenarioQuery("locked");
        assertEquals(1, catalog.visibleScenarios().size());
        assertEquals("Locked account", catalog.visibleScenarios().getFirst().name());

        catalog.clearFeatureSelection();
        catalog.setScenarioQuery("if else");
        assertEquals(1, catalog.visibleScenarios().size());
        assertEquals("IF ELSE branch", catalog.visibleScenarios().getFirst().name());
        assertTrue(catalog.visibleScenarios().getFirst().lines().getFirst().startsWith("Feature:"));
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
                "/usr/lib/jvm"
        );

        ConsumerFeatureCatalog catalog = ConsumerFeatureCatalog.scan(project, manifest);
        assertEquals(1, catalog.features().size());
        assertEquals("Configured", catalog.features().getFirst().featureName());
        assertEquals("Only this", catalog.visibleScenarios().getFirst().name());
    }
}
