package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;
import tools.dscode.store.sql.SqlStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A sandbox edit of a referenced binary copies bytes into that sandbox only. */
class LargeFileBranchCopyTest {
    @TempDir
    Path project;

    @AfterEach
    void clearScope() {
        StoreScope.clear();
    }

    @Test
    void sandboxChangeCopiesBytesIntoThatSandboxOnly() throws Exception {
        Files.writeString(project.resolve("store.properties"), "binary-policy=include\n");
        byte[] body = new byte[5 * 1024 * 1024];
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
        Capture hydrate = run("hydrate");
        assertEquals(0, hydrate.code, hydrate.out + hydrate.err);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.bin");
            assertTrue(row.binaryRef());
            assertEquals(0, row.raw().length);
            assertEquals(body.length, row.byteSize());
        }
        assertTrue(storeBytes(StorePaths.mainDatabase(project)) < 2_000_000L, "main stays small");
        try (SqlStore store = SqlStore.open(project, Binding.main())) {
            store.createBranch("agent");
        }
        Path sandbox = StorePaths.sandboxDatabase(project, "agent");
        long sandboxBytes = storeBytes(sandbox);
        assertTrue(sandboxBytes < 2_000_000L, "sandbox copy " + sandboxBytes);
        byte[] changed = new byte[] {1, 2, 3, 4, 5};
        StoreScope.bind(Binding.sandbox("agent"));
        StoreContent.put(project, "features/shot.bin", changed);
        try (StoreDatabase database = StoreDatabase.open(project, Binding.sandbox("agent"))) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.bin");
            assertFalse(row.binaryRef());
            assertEquals("db", row.location());
            assertEquals("", row.gitCommit());
            assertArrayEquals(changed, row.raw());
        }
        try (StoreDatabase database = StoreDatabase.open(project, Binding.main())) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.bin");
            assertTrue(row.binaryRef());
            assertEquals(0, row.raw().length);
        }
        assertArrayEquals(body, Files.readAllBytes(file));
        assertTrue(storeBytes(StorePaths.mainDatabase(project)) < 2_000_000L);
    }

    private static long storeBytes(Path database) throws Exception {
        long total = Files.size(database);
        for (String suffix : new String[] {"-wal", "-shm"}) {
            Path extra = Path.of(database.toString() + suffix);
            if (Files.isRegularFile(extra)) total += Files.size(extra);
        }
        return total;
    }

    private void git(String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(project.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) throw new AssertionError(code + " " + output);
    }

    private Capture run(String... args) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        tokens.add("store");
        tokens.addAll(java.util.List.of(args));
        tokens.add("--project=" + project);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = StoreCli.execute(tokens.toArray(String[]::new), new java.io.PrintStream(out), new java.io.PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
