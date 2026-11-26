package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreExtractTest {
    private static final String FEATURE_PATH = "features/ordered.feature";
    private static final String OTHER_PATH = "features/other.feature";
    private static final String CONFIG_PATH = "configs/raw.properties";

    @TempDir
    Path project;

    @Test
    void losslessRoundTripKeepsCommentsTagsTablesOrderAndRawBytes() throws Exception {
        byte[] feature = feature("finished");
        byte[] config = new byte[] {'k', 'e', 'e', 'p', '=', 'y', 'e', 's', '\n', (byte) 0xFF, (byte) 0xFE};
        write(FEATURE_PATH, feature);
        write(CONFIG_PATH, config);

        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        Capture extracted = run("store", "extract", "--project=" + project);
        assertEquals(0, extracted.code, extracted.err);
        assertTrue(extracted.out.contains("written=0"), extracted.out);
        assertTrue(extracted.out.contains("skipped=2"), extracted.out);
        assertTrue(extracted.out.contains("conflicts=0"), extracted.out);
        assertTrue(extracted.out.contains("database present"), extracted.out);
        assertArrayEquals(feature, Files.readAllBytes(project.resolve(FEATURE_PATH)));
        assertArrayEquals(config, Files.readAllBytes(project.resolve(CONFIG_PATH)));
        assertFalse(row(FEATURE_PATH).edited());

        Files.delete(project.resolve(FEATURE_PATH));
        Capture deleted = run("store", "extract", "--project=" + project);
        assertEquals(0, deleted.code, deleted.err);
        assertFalse(Files.exists(project.resolve(FEATURE_PATH)));

        Path fresh = project.resolve("fresh");
        Files.createDirectories(fresh);
        Capture missing = run("store", "extract", "--project=" + fresh);
        assertEquals(0, missing.code, missing.err);
        assertTrue(missing.out.contains("written=0"), missing.out);
        assertTrue(missing.out.contains("database missing"), missing.out);
        assertFalse(Files.exists(StorePaths.database(fresh)));
    }

    @Test
    void extractReportsFileOnlyDbOnlyAndBothChanged() throws Exception {
        byte[] original = feature("finished");
        byte[] fromDb = feature("finished from db");
        byte[] fromFile = feature("finished from file");
        byte[] both = feature("finished from both");
        write(FEATURE_PATH, original);
        write(CONFIG_PATH, new byte[] {'a'});
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());

        edit(FEATURE_PATH, fromDb);
        Capture dbOnly = run("store", "extract", "--project=" + project);
        assertEquals(0, dbOnly.code, dbOnly.err);
        assertTrue(dbOnly.out.contains("written=1"), dbOnly.out);
        assertTrue(dbOnly.out.contains("conflicts=0"), dbOnly.out);
        assertArrayEquals(fromDb, Files.readAllBytes(project.resolve(FEATURE_PATH)));
        assertFalse(row(FEATURE_PATH).edited());
        assertEquals(StoreHashes.sha256(fromDb), row(FEATURE_PATH).lastSyncedHash());

        Files.write(project.resolve(FEATURE_PATH), fromFile);
        Capture fileOnly = run("store", "extract", "--project=" + project);
        assertEquals(0, fileOnly.code, fileOnly.err);
        assertTrue(fileOnly.err.isBlank(), fileOnly.err);
        assertArrayEquals(fromFile, Files.readAllBytes(project.resolve(FEATURE_PATH)));

        edit(FEATURE_PATH, both);
        Capture conflict = run("store", "extract", "--project=" + project);
        assertEquals(1, conflict.code, conflict.out);
        assertTrue(conflict.err.contains("conflict: " + FEATURE_PATH), conflict.err);
        assertTrue(conflict.out.contains("conflicts=1"), conflict.out);
        assertArrayEquals(fromFile, Files.readAllBytes(project.resolve(FEATURE_PATH)));

        Capture forced = run("store", "extract", "--force", "--project=" + project);
        assertEquals(0, forced.code, forced.err);
        assertTrue(forced.out.contains("written=2"), forced.out);
        assertTrue(forced.out.contains("conflicts=0"), forced.out);
        assertArrayEquals(both, Files.readAllBytes(project.resolve(FEATURE_PATH)));
        assertArrayEquals(new byte[] {'a'}, Files.readAllBytes(project.resolve(CONFIG_PATH)));
        assertTrue(new String(both, StandardCharsets.UTF_8).contains("# comment stays only in the raw bytes"));
        assertTrue(new String(both, StandardCharsets.UTF_8).contains("@billing @smoke"));
        assertTrue(new String(both, StandardCharsets.UTF_8).indexOf("Scenario Outline")
                < new String(both, StandardCharsets.UTF_8).indexOf("Scenario: Done"));
    }

    @Test
    void extractWithoutChooseStillRefusesBothSides() throws Exception {
        byte[] original = feature("finished");
        byte[] fromFile = feature("finished from file");
        byte[] both = feature("finished from both");
        write(FEATURE_PATH, original);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        Files.write(project.resolve(FEATURE_PATH), fromFile);
        edit(FEATURE_PATH, both);

        Capture conflict = run("store", "extract", "--project=" + project);
        assertEquals(1, conflict.code, conflict.out);
        assertTrue(conflict.err.contains("conflict: " + FEATURE_PATH), conflict.err);
        assertTrue(conflict.out.contains("conflicts=1"), conflict.out);
        assertArrayEquals(fromFile, Files.readAllBytes(project.resolve(FEATURE_PATH)));
        assertFalse(Files.isDirectory(project.resolve(".pickleball").resolve("store").resolve("discarded")));
    }

    @Test
    void extractPathLimitUsesForwardSlashes() throws Exception {
        write(FEATURE_PATH, feature("one"));
        write(OTHER_PATH, feature("two"));
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        byte[] other = Files.readAllBytes(project.resolve(OTHER_PATH));
        edit(FEATURE_PATH, feature("one from db"));
        edit(OTHER_PATH, feature("two from db"));

        Capture limited = run("store", "extract", "--path=features\\ordered.feature", "--project=" + project);
        assertEquals(0, limited.code, limited.err);
        assertTrue(limited.out.contains("written=1"), limited.out);
        assertTrue(limited.out.contains("skipped=0"), limited.out);
        assertArrayEquals(feature("one from db"), Files.readAllBytes(project.resolve(FEATURE_PATH)));
        assertArrayEquals(other, Files.readAllBytes(project.resolve(OTHER_PATH)));
        assertTrue(row(OTHER_PATH).edited());
    }

    private static byte[] feature(String closing) {
        String text = "# language: en\n"
                + "# comment stays only in the raw bytes  \r\n"
                + "@billing @smoke\n"
                + "Feature: Invoices\n"
                + "  Customers receive an invoice.\n"
                + "\n"
                + "  @setup\n"
                + "  Background: Ready\n"
                + "    Given the ledger is open\n"
                + "    \"\"\"\n"
                + "    line one\n"
                + "    \"\"\"\n"
                + "\n"
                + "  @outline\n"
                + "  Scenario Outline: Pay <name>\n"
                + "    When the amount is <amount>\n"
                + "      | field | value |\n"
                + "      | name | <name> |\n"
                + "    Then the receipt is stored\n"
                + "    Examples:\n"
                + "      | name | amount |\n"
                + "      | Ada  | 10     |\n"
                + "\n"
                + "  Scenario: Done\n"
                + "    * " + closing + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private void write(String relative, byte[] raw) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, raw);
    }

    private void edit(String relative, byte[] raw) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(database.connection(), relative);
            StoreFiles.upsert(database.connection(), new StoreFiles.Row(
                    row.path(),
                    StoreHashes.sha256(raw),
                    raw,
                    row.mtime(),
                    row.sourceType(),
                    row.lastHydratedAt(),
                    row.lastSyncedHash(),
                    true));
        }
    }

    private StoreFiles.Row row(String relative) throws Exception {
        try (StoreDatabase database = StoreDatabase.open(project)) {
            return StoreFiles.find(database.connection(), relative);
        }
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
