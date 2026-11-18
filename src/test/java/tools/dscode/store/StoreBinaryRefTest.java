package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreBinaryRefTest {
    @TempDir
    Path project;

    @Test
    void hashOnlyStoresAPngReferenceAndExtractDoesNotRewriteIt() throws Exception {
        Files.writeString(project.resolve("store.properties"), "binary-policy=include\nlarge-file-bytes=0\n");
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        Path file = project.resolve("features/shot.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        git(project, "init");
        git(project, "config", "user.email", "dev@example.com");
        git(project, "config", "user.name", "dev");
        git(project, "config", "commit.gpgsign", "false");
        git(project, "add", "features/shot.png");
        git(project, "commit", "-m", "add png");
        assertEquals(0, run("hydrate").code);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.png");
            assertTrue(row.binaryRef());
            assertEquals(0, row.raw().length);
            assertEquals(StoreHashes.sha256(png), row.contentHash());
            assertEquals(png.length, row.byteSize());
            assertEquals("binary-ref", row.sourceType());
            assertEquals("features/shot.png", row.location());
            assertFalse(row.gitCommit().isBlank());
        }
        StoreExtractor.Result same = StoreExtractor.extract(project, new StoreExtractor.Options(java.util.List.of(), false));
        assertArrayEquals(png, Files.readAllBytes(file));
        assertTrue(same.notices().isEmpty(), same.notices().toString());
        Files.write(file, new byte[] {9, 9, 9});
        StoreExtractor.Result changed = StoreExtractor.extract(project, new StoreExtractor.Options(java.util.List.of(), false));
        assertTrue(changed.line().contains("binary_ref_changed features/shot.png"), changed.line());
        assertArrayEquals(new byte[] {9, 9, 9}, Files.readAllBytes(file));
    }

    @Test
    void belowThresholdBinaryIsStoredInline() throws Exception {
        Files.writeString(project.resolve("store.properties"), "binary-policy=include\nlarge-file-bytes=1000\n");
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 1, 2, 3, 4};
        Path file = project.resolve("features/shot.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        assertEquals(0, run("hydrate").code);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.png");
            assertFalse(row.binaryRef());
            assertArrayEquals(png, row.raw());
            assertEquals("db", row.location());
            assertEquals("", row.gitCommit());
        }
    }

    @Test
    void defaultIncludeStoresAPngInline() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        Path file = project.resolve("features/shot.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        Files.writeString(project.resolve("features/one.feature"), "Feature: One\n  Scenario: A\n    Given ready\n", StandardCharsets.UTF_8);
        Capture hydrate = run("hydrate");
        assertEquals(0, hydrate.code, hydrate.out + hydrate.err);
        assertTrue(hydrate.out.contains("ignored=0"), hydrate.out);
        assertTrue(hydrate.out.contains("inserted=2"), hydrate.out);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), "features/shot.png");
            assertFalse(row.binaryRef());
            assertArrayEquals(png, row.raw());
            assertEquals("db", row.location());
            assertEquals("binary", row.sourceType());
            assertEquals("feature", StoreFiles.find(database.connection(), "features/one.feature").sourceType());
        }
    }

    @Test
    void explicitSkipStillIgnoresAPng() throws Exception {
        Files.writeString(project.resolve("store.properties"), "binary-policy=skip\n");
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        Path file = project.resolve("features/shot.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        Files.writeString(project.resolve("features/one.feature"), "Feature: One\n  Scenario: A\n    Given ready\n", StandardCharsets.UTF_8);
        Capture hydrate = run("hydrate");
        assertEquals(0, hydrate.code, hydrate.out + hydrate.err);
        assertTrue(hydrate.out.contains("ignored=1"), hydrate.out);
        try (StoreDatabase database = StoreDatabase.open(project)) {
            assertNull(StoreFiles.find(database.connection(), "features/shot.png"));
            assertEquals("feature", StoreFiles.find(database.connection(), "features/one.feature").sourceType());
        }
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

    private static void git(Path dir, String... args) throws Exception {
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(java.util.List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(dir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = process.waitFor();
        if (code != 0) throw new AssertionError(code + " " + output);
    }
}
