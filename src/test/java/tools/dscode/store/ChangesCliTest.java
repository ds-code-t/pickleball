package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code store changes} filters, pages, and the retain and prune trim. */
class ChangesCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "super-secret-token";

    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        StoreScope.clear();
        System.clearProperty("pkb_store_log");
        System.clearProperty("pkb_store_log_entity");
        System.clearProperty("pkb_store_log_max_bytes");
        System.clearProperty("pkb_store_changes_days");
        StoreTestProcesses.release(project);
    }

    @Test
    void helpNamesMergeAndChanges() {
        String help = StoreCli.help();
        assertTrue(help.contains("sandbox merge <name>"), help);
        assertTrue(help.contains("store changes"), help);
        assertTrue(help.contains("--policy=fail|branch-wins|main-wins"), help);
        assertTrue(help.contains("pkb_store_changes_days"), help);
    }

    @Test
    void filtersPagesAndOmitsRawContent() throws Exception {
        Capture missing = cli("store", "changes", "--json", "--project=" + project);
        assertEquals(0, missing.code, missing.err);
        assertEquals(0, JSON.readTree(missing.out).get("count").asInt());

        Files.createDirectories(project.resolve("notes"));
        Files.writeString(project.resolve("notes/a.txt"), "one\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=notes/a.txt", "--text=" + SECRET + "\n",
                "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=notes/b.txt", "--text=two\n",
                "--project=" + project).code);
        assertEquals(0, cli("store", "sandbox", "create", "alpha", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=notes/c.txt", "--text=sandbox\n",
                "--sandbox=alpha", "--project=" + project).code);

        Capture all = cli("store", "changes", "--json", "--project=" + project);
        assertEquals(0, all.code, all.err);
        JsonNode rows = JSON.readTree(all.out).get("rows");
        assertTrue(rows.size() >= 2, all.out);
        assertFalse(all.out.contains(SECRET), all.out);
        assertFalse(all.out.contains("\"old\""), all.out);
        assertFalse(all.out.contains("\"new\""), all.out);
        long first = rows.get(0).get("seq").asLong();
        assertEquals("main", JSON.readTree(all.out).get("sandbox").asText());

        Capture page = cli("store", "changes", "--limit=1", "--json", "--project=" + project);
        assertEquals(1, JSON.readTree(page.out).get("count").asInt());
        assertEquals(first, JSON.readTree(page.out).get("rows").get(0).get("seq").asLong());
        Capture rest = cli("store", "changes", "--since=" + first, "--json", "--project=" + project);
        for (JsonNode row : JSON.readTree(rest.out).get("rows")) {
            assertTrue(row.get("seq").asLong() > first, rest.out);
        }
        Capture files = cli("store", "changes", "--table=files", "--json", "--project=" + project);
        for (JsonNode row : JSON.readTree(files.out).get("rows")) {
            assertEquals("files", row.get("table").asText());
        }
        assertTrue(files.out.contains("notes/b.txt"), files.out);

        Capture sandbox = cli("store", "changes", "--sandbox=alpha", "--table=files", "--json", "--project=" + project);
        assertEquals(0, sandbox.code, sandbox.err);
        assertEquals("alpha", JSON.readTree(sandbox.out).get("sandbox").asText());
        assertTrue(sandbox.out.contains("notes/c.txt"), sandbox.out);
        assertFalse(sandbox.out.contains("notes/b.txt"), sandbox.out);

        Capture bad = cli("store", "changes", "--limit=0", "--project=" + project);
        assertEquals(1, bad.code, bad.out);
        Capture nan = cli("store", "changes", "--since=nope", "--project=" + project);
        assertEquals(1, nan.code, nan.out);
        assertTrue(nan.err.contains("integer"), nan.err);

        String previous = System.getProperty("pkb_store_log");
        String previousEntity = System.getProperty("pkb_store_log_entity");
        String previousMax = System.getProperty("pkb_store_log_max_bytes");
        try {
            System.clearProperty("pkb_store_log");
            long before = Files.isRegularFile(StorePaths.log(project)) ? Files.size(StorePaths.log(project)) : 0L;
            assertEquals(0, cli("store", "changes", "--project=" + project).code);
            long afterOff = Files.isRegularFile(StorePaths.log(project)) ? Files.size(StorePaths.log(project)) : 0L;
            assertEquals(before, afterOff);
            System.setProperty("pkb_store_log", "changes");
            System.setProperty("pkb_store_log_entity", "not-a-table");
            assertEquals(0, cli("store", "changes", "--project=" + project).code);
            long filtered = Files.isRegularFile(StorePaths.log(project)) ? Files.size(StorePaths.log(project)) : 0L;
            assertEquals(afterOff, filtered);
            System.clearProperty("pkb_store_log_entity");
            assertEquals(0, cli("store", "changes", "--project=" + project).code);
            String log = Files.readString(StorePaths.log(project), StandardCharsets.UTF_8);
            assertTrue(log.contains(" changes "), log);
            assertEquals('2', log.trim().charAt(0), log);
            System.setProperty("pkb_store_log_max_bytes", "64");
            StoreLog.log(project, "changes", "*", "main", "x".repeat(200));
            assertTrue(Files.size(StorePaths.log(project)) <= 64);
            System.setProperty("pkb_store_log", "off");
            long capped = Files.size(StorePaths.log(project));
            StoreLog.log(project, "changes", "*", "main", "again");
            assertEquals(capped, Files.size(StorePaths.log(project)));
        } finally {
            restore("pkb_store_log", previous);
            restore("pkb_store_log_entity", previousEntity);
            restore("pkb_store_log_max_bytes", previousMax);
        }
    }

    @Test
    void retainAndPruneDropRowsOlderThanTheRetentionWindow() throws Exception {
        Files.createDirectories(project.resolve("notes"));
        Files.writeString(project.resolve("notes/a.txt"), "one\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=notes/old.txt", "--text=old\n",
                "--project=" + project).code);
        backdate(Binding.main(), "notes/old.txt");
        System.setProperty("pkb_store_changes_days", "14");
        Capture retained = cli("store", "retain", "--days=3650", "--max-bytes=100000000", "--project=" + project);
        assertEquals(0, retained.code, retained.err);
        String afterRetain = cli("store", "changes", "--table=files", "--json", "--project=" + project).out;
        assertFalse(afterRetain.contains("notes/old.txt"), afterRetain);

        assertEquals(0, cli("store", "file", "put", "--path=notes/aged.txt", "--text=aged\n",
                "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=notes/fresh.txt", "--text=fresh\n",
                "--project=" + project).code);
        backdate(Binding.main(), "notes/aged.txt");
        Capture pruned = cli("store", "sandbox", "prune", "--project=" + project);
        assertEquals(0, pruned.code, pruned.err + pruned.out);
        assertTrue(pruned.out.contains("changes removed="), pruned.out);
        String afterPrune = cli("store", "changes", "--table=files", "--json", "--project=" + project).out;
        assertFalse(afterPrune.contains("notes/aged.txt"), afterPrune);
        assertTrue(afterPrune.contains("notes/fresh.txt"), afterPrune);
    }

    private void backdate(Binding binding, String key) throws Exception {
        StoreScope.clear();
        try (var database = StoreDatabase.open(project, binding);
             Statement statement = database.connection().createStatement()) {
            statement.executeUpdate(
                    "UPDATE _pkb_changes SET at = '2000-01-01T00:00:00.000Z' WHERE pk = '" + key.replace("'", "''") + "'");
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private Capture cli(String... args) {
        StoreScope.clear();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = StoreCli.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        StoreScope.clear();
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
