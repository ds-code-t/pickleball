package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.Binding;
import tools.dscode.dolt.store.DoltStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltSandboxPruneTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() {
        DoltScope.clear();
        DoltScope.bind(Binding.sandbox("linbox"));
        DoltRun.stop(project);
        DoltScope.clear();
        DoltRun.stop(project);
        System.clearProperty("pkb_dolt_run_classpath");
    }

    @Test
    @Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
    void pruneDropsByAgeAndOwnerAndKeepsHistoryUnlessAsked() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  @dolt-prune
                  Scenario: Ada
                    * INFO: amount is 1
                    * ASSERT: "1" equals "1"
                """);
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "oldbox", "--owner=ada", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "fresh", "--owner=ada", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "linbox", "--owner=lin", "--project=" + project).code);
        DoltScope.clear();
        Path worker = DoltPaths.worker(project, "linbox");
        Files.createDirectories(worker);
        Files.writeString(worker.resolve(DoltRun.FINGERPRINT), DoltRun.glueFingerprint(project));
        System.setProperty("pkb_dolt_run_classpath", System.getProperty("java.class.path"));
        String features = project.resolve("features").toAbsolutePath().normalize().toString();
        Capture ran = cli(
                "dolt", "run", "--sandbox=linbox",
                "--tags=@dolt-prune",
                "--glue=tools.dscode.coredefinitions",
                "-Dpkb_dolt_track=true",
                "-Dpkb_features=wd-file:" + features,
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false",
                "--project=" + project);
        assertEquals(0, ran.code, ran.out + ran.err);
        DoltScope.clear();
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            assertNotEquals("0", store.session().scalar("SELECT COUNT(*) FROM runs WHERE sandbox = 'linbox'"));
        }

        String old = Instant.now().minus(3, ChronoUnit.DAYS).toString();
        try (DoltDatabase database = DoltDatabase.open(project);
             Statement statement = database.connection().createStatement()) {
            statement.execute("UPDATE sandboxes SET last_used = '" + old + "' WHERE name IN ('oldbox', 'linbox')");
            statement.execute("INSERT INTO runs(run_id, started_at, status, scenario, note, sandbox) "
                    + "VALUES ('kept', '" + old + "', 'passed', 'Ada', '', 'oldbox')");
        }
        Path oldHome = project.resolve(".pickleball/dolt/sandboxes/oldbox");
        Files.createDirectories(oldHome);
        Files.writeString(oldHome.resolve("marker.txt"), "keep-until-prune\n");

        Capture dry = cli("dolt", "sandbox", "prune", "--older-than=1h", "--owner=ada", "--dry-run", "--project=" + project);
        assertEquals(0, dry.code, dry.err);
        assertTrue(dry.out.contains("dry-run drop oldbox"), dry.out);
        assertFalse(dry.out.contains("linbox"), dry.out);
        assertFalse(dry.out.contains("fresh"), dry.out);
        assertTrue(Files.exists(oldHome.resolve("marker.txt")));

        Capture pruned = cli("dolt", "sandbox", "prune", "--older-than=1h", "--owner=ada", "--project=" + project);
        assertEquals(0, pruned.code, pruned.err);
        assertTrue(pruned.out.contains("dropped oldbox"), pruned.out);
        assertTrue(pruned.out.contains("gc "), pruned.out);
        assertFalse(Files.exists(oldHome));
        Capture listed = cli("dolt", "sandbox", "list", "--project=" + project);
        assertTrue(listed.out.contains("fresh"), listed.out);
        assertTrue(listed.out.contains("linbox"), listed.out);
        assertFalse(listed.out.contains("oldbox"), listed.out);
        try (DoltDatabase database = DoltDatabase.open(project);
             Statement statement = database.connection().createStatement();
             var rows = statement.executeQuery("SELECT run_id FROM runs WHERE sandbox = 'oldbox'")) {
            assertTrue(rows.next(), "history stays without --with-history");
        }

        Capture withHistory = cli("dolt", "sandbox", "prune", "--older-than=1h", "--owner=lin",
                "--with-history", "--project=" + project);
        assertEquals(0, withHistory.code, withHistory.err);
        assertTrue(withHistory.out.contains("dropped linbox"), withHistory.out);
        try (DoltDatabase database = DoltDatabase.open(project);
             Statement statement = database.connection().createStatement();
             var rows = statement.executeQuery("SELECT run_id FROM runs WHERE sandbox = 'linbox'")) {
            assertFalse(rows.next());
        }
        try (DoltDatabase database = DoltDatabase.open(project);
             Statement statement = database.connection().createStatement();
             var rows = statement.executeQuery("SELECT run_id FROM runs WHERE run_id = 'kept'")) {
            assertTrue(rows.next());
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
