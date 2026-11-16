package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.Binding;
import tools.dscode.dolt.store.DoltDialect;
import tools.dscode.dolt.store.DoltStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sandbox run records its name on main and does not write run-state on the branch. */
class DoltSandboxRunStateTest {
    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        DoltScope.clear();
        DoltScope.bind(Binding.sandbox("alpha"));
        DoltRun.stop(project);
        DoltScope.clear();
        DoltRun.stop(project);
        System.clearProperty("pkb_dolt_run_classpath");
        DoltTestProcesses.release(project);
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void sandboxRunWritesTheColumnOnMainOnly() throws Exception {
        Path features = project.resolve("features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("pay.feature"), """
                Feature: Pay
                  @dolt-run-state
                  Scenario: Ada
                    * INFO: amount is 1
                    * ASSERT: "1" equals "1"
                """);
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "alpha", "--project=" + project).code);
        DoltScope.clear();
        Path worker = DoltPaths.worker(project, "alpha");
        Files.createDirectories(worker);
        Files.writeString(worker.resolve(DoltRun.FINGERPRINT), DoltRun.glueFingerprint(project));
        System.setProperty("pkb_dolt_run_classpath", System.getProperty("java.class.path"));
        String featuresDir = features.toAbsolutePath().normalize().toString();
        Capture ran = cli(
                "dolt", "run", "--sandbox=alpha",
                "--tags=@dolt-run-state",
                "--glue=tools.dscode.coredefinitions",
                "-Dpkb_dolt_track=true",
                "-Dpkb_features=wd-file:" + featuresDir,
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false",
                "--project=" + project);
        assertEquals(0, ran.code, ran.out + ran.err);
        assertTrue(ran.out.contains("scenario PASSED Ada"), ran.out);
        DoltScope.clear();
        String step;
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
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
                "dolt", "history", "record", "--from=reports/alpha",
                "--message=noted", "--link=Customer#ada",
                "--sandbox=alpha", "--project=" + project);
        assertEquals(0, noted.code, noted.out + noted.err);
        Capture usage = cli(
                "dolt", "entity", "use", "Customer", "ada",
                "--step=" + step, "--operation=read",
                "--sandbox=alpha", "--project=" + project);
        assertEquals(0, usage.code, usage.out + usage.err);
        DoltScope.clear();
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            for (String table : DoltDialect.RUN_STATE_TABLES) {
                String count = "sandboxes".equals(table)
                        ? store.session().scalar("SELECT COUNT(*) FROM sandboxes WHERE name = 'alpha'")
                        : store.session().scalar("SELECT COUNT(*) FROM " + table + " WHERE sandbox = 'alpha'");
                assertNotEquals("0", count, table);
            }
        }
        try (DoltStore store = DoltStore.open(project, Binding.sandbox("alpha"))) {
            for (String table : DoltDialect.RUN_STATE_TABLES) {
                try {
                    String count = store.session().scalar("SELECT COUNT(*) FROM " + table);
                    assertEquals("0", count, table);
                } catch (SQLException missing) {
                    String message = missing.getMessage() == null ? "" : missing.getMessage().toLowerCase();
                    assertTrue(message.contains(table) || message.contains("not found") || message.contains("exist"),
                            missing.getMessage());
                }
            }
        }
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DoltCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
