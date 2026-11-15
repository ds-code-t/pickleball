package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.Binding;
import tools.dscode.dolt.store.DoltStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltSandboxHardenTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        DoltScope.clear();
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
        Process sleeper = new ProcessBuilder("sleep", "60").start();
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
        }
        Capture dropped = cli("dolt", "sandbox", "drop", "held", "--project=" + project);
        assertEquals(0, dropped.code, dropped.err);
        assertTrue(dropped.out.contains("dropped held"), dropped.out);
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
