package tools.dscode.dolt;

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

class DoltBinaryRefTest {
    @TempDir
    Path project;

    @Test
    void hashOnlyStoresAPngReferenceAndExtractDoesNotRewriteIt() throws Exception {
        Files.writeString(project.resolve("dolt.properties"), "binary-policy=hash-only\n");
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        Path file = project.resolve("features/shot.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        assertEquals(0, run("hydrate").code);
        try (DoltDatabase database = DoltDatabase.open(project)) {
            DoltFiles.Row row = DoltFiles.find(database.connection(), "features/shot.png");
            assertTrue(row.binaryRef());
            assertEquals(0, row.raw().length);
            assertEquals(DoltHashes.sha256(png), row.contentHash());
            assertEquals(png.length, row.byteSize());
            assertEquals("binary-ref", row.sourceType());
        }
        DoltExtractor.Result same = DoltExtractor.extract(project, new DoltExtractor.Options(java.util.List.of(), false));
        assertArrayEquals(png, Files.readAllBytes(file));
        assertTrue(same.notices().isEmpty(), same.notices().toString());
        Files.write(file, new byte[] {9, 9, 9});
        DoltExtractor.Result changed = DoltExtractor.extract(project, new DoltExtractor.Options(java.util.List.of(), false));
        assertTrue(changed.line().contains("binary_ref_changed features/shot.png"), changed.line());
        assertArrayEquals(new byte[] {9, 9, 9}, Files.readAllBytes(file));
    }

    @Test
    void skipDefaultStillIgnoresAPng() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47};
        Path file = project.resolve("features/shot.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        Files.writeString(project.resolve("features/one.feature"), "Feature: One\n  Scenario: A\n    Given ready\n", StandardCharsets.UTF_8);
        Capture hydrate = run("hydrate");
        assertEquals(0, hydrate.code, hydrate.out + hydrate.err);
        assertTrue(hydrate.out.contains("ignored=1"), hydrate.out);
        try (DoltDatabase database = DoltDatabase.open(project)) {
            assertNull(DoltFiles.find(database.connection(), "features/shot.png"));
        }
    }

    private Capture run(String... args) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        tokens.add("dolt");
        tokens.addAll(java.util.List.of(args));
        tokens.add("--project=" + project);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = DoltCli.execute(tokens.toArray(String[]::new), new java.io.PrintStream(out), new java.io.PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
