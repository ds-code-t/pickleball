package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Applies sandbox inserts, updates, and deletes into main and leaves a second merge idle. */
class SandboxMergeTest {
    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        StoreScope.clear();
        StoreTestProcesses.release(project);
    }

    @Test
    void insertUpdateAndDeleteRebuildDerivedRowsAndASecondMergeIsIdle() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """);
        Files.writeString(project.resolve("features/old.feature"), """
                Feature: Old
                  Scenario: Gone
                    * INFO: retire
                """);
        Files.writeString(project.resolve("features/keep.txt"), "keep\n");
        Files.writeString(project.resolve("features/gone.txt"), "gone\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        String synced = scalar(Binding.main(),
                "SELECT last_synced_hash FROM files WHERE path = ?", "features/pay.feature");
        assertFalse(synced == null || synced.isBlank(), synced);
        assertEquals(0, cli("store", "sandbox", "create", "alpha", "--mode=copy", "--project=" + project).code);

        Capture edited = cli("store", "feature", "put", "features/pay.feature",
                "--text=Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 9\n",
                "--sandbox=alpha", "--project=" + project);
        assertEquals(0, edited.code, edited.err);
        assertEquals(0, cli("store", "file", "delete", "--path=features/old.feature", "--confirm",
                "--sandbox=alpha", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "delete", "--path=features/gone.txt", "--confirm",
                "--sandbox=alpha", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/new.txt", "--text=added\n",
                "--sandbox=alpha", "--project=" + project).code);
        Path glue = project.resolve("incoming/PaySteps.java");
        Files.createDirectories(glue.getParent());
        Files.writeString(glue, """
                package steps;
                public class PaySteps {
                    @Given("amount is {word}")
                    public void amount(String value) {}
                }
                """);
        assertEquals(0, cli("store", "file", "put", "--path=src/test/java/steps/PaySteps.java",
                "--file=" + glue, "--sandbox=alpha", "--project=" + project).code);
        assertEquals(0, cli("store", "entity", "put", "Customer", "7", "--payload={\"name\":\"Ada\"}",
                "--sandbox=alpha", "--project=" + project).code);

        Capture merged = cli("store", "sandbox", "merge", "alpha", "--json", "--project=" + project);
        assertEquals(0, merged.code, merged.err + merged.out);
        assertTrue(merged.out.contains("\"ok\":true"), merged.out);
        assertTrue(merged.out.contains("\"command\":\"sandbox merge\""), merged.out);
        assertTrue(merged.out.contains("\"applied\":"), merged.out);
        assertTrue(Integer.parseInt(field(merged.out, "applied")) > 0, merged.out);

        StoreFiles.Row pay = file(Binding.main(), "features/pay.feature");
        assertTrue(new String(pay.raw(), StandardCharsets.UTF_8).contains("amount is 9"));
        assertTrue(pay.edited());
        assertEquals(synced, pay.lastSyncedHash());
        assertEquals(null, file(Binding.main(), "features/old.feature"));
        assertEquals(null, file(Binding.main(), "features/gone.txt"));
        StoreFiles.Row added = file(Binding.main(), "features/new.txt");
        assertEquals("added\n", new String(added.raw(), StandardCharsets.UTF_8));
        assertTrue(added.edited());
        assertEquals("", added.lastSyncedHash());
        StoreFiles.Row kept = file(Binding.main(), "features/keep.txt");
        assertFalse(kept.edited());
        assertEquals("0", scalar(Binding.main(),
                "SELECT COUNT(*) FROM feature_docs WHERE file_path = ?", "features/old.feature"));
        assertEquals("Pay", scalar(Binding.main(),
                "SELECT name FROM feature_docs WHERE file_path = ?", "features/pay.feature"));
        assertTrue(scalar(Binding.main(),
                "SELECT text FROM steps WHERE file_path = ?", "features/pay.feature").contains("amount is 9"));
        assertEquals("amount is {word}", scalar(Binding.main(),
                "SELECT pattern FROM step_definitions WHERE source_path = ? AND removed = 0",
                "src/test/java/steps/PaySteps.java"));
        assertEquals("Ada", scalar(Binding.main(),
                "SELECT json_extract(payload_json, '$.name') FROM entities WHERE entity_type = ? AND entity_id = ?",
                "Customer", "7"));
        assertEquals("keep\n", Files.readString(project.resolve("features/keep.txt")));
        assertTrue(Files.readString(project.resolve("features/pay.feature")).contains("amount is 1"));
        assertFalse(Files.exists(project.resolve("features/gone.txt")));

        Capture again = cli("store", "sandbox", "merge", "alpha", "--json", "--project=" + project);
        assertEquals(0, again.code, again.err + again.out);
        assertEquals("0", field(again.out, "applied"));
        assertEquals("0", field(again.out, "wouldApply"));
        assertTrue(again.out.contains("\"conflicts\":[]"), again.out);
    }

    @Test
    void ownedLargeBinaryLandsOwnedAndDropRemovesTheSandbox() throws Exception {
        Files.writeString(project.resolve("store.properties"), "binary-policy=include\n");
        byte[] body = new byte[300 * 1024];
        java.util.Arrays.fill(body, (byte) 7);
        body[0] = 0;
        Path file = project.resolve("features/shot.bin");
        Files.createDirectories(file.getParent());
        Files.write(file, body);
        git("init");
        git("config", "user.email", "dev@example.com");
        git("config", "user.name", "dev");
        git("config", "commit.gpgsign", "false");
        git("add", "features/shot.bin");
        git("commit", "-m", "add binary");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreFiles.Row before = file(Binding.main(), "features/shot.bin");
        assertTrue(before.binaryRef(), "expected a reference");
        String synced = before.lastSyncedHash() == null ? "" : before.lastSyncedHash();
        assertEquals(0, cli("store", "sandbox", "create", "bins", "--mode=copy", "--project=" + project).code);
        byte[] changed = "owned-bytes".getBytes(StandardCharsets.UTF_8);
        StoreScope.clear();
        StoreScope.bind(Binding.sandbox("bins"));
        StoreContent.put(project, "features/shot.bin", changed);
        StoreScope.clear();

        String previous = System.getProperty("pkb_store_log");
        try {
            System.setProperty("pkb_store_log", "merge");
            Capture merged = cli("store", "sandbox", "merge", "bins", "--drop", "--project=" + project);
            assertEquals(0, merged.code, merged.err + merged.out);
            assertTrue(merged.out.contains("dropped bins"), merged.out);
            assertFalse(Files.exists(StorePaths.sandboxDatabase(project, "bins")));
            String log = Files.readString(StorePaths.log(project), StandardCharsets.UTF_8);
            assertTrue(log.contains(" merge "), log);
            assertTrue(log.contains("bins"), log);
            assertEquals('2', log.charAt(0), log);
        } finally {
            if (previous == null) System.clearProperty("pkb_store_log");
            else System.setProperty("pkb_store_log", previous);
        }
        StoreFiles.Row after = file(Binding.main(), "features/shot.bin");
        assertFalse(after.binaryRef());
        assertEquals("db", after.location());
        assertEquals("", after.gitCommit());
        assertTrue(after.edited());
        assertEquals(synced, after.lastSyncedHash());
        assertArrayEquals(changed, after.raw());
        assertArrayEquals(body, Files.readAllBytes(file));
    }

    private StoreFiles.Row file(Binding binding, String path) throws Exception {
        StoreScope.clear();
        try (var database = StoreDatabase.open(project, binding)) {
            return StoreFiles.find(database.connection(), path);
        }
    }

    private String scalar(Binding binding, String sql, String... args) throws Exception {
        StoreScope.clear();
        try (var database = StoreDatabase.open(project, binding);
             PreparedStatement statement = database.connection().prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) statement.setString(index + 1, args[index]);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getString(1) != null ? result.getString(1) : "";
            }
        }
    }

    private static String field(String json, String name) {
        String token = "\"" + name + "\":";
        int at = json.indexOf(token);
        if (at < 0) return "";
        int start = at + token.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return json.substring(start, end);
    }

    private void git(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) throw new AssertionError(code + " " + output);
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
