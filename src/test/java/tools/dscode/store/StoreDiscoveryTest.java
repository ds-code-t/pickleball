package tools.dscode.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreDiscoveryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    @Test
    void defaultsKeepFixedDirectoriesSkipBinariesAndKeepUtf8() throws Exception {
        write("features/pay.feature", "Feature: Pay\n  Scenario: One\n    Given ready\n");
        write("notes/extra.txt", "not a default directory\n");
        byte[] cafe = new byte[] {(byte) 0x63, (byte) 0x61, (byte) 0x66, (byte) 0xC3, (byte) 0xA9, (byte) 0xFF};
        Path cafePath = project.resolve("src/test/resources/configs/cafe.yaml");
        Files.createDirectories(cafePath.getParent());
        Files.write(cafePath, cafe);
        Files.write(project.resolve("features/shot.png"), new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        Files.write(project.resolve("features/noise.dat"), new byte[] {0, 1, 2, 3});
        byte[] controls = new byte[64];
        java.util.Arrays.fill(controls, (byte) 1);
        Files.write(project.resolve("features/controls.dat"), controls);

        String previous = System.getProperty("pkb_store_log");
        try {
            System.setProperty("pkb_store_log", "discover");
            Capture hydrate = run("store", "hydrate", "--project=" + project);
            assertEquals(0, hydrate.code, hydrate.err);
            assertTrue(hydrate.out.contains("inserted=2"), hydrate.out);
            assertTrue(hydrate.out.contains("ignored=3"), hydrate.out);
            assertEquals("feature", row("features/pay.feature").sourceType());
            assertArrayEquals(cafe, row("src/test/resources/configs/cafe.yaml").raw());
            assertNull(row("notes/extra.txt"));
            assertNull(row("features/shot.png"));
            assertNull(row("features/noise.dat"));
            assertNull(row("features/controls.dat"));
            String log = Files.readString(StorePaths.log(project), StandardCharsets.UTF_8);
            assertTrue(log.contains(" discover features/shot.png sandbox=main exclude **/*.png"), log);
            assertTrue(log.contains(" discover features/noise.dat sandbox=main binary null byte"), log);
            assertTrue(log.contains(" discover features/controls.dat sandbox=main binary content"), log);
        } finally {
            restore("pkb_store_log", previous);
        }

        Capture status = run("store", "status", "--project=" + project);
        assertEquals(0, status.code, status.err);
        assertTrue(status.out.contains("skipped 3"), status.out);
        assertTrue(status.out.contains("sample features/shot.png exclude **/*.png"), status.out);
        assertTrue(status.out.contains("sample features/noise.dat binary null byte"), status.out);
        Capture json = run("store", "status", "--json", "--project=" + project);
        JsonNode node = JSON.readTree(json.out);
        assertEquals(3, node.get("skipped").asInt());
        assertEquals("features/controls.dat", node.get("skipSamples").get(0).get("path").asText());
        assertEquals("binary content", node.get("skipSamples").get(0).get("reason").asText());
    }

    @Test
    void statusOnSkippedFilesDoesNotCreateDatabase() throws Exception {
        Path png = project.resolve("features/shot.png");
        Files.createDirectories(png.getParent());
        Files.write(png, new byte[] {1, 2, 3, 4});
        Capture status = run("store", "status", "--json", "--project=" + project);
        assertEquals(0, status.code, status.err);
        assertTrue(status.out.contains("\"database\":\"missing\""), status.out);
        JsonNode node = JSON.readTree(status.out);
        assertEquals(1, node.get("skipped").asInt());
        assertEquals("features/shot.png", node.get("skipSamples").get(0).get("path").asText());
        assertTrue(node.get("skipSamples").get(0).get("reason").asText().contains("png"), node.toString());
        assertFalse(Files.exists(StorePaths.database(project)));
        assertFalse(Files.exists(StorePaths.root(project)));
    }

    @Test
    void includeExcludeAndSizeApplyUnlessPathIsForced() throws Exception {
        write("store.properties", """
                include=features/**
                exclude=**/hold/**
                max-file-size=40
                """);
        write("features/yes.feature", "Feature: Yes\n");
        write("features/hold/no.feature", "Feature: No\n");
        write("src/test/resources/features/other.feature", "Feature: Other\n");
        write("features/big.feature", "Feature: Big\n  Scenario: Wide\n    Given " + "x".repeat(80) + "\n");

        Capture hydrate = run("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrate.code, hydrate.err);
        assertTrue(hydrate.out.contains("inserted=1"), hydrate.out);
        assertTrue(hydrate.out.contains("ignored=2"), hydrate.out);
        assertEquals("feature", row("features/yes.feature").sourceType());
        assertNull(row("features/hold/no.feature"));
        assertNull(row("src/test/resources/features/other.feature"));
        assertNull(row("features/big.feature"));

        Capture limited = run("store", "hydrate", "--path=features/big.feature", "--project=" + project);
        assertEquals(0, limited.code, limited.err);
        assertTrue(limited.out.contains("ignored=1"), limited.out);
        assertTrue(limited.out.contains("inserted=0"), limited.out);
        assertNull(row("features/big.feature"));

        Capture forced = run("store", "hydrate", "--path=features/big.feature", "--force", "--project=" + project);
        assertEquals(0, forced.code, forced.err);
        assertTrue(forced.out.contains("ignored=0"), forced.out);
        assertTrue(forced.out.contains("inserted=1"), forced.out);
        assertEquals("feature", row("features/big.feature").sourceType());

        Capture broadForce = run("store", "hydrate", "--force", "--project=" + project);
        assertEquals(0, broadForce.code, broadForce.err);
        assertTrue(broadForce.out.contains("ignored=2"), broadForce.out);
        assertNull(row("features/hold/no.feature"));
    }

    @Test
    void systemPropertySizeBeatsTheFileAndUnknownPolicySkips() throws Exception {
        write("store.properties", "max-file-size=1048576\n");
        write("features/big.feature", "Feature: Big\n  Scenario: Wide\n    Given " + "x".repeat(80) + "\n");
        String previous = System.getProperty("pkb_store_max_file_size");
        try {
            System.setProperty("pkb_store_max_file_size", "30");
            Capture hydrate = run("store", "hydrate", "--project=" + project);
            assertEquals(0, hydrate.code, hydrate.err);
            assertTrue(hydrate.out.contains("ignored=1"), hydrate.out);
            assertNull(row("features/big.feature"));
            Capture status = run("store", "status", "--project=" + project);
            assertTrue(status.out.contains("sample features/big.feature size "), status.out);
        } finally {
            restore("pkb_store_max_file_size", previous);
        }

        write("store.properties", "binary-policy=include\n");
        Files.write(project.resolve("features/noise.dat"), new byte[] {0, 9});
        Capture included = run("store", "hydrate", "--project=" + project);
        assertEquals(0, included.code, included.out + included.err);
        StoreFiles.Row noise = row("features/noise.dat");
        assertTrue(noise != null && !noise.binaryRef(), String.valueOf(noise));
        assertEquals(2, noise.raw().length);

        write("store.properties", "binary-policy=hash-only\n");
        Capture hashOnly = run("store", "status", "--project=" + project);
        assertTrue(hashOnly.out.contains("binary-policy hash-only is not supported"), hashOnly.out);

        write("store.properties", "binary-policy=keep\n");
        Capture unknown = run("store", "status", "--project=" + project);
        assertTrue(unknown.out.contains("binary-policy keep is not supported"), unknown.out);
    }

    private StoreFiles.Row row(String path) throws Exception {
        if (!StorePaths.ready(project)) return null;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            return StoreFiles.find(database.connection(), path);
        }
    }

    private void write(String relative, String text) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void restore(String key, String previous) {
        if (previous == null) System.clearProperty(key);
        else System.setProperty(key, previous);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }

    private record Capture(int code, String out, String err) {
    }
}
