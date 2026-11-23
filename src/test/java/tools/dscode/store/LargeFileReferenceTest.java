package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Large binaries are references only when they match the Git blob. */
class LargeFileReferenceTest {
    @TempDir
    Path project;

    @Test
    void referenceOnlyWhenTheBytesEqualGit() throws Exception {
        properties("binary-policy=include\nlarge-file-bytes=32\n");
        byte[] dirty = bytes(64, (byte) 3);
        byte[] clean = bytes(64, (byte) 4);
        write("features/dirty.bin", dirty);
        write("features/clean.bin", clean);
        commit("features/clean.bin");
        Capture hydrate = run("hydrate");
        assertEquals(0, hydrate.code, hydrate.out + hydrate.err);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row owned = StoreFiles.find(database.connection(), "features/dirty.bin");
            assertFalse(owned.binaryRef());
            assertArrayEquals(dirty, owned.raw());
            assertEquals("db", owned.location());
            assertEquals("", owned.gitCommit());
            StoreFiles.Row ref = StoreFiles.find(database.connection(), "features/clean.bin");
            assertTrue(ref.binaryRef(), ref.toString());
            assertEquals(0, ref.raw().length);
            assertEquals(clean.length, ref.byteSize());
            assertEquals("features/clean.bin", ref.location());
            assertEquals(StoreHashes.gitBlob(clean), ref.gitBlob());
            assertFalse(ref.gitCommit().isBlank());
        }
    }

    @Test
    void dirtyLargeFileIsStored() throws Exception {
        properties("binary-policy=include\nlarge-file-bytes=32\n");
        byte[] body = bytes(80, (byte) 4);
        Path file = write("features/shot.bin", body);
        commit("features/shot.bin");
        byte[] dirty = bytes(80, (byte) 5);
        Files.write(file, dirty);
        assertEquals(0, run("hydrate").code);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.bin");
            assertFalse(row.binaryRef());
            assertArrayEquals(dirty, row.raw());
            assertEquals("db", row.location());
            assertEquals("", row.gitCommit());
            assertEquals("binary", row.sourceType());
        }
    }

    @Test
    void hashMismatchOnDiskFallsBackToGit() throws Exception {
        properties("binary-policy=include\nlarge-file-bytes=0\n");
        byte[] body = bytes(48, (byte) 6);
        Path file = write("features/shot.bin", body);
        commit("features/shot.bin");
        assertEquals(0, run("hydrate").code);
        Files.write(file, bytes(48, (byte) 9));
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.bin");
            assertArrayEquals(body, StoreRefs.read(project, row));
        }
    }

    @Test
    void bothMissingFailsWithFix() throws Exception {
        properties("binary-policy=include\nlarge-file-bytes=0\n");
        byte[] body = bytes(40, (byte) 8);
        write("features/shot.bin", body);
        commit("features/shot.bin");
        assertEquals(0, run("hydrate").code);
        Files.delete(project.resolve("features/shot.bin"));
        deleteTree(project.resolve(".git"));
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.bin");
            StoreRefs.Unavailable failure = assertThrows(StoreRefs.Unavailable.class, () -> StoreRefs.read(project, row));
            assertTrue(failure.getMessage().contains("large file features/shot.bin unavailable"), failure.getMessage());
            assertTrue(failure.fix().startsWith("fix:"), failure.fix());
        }
        Capture show = run("files", "show", "features/shot.bin");
        assertEquals(1, show.code, show.out);
        assertTrue(show.err.contains("error: large file features/shot.bin unavailable"), show.err);
        assertTrue(show.err.contains("fix:"), show.err);
    }

    @Test
    void maxFileSizeExemptsAReference() throws Exception {
        properties("binary-policy=include\nlarge-file-bytes=0\nmax-file-size=50\n");
        byte[] kept = bytes(80, (byte) 2);
        write("features/kept.bin", kept);
        commit("features/kept.bin");
        write("features/dirty.bin", bytes(80, (byte) 1));
        Capture hydrate = run("hydrate");
        assertEquals(0, hydrate.code, hydrate.out + hydrate.err);
        assertTrue(hydrate.out.contains("ignored=1"), hydrate.out);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/kept.bin");
            assertTrue(row.binaryRef());
            assertEquals(0, row.raw().length);
            assertNull(StoreFiles.find(database.connection(), "features/dirty.bin"));
        }
        Capture status = run("status");
        assertTrue(status.out.contains("refs=1 owned-large=0"), status.out);
    }

    @Test
    void refsLoggingIsOffByDefaultDateStampedAndCapped() throws Exception {
        properties("binary-policy=include\nlarge-file-bytes=0\n");
        write("features/shot.bin", bytes(40, (byte) 7));
        commit("features/shot.bin");
        String previous = System.getProperty("pkb_store_log");
        String previousMax = System.getProperty("pkb_store_log_max_bytes");
        try {
            System.clearProperty("pkb_store_log");
            assertEquals(0, run("hydrate").code);
            assertFalse(Files.exists(StorePaths.log(project)));
            deleteTree(project.resolve(".pickleball"));
            System.setProperty("pkb_store_log", "refs");
            assertEquals(0, run("hydrate").code);
            String text = Files.readString(StorePaths.log(project), StandardCharsets.UTF_8);
            assertTrue(text.contains(" refs "), text);
            assertTrue(text.contains("reference bytes="), text);
            assertTrue(text.charAt(0) == '2', text);
            System.setProperty("pkb_store_log_max_bytes", "80");
            StoreLog.log(project, "refs", "features/shot.bin", "x".repeat(200));
            assertTrue(Files.size(StorePaths.log(project)) <= 80);
            System.setProperty("pkb_store_log", "off");
            long size = Files.size(StorePaths.log(project));
            StoreLog.log(project, "refs", "features/shot.bin", "again");
            assertEquals(size, Files.size(StorePaths.log(project)));
        } finally {
            restore("pkb_store_log", previous);
            restore("pkb_store_log_max_bytes", previousMax);
        }
    }

    private void properties(String text) throws Exception {
        Files.writeString(project.resolve("store.properties"), text);
    }

    private Path write(String relative, byte[] body) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, body);
        return file;
    }

    private void commit(String relative) throws Exception {
        if (!Files.isDirectory(project.resolve(".git"))) {
            git("init");
            git("config", "user.email", "dev@example.com");
            git("config", "user.name", "dev");
            git("config", "commit.gpgsign", "false");
        }
        git("add", relative);
        git("commit", "-m", "add " + relative);
    }

    private static byte[] bytes(int size, byte fill) {
        byte[] body = new byte[size];
        java.util.Arrays.fill(body, fill);
        body[0] = 0;
        return body;
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

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try {
            removeTree(root);
        } catch (java.nio.file.AccessDeniedException denied) {
            if (root.getFileName() == null || !".git".equals(root.getFileName().toString())) throw denied;
            Path objects = root.resolve("objects");
            Files.createDirectories(objects);
            try (var walk = Files.walk(objects)) {
                for (Path path : walk.filter(Files::isRegularFile).toList()) {
                    clearReadonly(path);
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** Windows git marks object files read-only. Clearing that bit is a no-op when the volume has no DOS view. */
    private static void removeTree(Path root) throws java.io.IOException {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                clearReadonly(path);
                Files.deleteIfExists(path);
            }
        }
    }

    private static void clearReadonly(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", false);
        } catch (Exception ignored) {
            // Not a DOS volume, or the file is already gone.
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private record Capture(int code, String out, String err) {
    }
}
