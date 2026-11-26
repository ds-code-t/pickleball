package tools.dscode.common.exceptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.coredefinitions.MetaSteps;
import tools.dscode.store.ExceptionCluster;
import tools.dscode.store.StoreExecution;
import tools.dscode.store.StorePaths;
import tools.dscode.store.StoreScope;
import tools.dscode.store.sql.SqlConnections;
import tools.dscode.store.sql.SqliteOpen;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicit scope is kept. A default failure is environmental only when the run records an environment. */
class PickleballExceptionTest {
    @TempDir
    Path project;

    private final Map<String, String> previous = new LinkedHashMap<>();

    @BeforeEach
    void isolateScope() {
        System.clearProperty("pkb_store_sandbox");
        StoreScope.clear();
        ExceptionCluster.clear();
    }

    @AfterEach
    void release() {
        restore();
        System.clearProperty("pkb_store_sandbox");
        System.clearProperty("pkb_environment");
        StoreScope.clear();
        SqlConnections.closeProject(project);
        StoreExecution.finishScenario("PASSED");
        StoreScope.clear();
        ExceptionCluster.clear();
    }

    @Test
    void explicitScopeStaysUniversalWhenEnvironmentIsSet() throws Exception {
        track("scope-universal");
        set("pkb_environment", "night");
        StoreExecution.beforeStep(project, "Boom", "memory.feature", 2, "click save", 3);
        StoreExecution.noteFailure(new PickleballException("boom", "error", "universal"));
        StoreExecution.finishScenario("FAILED");
        assertEquals("universal", attribute("scope-universal", "exception.scope"));
        assertEquals("error", attribute("scope-universal", "exception.severity"));
    }

    @Test
    void markEnvironmentMakesADefaultFailureEnvironmental() throws Exception {
        track("scope-env");
        System.clearProperty("pkb_environment");
        StoreExecution.beforeStep(project, "Later", "memory.feature", 4, "click save", 5);
        MetaSteps.markEnvironment("off-hours");
        StatusException later = new StatusException("later");
        StoreExecution.noteFailure(later);
        StoreExecution.finishScenario("FAILED");
        assertEquals("environmental", attribute("scope-env", "exception.scope"));
        assertEquals("off-hours", attribute("scope-env", "environment"));
        assertEquals(ExceptionCluster.signature(later), clusterSignature("environmental"));
    }

    private void track(String runId) {
        set("pkb_store", "true");
        set("pkb_store_track", "true");
        set("pkb_run_id", runId);
        set("pkb_store_project", project.toAbsolutePath().normalize().toString());
    }

    private String attribute(String runId, String name) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT value FROM run_attributes WHERE run_id = ? AND name = ?")) {
            statement.setString(1, runId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), name);
                return rows.getString(1);
            }
        }
    }

    private String clusterSignature(String scope) throws Exception {
        try (Connection connection = SqliteOpen.open(StorePaths.runState(project));
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT signature FROM exception_clusters WHERE scope = ?")) {
            statement.setString(1, scope);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next(), scope);
                return rows.getString(1);
            }
        }
    }

    private void set(String key, String value) {
        previous.putIfAbsent(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    private void restore() {
        for (Map.Entry<String, String> entry : previous.entrySet()) {
            if (entry.getValue() == null) System.clearProperty(entry.getKey());
            else System.setProperty(entry.getKey(), entry.getValue());
        }
        previous.clear();
    }
}
