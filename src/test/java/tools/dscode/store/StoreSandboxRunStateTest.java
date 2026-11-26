package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlDialect;
import tools.dscode.store.sql.SqlStore;
import tools.dscode.store.sql.SqliteOpen;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sandbox run records its name on main and does not write run-state on the branch. */
class StoreSandboxRunStateTest {
    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        StoreScope.clear();
        StoreScope.bind(Binding.sandbox("alpha"));
        StoreRun.stop(project);
        StoreScope.clear();
        StoreRun.stop(project);
        System.clearProperty("pkb_store_run_classpath");
        StoreTestProcesses.release(project);
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void sandboxRunWritesTheColumnOnMainOnly() throws Exception {
        Path features = project.resolve("features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("pay.feature"), """
                Feature: Pay
                  @store-run-state
                  Scenario: Ada
                    * INFO: amount is 1
                    * ASSERT: "1" equals "1"
                """);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "alpha", "--mode=copy", "--project=" + project).code);
        StoreScope.clear();
        Path worker = StorePaths.worker(project, "alpha");
        Files.createDirectories(worker);
        Files.writeString(worker.resolve(StoreRun.FINGERPRINT), StoreRun.glueFingerprint(project));
        System.setProperty("pkb_store_run_classpath", System.getProperty("java.class.path"));
        String featuresDir = features.toAbsolutePath().normalize().toString();
        Capture ran = cli(
                "store", "run", "--sandbox=alpha",
                "--tags=@store-run-state",
                "--glue=tools.dscode.coredefinitions",
                "-Dpkb_store_track=true",
                "-Dpkb_features=wd-file:" + featuresDir,
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false",
                "--project=" + project);
        assertEquals(0, ran.code, ran.out + ran.err);
        assertTrue(ran.out.contains("scenario PASSED Ada"), ran.out);
        StoreScope.clear();
        String step;
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            step = store.session().scalar(
                    "SELECT step_exec_id FROM step_executions WHERE sandbox = 'alpha' ORDER BY step_ord LIMIT 1");
        }
        assertNotNull(step, "main has no sandbox=alpha step");
        Path report = project.resolve("reports/alpha");
        Files.createDirectories(report);
        Files.writeString(report.resolve("run-index.json"), """
                {"runId":"alpha-shot","startedAt":"2026-11-15T00:00:00Z","outcome":"passed","scenarios":[{"name":"Ada"}],"counts":{"passed":1,"failed":0}}
                """);
        Files.write(report.resolve("shot.png"), new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        Capture noted = cli(
                "store", "history", "record", "--from=reports/alpha",
                "--message=noted", "--link=Customer#ada",
                "--sandbox=alpha", "--project=" + project);
        assertEquals(0, noted.code, noted.out + noted.err);
        Capture usage = cli(
                "store", "entity", "use", "Customer", "ada",
                "--step=" + step, "--operation=read",
                "--sandbox=alpha", "--project=" + project);
        assertEquals(0, usage.code, usage.out + usage.err);
        StoreScope.clear();
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            for (String table : SqlDialect.RUN_STATE_TABLES) {
                if ("sandboxes".equals(table)) {
                    assertTrue(java.nio.file.Files.isRegularFile(StorePaths.sandboxDatabase(project, "alpha")), table);
                    assertEquals("0", store.session().scalar("SELECT COUNT(*) FROM sandboxes WHERE name = 'alpha'"),
                            "no central sandbox registry");
                    continue;
                }
                if ("run_snapshots".equals(table) || "run_snapshot_entities".equals(table)
                        || "run_attributes".equals(table) || "snap_find_index".equals(table)
                        || "delta_archive".equals(table)) {
                    continue;
                }
                String count = store.session().scalar("SELECT COUNT(*) FROM " + table + " WHERE sandbox = 'alpha'");
                assertNotEquals("0", count, table);
            }
        }
        try (var connection = SqliteOpen.open(StorePaths.sandboxDatabase(project, "alpha"));
             Statement statement = connection.createStatement()) {
            for (String table : SqlDialect.RUN_STATE_TABLES) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    assertTrue(rows.next(), table);
                    assertEquals(0, rows.getInt(1), table);
                } catch (SQLException missing) {
                    String message = missing.getMessage() == null ? "" : missing.getMessage().toLowerCase();
                    assertTrue(message.contains(table) || message.contains("no such table"), missing.getMessage());
                }
            }
        }
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
