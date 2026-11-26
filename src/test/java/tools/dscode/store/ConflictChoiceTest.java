package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConflictChoiceTest {
    private static final String FEATURE = "features/pay.feature";
    private static final String CONFIG = "configs/app.properties";
    private static final Instant START = Instant.parse("2026-11-26T08:11:26.356Z");

    @TempDir
    Path project;

    @BeforeEach
    void isolateScope() {
        StoreScope.clear();
        StoreConflictChoice.clock(Clock.fixed(START, ZoneOffset.UTC));
    }

    @AfterEach
    void resetScope() {
        StoreScope.clear();
        StoreConflictChoice.clock(Clock.systemUTC());
        System.clearProperty("pkb_store_log");
    }

    @Test
    void keepFileShowsBothBodiesAndExpiresAfterTwentyFiveHours() throws Exception {
        byte[] fileSide = feature("FileSide", "FromFile", "the file side");
        byte[] dbSide = feature("DbSide", "FromDb", "the database side");
        both(FEATURE, feature("Original", "Original", "the original"), dbSide, fileSide);
        System.setProperty("pkb_store_log", "conflict");

        Capture shown = run("store", "conflicts", "show", FEATURE, "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.contains("the file side"), shown.out);
        assertTrue(shown.out.contains("the database side"), shown.out);

        Capture json = run("store", "conflicts", "show", FEATURE, "--json", "--project=" + project);
        assertEquals(0, json.code, json.err);
        assertTrue(json.out.contains("the file side"), json.out);
        assertTrue(json.out.contains("the database side"), json.out);

        Capture chosen = run("store", "conflicts", "choose", FEATURE, "--keep=file", "--project=" + project);
        assertEquals(0, chosen.code, chosen.err);
        assertTrue(chosen.out.contains("keep=file"), chosen.out);
        assertTrue(chosen.out.contains("discarded="), chosen.out);

        StoreFiles.Row row = row(FEATURE);
        assertArrayEquals(fileSide, row.raw());
        assertFalse(row.edited());
        assertEquals(StoreHashes.sha256(fileSide), row.lastSyncedHash());
        assertEquals(StoreHashes.sha256(fileSide), row.contentHash());
        assertArrayEquals(fileSide, Files.readAllBytes(project.resolve(FEATURE)));
        assertTrue(scenarioNames(FEATURE).contains("FromFile"), scenarioNames(FEATURE));
        assertFalse(scenarioNames(FEATURE).contains("FromDb"), scenarioNames(FEATURE));

        List<Path> held = discardBodies();
        assertEquals(1, held.size());
        String discarded = Files.readString(held.get(0));
        assertTrue(discarded.contains("the database side"), discarded);
        assertTrue(Files.isRegularFile(Path.of(held.get(0).toString() + ".start")));

        String log = Files.readString(project.resolve(".pickleball").resolve("store").resolve("store.log"));
        assertTrue(log.contains("conflict"), log);
        assertTrue(log.contains(FEATURE), log);
        assertTrue(log.contains("keep=file"), log);
        assertTrue(log.contains("discarded="), log);
        assertTrue(log.contains("sandbox=main"), log);
        assertFalse(log.contains("the database side"), log);

        StoreConflictChoice.sweep(project);
        assertEquals(1, discardBodies().size());

        StoreConflictChoice.clock(Clock.fixed(START.plus(java.time.Duration.ofHours(25)), ZoneOffset.UTC));
        StoreConflictChoice.sweep(project);
        assertEquals(0, discardBodies().size());
    }

    @Test
    void keepDbWritesDatabaseBytesAndClearsTheEdit() throws Exception {
        byte[] fileSide = feature("FileSide", "FromFile", "the file side");
        byte[] dbSide = feature("DbSide", "FromDb", "the database side");
        both(FEATURE, feature("Original", "Original", "the original"), dbSide, fileSide);

        Capture chosen = run("store", "conflicts", "choose", FEATURE, "--keep=db", "--json", "--project=" + project);
        assertEquals(0, chosen.code, chosen.err);
        assertTrue(chosen.out.contains("\"keep\":\"db\""), chosen.out);

        assertArrayEquals(dbSide, Files.readAllBytes(project.resolve(FEATURE)));
        StoreFiles.Row row = row(FEATURE);
        assertArrayEquals(dbSide, row.raw());
        assertFalse(row.edited());
        assertEquals(StoreHashes.sha256(dbSide), row.lastSyncedHash());

        String discarded = Files.readString(discardBodies().get(0));
        assertTrue(discarded.contains("the file side"), discarded);
        assertFalse(discarded.contains("the database side"), discarded);
    }

    @Test
    void secretReferenceIsShownAndDiscardedWithoutCleartext() throws Exception {
        String fileSide = "password=<password:qa>\ntoken=<token:qa>\nside=file\n";
        String dbSide = "password=<password:qa>\ntoken=hunter2-clear\nside=database\n";
        both(CONFIG, "password=<password:qa>\nside=original\n".getBytes(StandardCharsets.UTF_8),
                dbSide.getBytes(StandardCharsets.UTF_8), fileSide.getBytes(StandardCharsets.UTF_8));

        Capture shown = run("store", "conflicts", "show", CONFIG, "--project=" + project);
        assertEquals(0, shown.code, shown.err);
        assertTrue(shown.out.contains("<password:qa>"), shown.out);
        assertTrue(shown.out.contains("side=file"), shown.out);
        assertTrue(shown.out.contains("side=database"), shown.out);
        assertTrue(shown.out.contains("~MASK;"), shown.out);
        assertFalse(shown.out.contains("hunter2-clear"), shown.out);

        Capture chosen = run("store", "conflicts", "choose", CONFIG, "--keep=file", "--project=" + project);
        assertEquals(0, chosen.code, chosen.err);
        String discarded = Files.readString(discardBodies().get(0));
        assertTrue(discarded.contains("<password:qa>"), discarded);
        assertTrue(discarded.contains("side=database"), discarded);
        assertTrue(discarded.contains("~MASK;"), discarded);
        assertFalse(discarded.contains("hunter2-clear"), discarded);
        assertArrayEquals(fileSide.getBytes(StandardCharsets.UTF_8), row(CONFIG).raw());
    }

    @Test
    void previousJvmMarkerIsDeleted() throws Exception {
        byte[] fileSide = feature("FileSide", "FromFile", "the file side");
        byte[] dbSide = feature("DbSide", "FromDb", "the database side");
        both(FEATURE, feature("Original", "Original", "the original"), dbSide, fileSide);
        Capture chosen = run("store", "conflicts", "choose", FEATURE, "--keep=file", "--project=" + project);
        assertEquals(0, chosen.code, chosen.err);
        assertEquals(1, discardBodies().size());

        StoreConflictChoice.newProcess(project);
        assertEquals(0, discardBodies().size());
        assertTrue(Files.isRegularFile(StoreConflictChoice.directory(project).resolve("session")));
    }

    @Test
    void missingKeepAndMissingConflictExit() throws Exception {
        Capture missingKeep = run("store", "conflicts", "choose", FEATURE, "--project=" + project);
        assertEquals(2, missingKeep.code, missingKeep.out);
        assertTrue(missingKeep.err.contains("--keep=db|file"), missingKeep.err);

        Capture missingShow = run("store", "conflicts", "show", "--project=" + project);
        assertEquals(2, missingShow.code, missingShow.out);

        byte[] original = feature("Original", "Original", "the original");
        Path file = project.resolve(FEATURE);
        Files.createDirectories(file.getParent());
        Files.write(file, original);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        Capture none = run("store", "conflicts", "choose", FEATURE, "--keep=file", "--project=" + project);
        assertEquals(1, none.code, none.out);
        assertTrue(none.err.contains("no both-sides conflict"), none.err);
        assertEquals(0, discardBodies().size());
    }

    private void both(String relative, byte[] original, byte[] database, byte[] file) throws Exception {
        Path path = project.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, original);
        StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        try (StoreDatabase opened = StoreDatabase.open(project)) {
            StoreFiles.Row row = StoreFiles.find(opened.connection(), relative);
            StoreFiles.upsert(opened.connection(), new StoreFiles.Row(
                    row.path(),
                    StoreHashes.sha256(database),
                    database,
                    row.mtime(),
                    row.sourceType(),
                    row.lastHydratedAt(),
                    row.lastSyncedHash(),
                    true));
        }
        Files.write(path, file);
    }

    private StoreFiles.Row row(String relative) throws Exception {
        try (StoreDatabase opened = StoreDatabase.open(project)) {
            return StoreFiles.find(opened.connection(), relative);
        }
    }

    private String scenarioNames(String relative) throws Exception {
        try (StoreDatabase opened = StoreDatabase.open(project);
             PreparedStatement statement = opened.connection().prepareStatement(
                     "SELECT name FROM scenarios WHERE file_path = ?")) {
            statement.setString(1, relative);
            try (ResultSet rows = statement.executeQuery()) {
                StringBuilder names = new StringBuilder();
                while (rows.next()) names.append(rows.getString(1)).append('\n');
                return names.toString();
            }
        }
    }

    private List<Path> discardBodies() throws Exception {
        Path dir = StoreConflictChoice.directory(project);
        if (!Files.isDirectory(dir)) return List.of();
        List<Path> found = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && !"session".equals(name) && !name.endsWith(".start")) found.add(file);
            }
        }
        return found;
    }

    private static byte[] feature(String featureName, String scenario, String step) {
        String text = "Feature: " + featureName + "\n"
                + "  Scenario: " + scenario + "\n"
                + "    Given " + step + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
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
