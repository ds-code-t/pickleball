package tools.dscode.store;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import io.cucumber.core.runtime.FeaturePathLoadCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.model.EngineFeature;
import tools.dscode.engine.source.StoreScenarioSource;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.testengine.PKB_props;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Call and component files stay enabled 0 so a feature run does not select
 * them. An explicit calls or component path still loads them. {@code @pkb-ignore}
 * still removes that scenario.
 */
class CallLibraryScanTest {
    private static final String CALLS = "src/test/resources/calls/defs.feature";
    private static final String COMPONENT = "src/test/resources/component/bits.feature";
    private static final String SUITE = "src/test/resources/features/suite.feature";

    @TempDir
    Path project;

    private String previousStore;
    private String previousProject;

    @AfterEach
    void release() throws Exception {
        if (previousStore == null) System.clearProperty("pkb_store");
        else System.setProperty("pkb_store", previousStore);
        if (previousProject == null) System.clearProperty("pkb_store_project");
        else System.setProperty("pkb_store_project", previousProject);
        StoreRuntime.reset();
        FeaturePathLoadCache.clear();
        CucumberScanUtil.clearCache();
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreHandles.assertReleased(project);
    }

    @Test
    void explicitLibraryPathLoadsWhileAFeatureRunSkipsEnabledZero() throws Exception {
        write(project.resolve(CALLS), """
                Feature: Calls
                  Scenario Outline: TokenCall
                    * a call step
                    Examples:
                      | Scenario Tags |
                      | %TokenCall    |

                  @pkb-ignore @hidden-call
                  Scenario: HiddenCall
                    * a hidden step
                """);
        write(project.resolve(COMPONENT), """
                @nested-bit
                Feature: Components
                  Scenario: NestedBit
                    * a component step
                """);
        write(project.resolve(SUITE), """
                @suite-only
                Feature: Suite
                  Scenario: StayOut
                    * a suite step
                """);
        previousStore = System.getProperty("pkb_store");
        previousProject = System.getProperty("pkb_store_project");
        System.setProperty("pkb_store", "true");
        System.setProperty("pkb_store_project", project.toAbsolutePath().normalize().toString());
        StoreRuntime.reset();
        FeaturePathLoadCache.clear();
        CucumberScanUtil.clearCache();
        StoreRuntime.beforeFeatureLoad();

        assertEquals("0", enabled(CALLS));
        assertEquals("0", enabled(COMPONENT));
        assertEquals("1", enabled(SUITE));
        disable(SUITE);
        FeaturePathLoadCache.clear();
        CucumberScanUtil.clearCache();

        assertEquals(List.of("TokenCall"), names(callsDir(), "@%TokenCall"));
        assertEquals(List.of("NestedBit"), names(componentDir(), "@nested-bit"));
        IllegalArgumentException hidden = assertThrows(
                IllegalArgumentException.class,
                () -> names(callsDir(), "@hidden-call"));
        assertTrue(hidden.getMessage().toLowerCase().contains("no scenarios"), hidden.getMessage());
        IllegalArgumentException suite = assertThrows(
                IllegalArgumentException.class,
                () -> names(featuresDir(), "@suite-only"));
        assertTrue(suite.getMessage().toLowerCase().contains("no scenarios"), suite.getMessage());
        IllegalArgumentException notSelected = assertThrows(
                IllegalArgumentException.class,
                () -> names(featuresDir(), "@%TokenCall"));
        assertTrue(notSelected.getMessage().toLowerCase().contains("no scenarios"), notSelected.getMessage());

        List<String> suiteNames = new StoreScenarioSource(project, null).load().stream()
                .map(EngineFeature::name)
                .toList();
        assertTrue(suiteNames.stream().noneMatch(name -> name.equals("Calls") || name.equals("Components") || name.equals("Suite")),
                suiteNames.toString());
    }

    private String callsDir() {
        return project.resolve("src/test/resources/calls").toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private String componentDir() {
        return project.resolve("src/test/resources/component").toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private String featuresDir() {
        return project.resolve("src/test/resources/features").toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private static List<String> names(String features, String tags) {
        List<Pickle> pickles = CucumberScanUtil.listPickles(Map.of(
                PKB_props.PKB_FEATURES, features,
                PKB_props.PKB_TAGS, tags));
        List<String> names = new ArrayList<>();
        for (Pickle pickle : pickles) names.add(pickle.getName());
        return names;
    }

    private String enabled(String path) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            return scalar(database.connection(), "SELECT enabled FROM files WHERE path = ?", path);
        }
    }

    private void disable(String path) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project);
             PreparedStatement file = database.connection().prepareStatement(
                     "UPDATE files SET enabled = 0 WHERE path = ?");
             PreparedStatement scenarios = database.connection().prepareStatement(
                     "UPDATE scenarios SET enabled = 0 WHERE file_path = ?")) {
            file.setString(1, path);
            assertEquals(1, file.executeUpdate());
            scenarios.setString(1, path);
            assertTrue(scenarios.executeUpdate() >= 1);
            if (!database.connection().getAutoCommit()) database.connection().commit();
        }
    }

    private static String scalar(Connection connection, String sql, String arg) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, arg);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
