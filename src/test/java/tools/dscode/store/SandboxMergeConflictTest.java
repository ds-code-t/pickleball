package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Conflict policies, a converged row, and a report that carries hashes only. */
class SandboxMergeConflictTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "super-secret-token";

    @TempDir
    Path project;

    @AfterEach
    void release() throws Exception {
        StoreScope.clear();
        StoreTestProcesses.release(project);
    }

    @Test
    void failWritesNothingAndTheReportHasHashesOnly() throws Exception {
        seed();
        assertEquals(0, cli("store", "sandbox", "create", "left", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/a.txt",
                "--text=" + SECRET + "\n", "--sandbox=left", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/a.txt",
                "--text=main-body\n", "--project=" + project).code);
        byte[] before = bytes("features/a.txt");

        Capture dry = cli("store", "sandbox", "merge", "left", "--dry-run", "--json", "--project=" + project);
        assertEquals(1, dry.code, dry.out + dry.err);
        assertTrue(dry.out.contains("\"ok\":false"), dry.out);
        assertTrue(dry.out.contains("\"dryRun\":true"), dry.out);
        assertEquals(0, reportCount("left"));
        assertEquals(before.length, bytes("features/a.txt").length);

        Capture failed = cli("store", "sandbox", "merge", "left", "--json", "--project=" + project);
        assertEquals(1, failed.code, failed.out + failed.err);
        JsonNode node = JSON.readTree(failed.out);
        assertFalse(node.get("ok").asBoolean());
        assertEquals("sandbox merge", node.get("command").asText());
        assertEquals("left", node.get("sandbox").asText());
        assertEquals("fail", node.get("policy").asText());
        assertEquals(0, node.get("applied").asInt());
        assertTrue(node.has("createdFromCommit"));
        assertTrue(node.has("converged"));
        assertTrue(node.has("wouldApply"));
        JsonNode conflict = node.get("conflicts").get(0);
        assertEquals("files", conflict.get("table").asText());
        assertEquals("features/a.txt", conflict.get("key").asText());
        assertEquals("none", conflict.get("resolution").asText());
        assertTrue(conflict.get("base").asText().startsWith("sha256:"), conflict.toString());
        assertTrue(conflict.get("main").asText().startsWith("sha256:"), conflict.toString());
        assertTrue(conflict.get("branch").asText().startsWith("sha256:"), conflict.toString());
        assertFalse(failed.out.contains(SECRET), failed.out);
        assertFalse(failed.out.contains("main-body"), failed.out);
        Path report = onlyReport("left");
        assertFalse(report.getFileName().toString().contains(":"), report.toString());
        String saved = Files.readString(report);
        assertFalse(saved.contains(SECRET), saved);
        assertTrue(saved.contains("\"resolution\":\"none\""), saved);
        Capture text = cli("store", "sandbox", "merge", "left", "--project=" + project);
        assertEquals(1, text.code, text.out);
        assertTrue(text.out.contains("table\n"), text.out);
        assertTrue(text.out.contains("features/a.txt"), text.out);
        assertTrue(text.out.contains("\t"), text.out);
        assertTrue(text.out.contains("fix: store sandbox merge left --policy=branch-wins"), text.out);
        assertTrue(text.out.contains("fix: store sandbox merge left --policy=main-wins"), text.out);
        assertTrue(text.out.contains("fix: store sandbox extract left --to=<dir> and commit with Git"), text.out);
        assertFalse(text.out.contains(SECRET), text.out);
        assertEquals(new String(before, StandardCharsets.UTF_8),
                new String(bytes("features/a.txt"), StandardCharsets.UTF_8));

        Capture unknown = cli("store", "sandbox", "merge", "left", "--policy=ask", "--project=" + project);
        assertEquals(1, unknown.code, unknown.out);
        assertTrue(unknown.err.contains("unknown merge policy"), unknown.err);
    }

    @Test
    void branchWinsAndMainWinsAndConvergedAndDeleteVersusModify() throws Exception {
        seed();
        assertEquals(0, cli("store", "sandbox", "create", "left", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/a.txt", "--text=branch-body\n",
                "--sandbox=left", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/extra.txt", "--text=from-branch\n",
                "--sandbox=left", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/a.txt", "--text=main-body\n",
                "--project=" + project).code);

        Capture branch = cli("store", "sandbox", "merge", "left", "--policy=branch-wins", "--json",
                "--project=" + project);
        assertEquals(0, branch.code, branch.err + branch.out);
        JsonNode node = JSON.readTree(branch.out);
        assertTrue(node.get("ok").asBoolean());
        assertEquals("branch", node.get("conflicts").get(0).get("resolution").asText());
        assertEquals("branch-body\n", text("features/a.txt"));
        assertEquals("from-branch\n", text("features/extra.txt"));
        Capture idle = cli("store", "sandbox", "merge", "left", "--policy=branch-wins", "--json",
                "--project=" + project);
        assertEquals(0, idle.code, idle.err + idle.out);
        assertEquals(0, JSON.readTree(idle.out).get("wouldApply").asInt());

        assertEquals(0, cli("store", "sandbox", "create", "right", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/a.txt", "--text=other-body\n",
                "--sandbox=right", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/side.txt", "--text=side\n",
                "--sandbox=right", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/a.txt", "--text=kept-main\n",
                "--project=" + project).code);
        byte[] kept = bytes("features/a.txt");
        Capture main = cli("store", "sandbox", "merge", "right", "--policy=main-wins", "--json",
                "--project=" + project);
        assertEquals(0, main.code, main.err + main.out);
        assertEquals("main", JSON.readTree(main.out).get("conflicts").get(0).get("resolution").asText());
        assertEquals(new String(kept, StandardCharsets.UTF_8), text("features/a.txt"));
        assertEquals("side\n", text("features/side.txt"));
        Capture again = cli("store", "sandbox", "merge", "right", "--policy=main-wins", "--json",
                "--project=" + project);
        assertEquals(0, again.code, again.err + again.out);
        assertEquals(0, JSON.readTree(again.out).get("wouldApply").asInt());
        assertEquals(0, JSON.readTree(again.out).get("applied").asInt());
        assertEquals("kept-main\n", text("features/a.txt"));

        assertEquals(0, cli("store", "sandbox", "create", "same", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/b.txt", "--text=both\n",
                "--sandbox=same", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/b.txt", "--text=both\n",
                "--project=" + project).code);
        Capture converged = cli("store", "sandbox", "merge", "same", "--json", "--project=" + project);
        assertEquals(0, converged.code, converged.err + converged.out);
        JsonNode same = JSON.readTree(converged.out);
        assertTrue(same.get("converged").asInt() >= 1, converged.out);
        assertEquals(0, same.get("conflicts").size());
        assertEquals("both\n", text("features/b.txt"));

        assertEquals(0, cli("store", "sandbox", "create", "cut", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "delete", "--path=features/b.txt", "--confirm",
                "--sandbox=cut", "--project=" + project).code);
        assertEquals(0, cli("store", "file", "put", "--path=features/b.txt", "--text=main-still\n",
                "--project=" + project).code);
        byte[] still = bytes("features/b.txt");
        Capture deleted = cli("store", "sandbox", "merge", "cut", "--project=" + project);
        assertEquals(1, deleted.code, deleted.out + deleted.err);
        assertTrue(deleted.out.contains("features/b.txt"), deleted.out);
        assertEquals(new String(still, StandardCharsets.UTF_8), text("features/b.txt"));
    }

    private void seed() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/a.txt"), "base\n");
        Files.writeString(project.resolve("features/b.txt"), "base-b\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
    }

    private String text(String path) throws Exception {
        return new String(bytes(path), StandardCharsets.UTF_8);
    }

    private byte[] bytes(String path) throws Exception {
        StoreScope.clear();
        try (var database = StoreDatabase.open(project, tools.dscode.store.sql.Binding.main())) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), path);
            return row == null || row.raw() == null ? new byte[0] : row.raw();
        }
    }

    private Path onlyReport(String name) throws Exception {
        try (Stream<Path> listing = Files.list(StorePaths.sandboxHome(project, name))) {
            return listing.filter(path -> path.getFileName().toString().startsWith("merge-")).findFirst().orElseThrow();
        }
    }

    private long reportCount(String name) throws Exception {
        try (Stream<Path> listing = Files.list(StorePaths.sandboxHome(project, name))) {
            return listing.filter(path -> path.getFileName().toString().startsWith("merge-")).count();
        }
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
