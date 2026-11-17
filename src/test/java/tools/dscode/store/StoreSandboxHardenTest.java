package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlStore;

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

class StoreSandboxHardenTest {
    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        StoreScope.clear();
        StoreTestProcesses.release(project);
    }

    @Test
    void fromMustBeACommitHashAndExtractRejectsTraversal() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        Capture injected = cli("store", "sandbox", "create", "beta",
                "--from=abcd'; DROP TABLE sandboxes; --", "--project=" + project);
        assertEquals(1, injected.code, injected.out);
        assertTrue(injected.err.contains("main"), injected.err);
        Capture listed = cli("store", "sandbox", "list", "--project=" + project);
        assertFalse(listed.out.contains("beta"), listed.out);

        Capture other = cli("store", "sandbox", "create", "beta", "--from=abc123def", "--project=" + project);
        assertEquals(1, other.code, other.err);
        assertTrue(other.err.contains("main"), other.err);

        Capture created = cli("store", "sandbox", "create", "beta", "--from=main", "--project=" + project);
        assertEquals(0, created.code, created.err);
        StoreScope.clear();

        String marker = "pkb-store-nope-" + UUID.randomUUID();
        Path escaped = project.resolve(marker);
        try (SqlStore store = SqlStore.open(project, Binding.sandbox("beta"))) {
            store.session().update(
                    "INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    "../" + marker, "abc", "nope".getBytes(StandardCharsets.UTF_8), 1L, "file", "t", "abc", 1);
            store.session().update(
                    "INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    escaped.toAbsolutePath().toString(), "abc", "nope".getBytes(StandardCharsets.UTF_8), 1L, "file", "t", "abc", 1);
        }
        StoreScope.clear();
        Path dest = project.resolve("out");
        Capture extracted = cli("store", "sandbox", "extract", "beta", "--to=" + dest, "--project=" + project);
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
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "held", "--project=" + project).code);
        StoreScope.clear();
        Process holder = holdProcess(project, "held");
        try {
            Capture busy = cli("store", "sandbox", "drop", "held", "--project=" + project);
            assertEquals(1, busy.code, busy.out + busy.err);
            assertTrue(busy.err.contains("in use"), busy.err);
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(30, TimeUnit.SECONDS)) holder.destroyForcibly();
        }
        Capture dropped = cli("store", "sandbox", "drop", "held", "--project=" + project);
        assertEquals(0, dropped.code, dropped.err);
        assertTrue(dropped.out.contains("dropped held"), dropped.out);
    }

    @Test
    void anotherJvmHoldingTheBranchBlocksDropAndPrune() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "busy", "--project=" + project).code);
        StoreScope.clear();
        Process holder = holdProcess(project, "busy");
        try {
            String old = Instant.now().minus(3, ChronoUnit.DAYS).toString();
            try (var store = tools.dscode.store.sql.SqlStore.open(project, tools.dscode.store.sql.Binding.sandbox("busy"))) {
                store.session().update(
                        "INSERT INTO _pkb_branch(`key`, value) VALUES ('last_used', ?) "
                                + "ON CONFLICT(`key`) DO UPDATE SET value = excluded.value",
                        old);
            }
            StoreScope.clear();
            Capture busy = cli("store", "sandbox", "drop", "busy", "--project=" + project);
            assertEquals(1, busy.code, busy.out + busy.err);
            assertTrue(busy.err.contains("in use"), busy.err);
            Capture pruned = cli("store", "sandbox", "prune", "--older-than=1h", "--project=" + project);
            assertEquals(0, pruned.code, pruned.err);
            assertTrue(pruned.out.contains("skipped busy in use"), pruned.out);
            assertFalse(pruned.out.contains("dropped busy"), pruned.out);
            Capture listed = cli("store", "sandbox", "list", "--project=" + project);
            assertTrue(listed.out.contains("busy"), listed.out);
        } finally {
            holder.getOutputStream().close();
            if (!holder.waitFor(30, TimeUnit.SECONDS)) holder.destroyForcibly();
        }
        Capture dropped = cli("store", "sandbox", "drop", "busy", "--project=" + project);
        assertEquals(0, dropped.code, dropped.err);
        assertTrue(dropped.out.contains("dropped busy"), dropped.out);
    }

    private static Process pauseProcess() throws Exception {
        return new ProcessBuilder(StoreTestProcesses.java(List.of("-cp", System.getProperty("java.class.path"),
                StoreSandboxHardenTest.class.getName() + "$Pause"))).start();
    }

    private static Process holdProcess(Path project, String name) throws Exception {
        Path err = Files.createTempFile("store-hold-", ".err");
        Process process = new ProcessBuilder(StoreTestProcesses.java(List.of("-cp", System.getProperty("java.class.path"),
                StoreSandboxHardenTest.class.getName() + "$Hold",
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
            try (var store = tools.dscode.store.sql.SqlStore.open(project, tools.dscode.store.sql.Binding.sandbox(name))) {
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
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
