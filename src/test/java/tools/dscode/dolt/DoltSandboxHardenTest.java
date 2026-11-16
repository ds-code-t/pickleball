package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.Binding;
import tools.dscode.dolt.store.DoltStore;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltSandboxHardenTest {
    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        DoltScope.clear();
        DoltTestProcesses.release(project);
    }

    @Test
    void fromMustBeACommitHashAndExtractRejectsTraversal() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        Capture injected = cli("dolt", "sandbox", "create", "beta",
                "--from=abcd'; DROP TABLE sandboxes; --", "--project=" + project);
        assertEquals(1, injected.code, injected.out);
        assertTrue(injected.err.contains("commit hash"), injected.err);
        Capture listed = cli("dolt", "sandbox", "list", "--project=" + project);
        assertFalse(listed.out.contains("beta"), listed.out);

        String hash;
        try (DoltStore store = DoltStore.open(project, Binding.main())) {
            hash = store.session().scalar("SELECT commit_hash FROM dolt_log ORDER BY date DESC LIMIT 1");
        }
        assertTrue(hash != null && hash.length() >= 6, hash);
        Capture created = cli("dolt", "sandbox", "create", "beta", "--from=" + hash, "--project=" + project);
        assertEquals(0, created.code, created.err + " hash=" + hash);
        DoltScope.clear();

        String marker = "pkb-dolt-nope-" + UUID.randomUUID();
        Path escaped = project.resolve(marker);
        try (DoltStore store = DoltStore.open(project, Binding.sandbox("beta"))) {
            store.session().update(
                    "INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "../" + marker, "abc", "nope".getBytes(StandardCharsets.UTF_8), 1L, "file", "t", "abc", 1);
            store.session().update(
                    "INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    escaped.toAbsolutePath().toString(), "abc", "nope".getBytes(StandardCharsets.UTF_8), 1L, "file", "t", "abc", 1);
        }
        DoltScope.clear();
        Path dest = project.resolve("out");
        Capture extracted = cli("dolt", "sandbox", "extract", "beta", "--to=" + dest, "--project=" + project);
        assertEquals(1, extracted.code, extracted.out);
        assertTrue(extracted.err.contains("refused") || extracted.err.contains("..") || extracted.err.contains("relative"),
                extracted.err);
        assertFalse(Files.exists(escaped), escaped.toString());
        assertFalse(Files.exists(dest.resolve(marker)));
    }

    @Test
    void dropRequiresForceWhileAnotherProcessHoldsTheBranch() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "held", "--project=" + project).code);
        DoltScope.clear();
        Process sleeper = pauseProcess();
        try {
            Path lease = project.resolve(".pickleball/dolt/branch-lease/sbx-held")
                    .resolve(Long.toString(sleeper.pid()));
            Files.createDirectories(lease.getParent());
            Files.writeString(lease, "1");
            Capture busy = cli("dolt", "sandbox", "drop", "held", "--project=" + project);
            assertEquals(1, busy.code, busy.out);
            assertTrue(busy.err.contains("in use"), busy.err);
        } finally {
            sleeper.destroyForcibly();
            sleeper.waitFor(10, TimeUnit.SECONDS);
        }
        Capture dropped = cli("dolt", "sandbox", "drop", "held", "--project=" + project);
        assertEquals(0, dropped.code, dropped.err);
        assertTrue(dropped.out.contains("dropped held"), dropped.out);
    }

    @Test
    void anotherJvmHoldingTheBranchBlocksDropAndPrune() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "busy", "--project=" + project).code);
        DoltScope.clear();
        Process holder = holdProcess(project, "busy");
        try {
            String old = Instant.now().minus(3, ChronoUnit.DAYS).toString();
            try (var store = tools.dscode.dolt.store.DoltStore.open(project, tools.dscode.dolt.store.Binding.main())) {
                store.session().update("UPDATE sandboxes SET last_used = ? WHERE name = 'busy'", old);
            }
            Capture busy = cli("dolt", "sandbox", "drop", "busy", "--project=" + project);
            assertEquals(1, busy.code, busy.out + busy.err);
            assertTrue(busy.err.contains("in use"), busy.err);
            Capture pruned = cli("dolt", "sandbox", "prune", "--older-than=1h", "--project=" + project);
            assertEquals(0, pruned.code, pruned.err);
            assertTrue(pruned.out.contains("skipped busy in use"), pruned.out);
            assertFalse(pruned.out.contains("dropped busy"), pruned.out);
            Capture listed = cli("dolt", "sandbox", "list", "--project=" + project);
            assertTrue(listed.out.contains("busy"), listed.out);
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(30, TimeUnit.SECONDS)) holder.destroyForcibly();
        }
        Capture dropped = cli("dolt", "sandbox", "drop", "busy", "--project=" + project);
        assertEquals(0, dropped.code, dropped.err);
        assertTrue(dropped.out.contains("dropped busy"), dropped.out);
    }

    private static Process pauseProcess() throws Exception {
        return new ProcessBuilder(DoltTestProcesses.java(List.of("-cp", System.getProperty("java.class.path"),
                DoltSandboxHardenTest.class.getName() + "$Pause"))).start();
    }

    private static Process holdProcess(Path project, String name) throws Exception {
        Path err = Files.createTempFile("dolt-hold-", ".err");
        Process process = new ProcessBuilder(DoltTestProcesses.java(List.of("-cp", System.getProperty("java.class.path"),
                DoltSandboxHardenTest.class.getName() + "$Hold",
                project.toAbsolutePath().toString(), name)))
                .redirectError(err.toFile())
                .start();
        String line = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                if (!reader.ready()) {
                    if (!process.isAlive()) {
                        throw new IllegalStateException("holder exited " + process.exitValue() + " " + Files.readString(err));
                    }
                    Thread.sleep(50);
                    continue;
                }
                line = reader.readLine();
                if (line != null && line.startsWith("HOLDING ")) return process;
            }
        }
        process.destroyForcibly();
        throw new IllegalStateException("holder did not start: " + line + " " + Files.readString(err));
    }

    /** Stays alive so a lease file can name a live pid. No shell `sleep`, so Windows can run it. */
    public static class Pause {
        public static void main(String[] args) throws Exception {
            Thread.sleep(60_000);
        }
    }

    /** Second JVM. Holds one sandbox connection until stdin closes. */
    public static class Hold {
        public static void main(String[] args) throws Exception {
            Path project = Path.of(args[0]);
            String name = args[1];
            try (var store = tools.dscode.dolt.store.DoltStore.open(project, tools.dscode.dolt.store.Binding.sandbox(name))) {
                System.out.println("HOLDING " + ProcessHandle.current().pid());
                System.out.flush();
                while (System.in.read() >= 0) {
                    // The parent closes stdin when the hold should end.
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
