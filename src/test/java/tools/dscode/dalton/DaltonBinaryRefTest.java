package tools.dscode.dalton;

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

class DaltonBinaryRefTest {
    @TempDir
    Path project;

    @Test
    void hashOnlyStoresAPngReferenceAndExtractDoesNotRewriteIt() throws Exception {
        Files.writeString(project.resolve("dalton.properties"), "binary-policy=hash-only\n");
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        Path file = project.resolve("features/shot.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png);
        assertEquals(0, run("hydrate").code);
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            DaltonFiles.Row row = DaltonFiles.find(database.connection(), "features/shot.png");
            assertTrue(row.binaryRef());
            assertEquals(0, row.raw().length);
            assertEquals(DaltonHashes.sha256(png), row.contentHash());
            assertEquals(png.length, row.byteSize());
            assertEquals("binary-ref", row.sourceType());
        }
        DaltonExtractor.Result same = DaltonExtractor.extract(project, new DaltonExtractor.Options(java.util.List.of(), false));
        assertArrayEquals(png, Files.readAllBytes(file));
        assertTrue(same.notices().isEmpty(), same.notices().toString());
        Files.write(file, new byte[] {9, 9, 9});
        DaltonExtractor.Result changed = DaltonExtractor.extract(project, new DaltonExtractor.Options(java.util.List.of(), false));
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
        try (DaltonDatabase database = DaltonDatabase.open(project)) {
            assertNull(DaltonFiles.find(database.connection(), "features/shot.png"));
        }
    }

    private Capture run(String... args) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        tokens.add("dalton");
        tokens.addAll(java.util.List.of(args));
        tokens.add("--project=" + project);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = DaltonCli.execute(tokens.toArray(String[]::new), new java.io.PrintStream(out), new java.io.PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
