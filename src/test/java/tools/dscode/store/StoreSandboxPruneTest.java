package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlStore;

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

class StoreSandboxPruneTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() throws Exception {
        StoreScope.clear();
        StoreScope.bind(Binding.sandbox("linbox"));
        StoreRun.stop(project);
        StoreScope.clear();
        StoreRun.stop(project);
        System.clearProperty("pkb_store_run_classpath");
        StoreTestProcesses.release(project);
    }

    @Test
    @Timeout(value = 300, unit = java.util.concurrent.TimeUnit.SECONDS)
    void pruneDropsByAgeAndOwnerAndKeepsHistoryUnlessAsked() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  @store-prune
                  Scenario: Ada
                    * INFO: amount is 1
                    * ASSERT: "1" equals "1"
                """);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "oldbox", "--owner=ada", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "fresh", "--owner=ada", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "linbox", "--owner=lin", "--project=" + project).code);
        StoreScope.clear();
        Path worker = StorePaths.worker(project, "linbox");
        Files.createDirectories(worker);
        Files.writeString(worker.resolve(StoreRun.FINGERPRINT), StoreRun.glueFingerprint(project));
        System.setProperty("pkb_store_run_classpath", System.getProperty("java.class.path"));
        String features = project.resolve("features").toAbsolutePath().normalize().toString();
        Capture ran = cli(
                "store", "run", "--sandbox=linbox",
                "--tags=@store-prune",
                "--glue=tools.dscode.coredefinitions",
                "-Dpkb_store_track=true",
                "-Dpkb_features=wd-file:" + features,
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false",
                "--project=" + project);
        assertEquals(0, ran.code, ran.out + ran.err);
        StoreScope.clear();
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            assertNotEquals("0", store.session().scalar("SELECT COUNT(*) FROM runs WHERE sandbox = 'linbox'"));
        }

        String old = Instant.now().minus(3, ChronoUnit.DAYS).toString();
        age(project, "oldbox", old);
        age(project, "linbox", old);
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement()) {
            statement.execute("INSERT INTO runs(run_id, started_at, status, scenario, note, sandbox) "
                    + "VALUES ('kept', '" + old + "', 'passed', 'Ada', '', 'oldbox')");
        }
        Path oldHome = project.resolve(".pickleball/store/sandboxes/oldbox");
        Files.createDirectories(oldHome);
        Files.writeString(oldHome.resolve("marker.txt"), "keep-until-prune\n");

        Capture dry = cli("store", "sandbox", "prune", "--older-than=1h", "--owner=ada", "--dry-run", "--project=" + project);
        assertEquals(0, dry.code, dry.err);
        assertTrue(dry.out.contains("dry-run drop oldbox"), dry.out);
        assertFalse(dry.out.contains("linbox"), dry.out);
        assertFalse(dry.out.contains("fresh"), dry.out);
        assertTrue(Files.exists(oldHome.resolve("marker.txt")));

        Capture pruned = cli("store", "sandbox", "prune", "--older-than=1h", "--owner=ada", "--vacuum", "--project=" + project);
        assertEquals(0, pruned.code, pruned.err);
        assertTrue(pruned.out.contains("dropped oldbox"), pruned.out);
        assertTrue(pruned.out.contains("vacuum "), pruned.out);
        assertFalse(Files.exists(oldHome));
        Capture listed = cli("store", "sandbox", "list", "--project=" + project);
        assertTrue(listed.out.contains("fresh"), listed.out);
        assertTrue(listed.out.contains("linbox"), listed.out);
        assertFalse(listed.out.contains("oldbox"), listed.out);
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement();
             var rows = statement.executeQuery("SELECT run_id FROM runs WHERE sandbox = 'oldbox'")) {
            assertTrue(rows.next(), "history stays without --with-history");
        }

        StoreScope.clear();
        StoreScope.bind(Binding.sandbox("linbox"));
        StoreRun.stop(project);
        StoreScope.clear();
        Capture withHistory = cli("store", "sandbox", "prune", "--older-than=1h", "--owner=lin",
                "--with-history", "--project=" + project);
        assertEquals(0, withHistory.code, withHistory.err);
        assertTrue(withHistory.out.contains("dropped linbox"), withHistory.out);
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement();
             var rows = statement.executeQuery("SELECT run_id FROM runs WHERE sandbox = 'linbox'")) {
            assertFalse(rows.next());
        }
        try (StoreDatabase database = StoreDatabase.open(project);
             Statement statement = database.connection().createStatement();
             var rows = statement.executeQuery("SELECT run_id FROM runs WHERE run_id = 'kept'")) {
            assertTrue(rows.next());
        }

        assertEquals(0, cli("store", "sandbox", "create", "busybox", "--owner=ada", "--project=" + project).code);
        StoreScope.clear();
        age(project, "busybox", Instant.now().minus(3, ChronoUnit.DAYS).toString());
        Process holder = hold(project, "busybox");
        try {
            Capture held = cli("store", "sandbox", "prune", "--older-than=1h", "--owner=ada", "--project=" + project);
            assertEquals(0, held.code, held.err);
            assertTrue(held.out.contains("skipped busybox in use"), held.out);
            assertFalse(held.out.contains("dropped busybox"), held.out);
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) holder.destroyForcibly();
        }
        Capture afterHold = cli("store", "sandbox", "prune", "--older-than=1h", "--owner=ada", "--project=" + project);
        assertEquals(0, afterHold.code, afterHold.err);
        assertTrue(afterHold.out.contains("dropped busybox"), afterHold.out);
    }

    private static void age(Path project, String name, String when) throws Exception {
        try (SqlStore store = SqlStore.open(project, Binding.sandbox(name))) {
            store.session().update(
                    "INSERT INTO _pkb_branch(`key`, value) VALUES ('last_used', ?) "
                            + "ON CONFLICT(`key`) DO UPDATE SET value = excluded.value",
                    when);
        }
        StoreScope.clear();
    }

    private static Process hold(Path project, String name) throws Exception {
        Path err = Files.createTempFile("store-hold-", ".err");
        Process process = new ProcessBuilder(StoreTestProcesses.java(java.util.List.of(
                "-cp", System.getProperty("java.class.path"),
                StoreSandboxHardenTest.class.getName() + "$Hold",
                project.toAbsolutePath().toString(), name)))
                .redirectError(err.toFile())
                .start();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                if (!reader.ready()) {
                    if (!process.isAlive()) {
                        throw new IllegalStateException("holder exited " + process.exitValue() + " " + Files.readString(err));
                    }
                    Thread.sleep(50);
                    continue;
                }
                String line = reader.readLine();
                if (line != null && line.startsWith("HOLDING ")) return process;
            }
        }
        process.destroyForcibly();
        throw new IllegalStateException("holder did not start " + Files.readString(err));
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
