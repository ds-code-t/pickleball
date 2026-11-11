package io.cucumber.core.runtime;

import io.cucumber.core.gherkin.Feature;
import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.gherkin.Step;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dalton.DaltonConfig;
import tools.dscode.dalton.DaltonDatabase;
import tools.dscode.dalton.DaltonFeatureSource;
import tools.dscode.dalton.DaltonFiles;
import tools.dscode.dalton.DaltonHashes;
import tools.dscode.dalton.DaltonPaths;
import tools.dscode.dalton.DaltonRuntime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonExecuteFromDbTest {
    @TempDir
    Path project;

    @Test
    void enabledLoadUsesDatabaseBytesAndDisabledLoadStaysOnTheFile() throws Exception {
        Path repo = Path.of("").toAbsolutePath().normalize();
        Path feature = project.resolve("features/navigation.feature");
        Files.createDirectories(feature.getParent());
        Files.copy(repo.resolve("maven-consumer-project/src/test/resources/features/navigation.feature"), feature);
        Files.writeString(project.resolve("features/outline.feature"), """
                Feature: Pay
                  Scenario Outline: Pay <name>
                    * amount is <amount>
                    Examples:
                      | name | amount |
                      | Ada  | 10     |
                      | Lin  | 4      |
                """);
        String previous = System.getProperty("pkb_dalton");
        String previousProject = System.getProperty("pkb_dalton_project");
        try {
            System.setProperty("pkb_dalton", "false");
            System.setProperty("pkb_dalton_project", project.toString());
            DaltonRuntime.reset();
            FeaturePathLoadCache.clear();
            assertNull(DaltonFeatureSource.cacheSuffix());
            assertNull(DaltonFeatureSource.bytesFor(feature.toUri()));
            List<String> fromFile = fingerprint(load());
            int parses = FeaturePathLoadCache.parseCount();
            assertEquals(fromFile, fingerprint(load()));
            assertEquals(parses, FeaturePathLoadCache.parseCount());
            assertFalse(Files.exists(DaltonPaths.database(project)));
            assertTrue(fromFile.stream().anyMatch(row -> row.startsWith("Open a playground from the dashboard")));
            assertTrue(fromFile.stream().anyMatch(row -> row.startsWith("Pay Ada") || row.startsWith("Pay <name>")));

            System.setProperty("pkb_dalton", "true");
            DaltonRuntime.reset();
            FeaturePathLoadCache.clear();
            assertEquals(fromFile, fingerprint(load()));
            assertTrue(DaltonConfig.enabled(project));
            try (DaltonDatabase database = DaltonDatabase.open(project)) {
                DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/navigation.feature");
                assertArrayEquals(Files.readAllBytes(feature), row.raw());
                assertTrue(count(database, "SELECT COUNT(*) FROM scenarios") >= 2);
            }

            String rewritten = Files.readString(feature).replace(
                    "Scenario: Open a playground from the dashboard and return home",
                    "Scenario: Open a playground from the database");
            byte[] raw = rewritten.getBytes(StandardCharsets.UTF_8);
            try (DaltonDatabase database = DaltonDatabase.open(project)) {
                DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/navigation.feature");
                DaltonFiles.upsert(database.connection(), new DaltonFiles.Row(
                        row.path(),
                        DaltonHashes.sha256(raw),
                        raw,
                        row.mtime(),
                        row.sourceType(),
                        row.lastHydratedAt(),
                        row.lastSyncedHash(),
                        true
                ));
            }
            List<String> fromDb = fingerprint(load());
            assertTrue(fromDb.stream().anyMatch(row -> row.startsWith("Open a playground from the database")), fromDb.toString());
            assertFalse(fromDb.stream().anyMatch(row -> row.startsWith("Open a playground from the dashboard")), fromDb.toString());
            assertTrue(Files.readString(feature).contains("Open a playground from the dashboard"));

            System.setProperty("pkb_dalton", "false");
            DaltonRuntime.reset();
            FeaturePathLoadCache.clear();
            assertEquals(fromFile, fingerprint(load()));
            assertNull(DaltonFeatureSource.bytesFor(feature.toUri()));
        } finally {
            if (previous == null) System.clearProperty("pkb_dalton");
            else System.setProperty("pkb_dalton", previous);
            if (previousProject == null) System.clearProperty("pkb_dalton_project");
            else System.setProperty("pkb_dalton_project", previousProject);
            DaltonRuntime.reset();
            FeaturePathLoadCache.clear();
        }
    }

    private List<Feature> load() {
        return FeaturePathLoadCache.load(
                List.of(project.resolve("features").toUri()),
                () -> DaltonExecuteFromDbTest.class.getClassLoader(),
                List::of
        );
    }

    private static List<String> fingerprint(List<Feature> features) {
        List<String> rows = new ArrayList<>();
        for (Feature feature : features) {
            for (Pickle pickle : feature.getPickles()) {
                StringBuilder row = new StringBuilder(pickle.getName());
                for (Step step : pickle.getSteps()) {
                    row.append('\n').append(step.getText());
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }

    private static int count(DaltonDatabase database, String sql) throws Exception {
        try (var statement = database.connection().createStatement();
             var rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }
}
