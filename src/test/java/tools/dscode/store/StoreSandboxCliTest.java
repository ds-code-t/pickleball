package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlStore;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreSandboxCliTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() throws Exception {
        StoreScope.clear();
        StoreTestProcesses.release(project);
    }

    @Test
    void createListUseDiffExtractAndDrop() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """);
        Capture hydrated = cli("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.err);
        StoreScope.clear();

        Capture created = cli("store", "sandbox", "create", "alpha", "--owner=ada", "--project=" + project);
        assertEquals(0, created.code, created.err);
        assertTrue(created.out.contains("branch sbx-alpha"), created.out);

        Capture listed = cli("store", "sandbox", "list", "--json", "--project=" + project);
        assertEquals(0, listed.code, listed.err);
        assertTrue(listed.out.contains("\"name\":\"alpha\""), listed.out);
        assertTrue(listed.out.contains("\"ttl\":\"24h\""), listed.out);
        assertTrue(listed.out.contains("\"owner\":\"ada\""), listed.out);

        Capture used = cli("store", "sandbox", "use", "alpha", "--project=" + project);
        assertEquals(0, used.code, used.err);
        assertTrue(used.out.contains("export PKB_STORE_SANDBOX=alpha"), used.out);
        assertTrue(used.out.contains("$env:PKB_STORE_SANDBOX='alpha'"), used.out);
        assertTrue(used.out.contains("set PKB_STORE_SANDBOX=alpha"), used.out);
        assertFalse(Files.exists(project.resolve(".pickleball/store/sandbox-selection")), used.out);

        Capture edited = cli("store", "feature", "put", "features/pay.feature",
                "--text=Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 9\n",
                "--sandbox=alpha", "--project=" + project);
        assertEquals(0, edited.code, edited.err);
        StoreScope.clear();

        Capture diff = cli("store", "sandbox", "diff", "alpha", "--json", "--project=" + project);
        assertEquals(0, diff.code, diff.err);
        assertTrue(diff.out.contains("features/pay.feature"), diff.out);

        Path outside = project.resolve("sibling");
        Capture refused = cli("store", "sandbox", "extract", "alpha", "--to=" + project, "--project=" + project);
        assertEquals(1, refused.code, refused.out);
        assertTrue(refused.err.contains("--into-project"), refused.err);

        Capture extracted = cli("store", "sandbox", "extract", "alpha", "--to=" + outside, "--project=" + project);
        assertEquals(0, extracted.code, extracted.err);
        assertTrue(Files.readString(outside.resolve("features/pay.feature")).contains("amount is 9"), extracted.out);

        Capture unknown = cli("store", "sandbox", "use", "missing", "--project=" + project);
        assertEquals(1, unknown.code, unknown.err);
        assertTrue(unknown.err.contains("unknown sandbox missing"), unknown.err);

        Capture bad = cli("store", "sandbox", "create", "No", "--project=" + project);
        assertEquals(1, bad.code, bad.err);

        Capture dropped = cli("store", "sandbox", "drop", "alpha", "--project=" + project);
        assertEquals(0, dropped.code, dropped.err);
        Capture after = cli("store", "sandbox", "list", "--project=" + project);
        assertEquals(0, after.code, after.err);
        assertTrue(after.out.contains("sandboxes 0"), after.out);
    }

    @Test
    void extractRefusesABadPathBeforeWritingAnyFile() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """);
        Capture hydrated = cli("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.err);
        StoreScope.clear();
        Capture created = cli("store", "sandbox", "create", "alpha", "--project=" + project);
        assertEquals(0, created.code, created.err);
        StoreScope.clear();
        try (SqlStore store = SqlStore.open(project, Binding.sandbox("alpha"))) {
            store.session().update(
                    """
                    INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    "zzz/../out.txt",
                    "abc",
                    "nope".getBytes(StandardCharsets.UTF_8),
                    0L,
                    "feature",
                    "t",
                    "abc",
                    1);
        }
        StoreScope.clear();
        Path outside = project.resolve("sibling");
        Capture refused = cli("store", "sandbox", "extract", "alpha", "--to=" + outside, "--project=" + project);
        assertEquals(1, refused.code, refused.out + refused.err);
        assertTrue(refused.err.contains("extract refused zzz/../out.txt"), refused.err);
        assertFalse(Files.exists(outside.resolve("features/pay.feature")));
        assertFalse(Files.exists(outside.resolve("features")));
        assertFalse(Files.exists(outside));
    }

    @Test
    void extractUndoesEarlierFilesWhenALaterWriteFails() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """);
        Capture hydrated = cli("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.err);
        StoreScope.clear();
        Capture created = cli("store", "sandbox", "create", "alpha", "--project=" + project);
        assertEquals(0, created.code, created.err);
        StoreScope.clear();
        try (SqlStore store = SqlStore.open(project, Binding.sandbox("alpha"))) {
            for (String path : new String[] {"a/b.txt", "a/b.txt/c.txt"}) {
                store.session().update(
                        """
                        INSERT INTO files(path, content_hash, raw_content, mtime, source_type, last_hydrated_at, last_synced_hash, db_edited)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        path,
                        "abc",
                        "body".getBytes(StandardCharsets.UTF_8),
                        0L,
                        "feature",
                        "t",
                        "abc",
                        1);
            }
        }
        StoreScope.clear();
        Path outside = project.resolve("sibling");
        Capture failed = cli("store", "sandbox", "extract", "alpha", "--to=" + outside, "--project=" + project);
        assertEquals(1, failed.code, failed.out + failed.err);
        assertFalse(failed.out.contains("extracted alpha"), failed.out);
        assertFalse(Files.exists(outside.resolve("a/b.txt")));
        assertFalse(Files.exists(outside.resolve("a")));
        assertFalse(Files.exists(outside));
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
