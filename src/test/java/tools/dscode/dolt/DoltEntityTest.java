package tools.dscode.dolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.mappings.MappingProcessor;
import tools.dscode.common.mappings.ParsingMap;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltEntityTest {
    private static final String PAYLOAD = "{\"password\":\"<password:qaDb>\",\"name\":\"Ada\"}";
    private static final String CANONICAL = "{\"name\":\"Ada\",\"password\":\"<password:qaDb>\"}";

    @TempDir
    Path project;

    @Test
    void crudAndFileRoundTripKeepTheOriginalBytesUntilADatabaseEdit() throws Exception {
        String original = """
                # keep me
                type: Account
                id: ada
                version: 1
                payload: {"password":"<password:qaDb>","name":"Ada"}
                """;
        Path file = project.resolve("dolt-entities/Account/ada.entity");
        Files.createDirectories(file.getParent());
        byte[] raw = original.getBytes(StandardCharsets.UTF_8);
        Files.write(file, raw);

        DoltHydrator.Result hydrated = DoltHydrator.hydrate(project, DoltHydrator.Options.full());
        assertEquals(1, hydrated.inserted());
        DoltEntities.Entity stored = DoltEntities.get(project, "Account", "ada");
        assertEquals(CANONICAL, stored.payloadJson());
        assertEquals("[\"<password:qaDb>\"]", stored.secretRefs());
        assertEquals("dolt-entities/Account/ada.entity", stored.sourceFile());
        assertArrayEquals(raw, row().raw());

        Capture exported = run("dolt", "entity", "export", "--project=" + project);
        assertEquals(0, exported.code, exported.err);
        assertTrue(exported.out.contains("written=0"), exported.out);
        assertTrue(exported.out.contains("skipped=1"), exported.out);
        assertArrayEquals(raw, Files.readAllBytes(file));

        Capture put = run("dolt", "entity", "put", "Account", "ada", "--version=2",
                "--payload=" + PAYLOAD, "--project=" + project);
        assertEquals(0, put.code, put.err);
        assertTrue(put.out.contains("version 2"), put.out);
        Capture again = run("dolt", "entity", "export", "--project=" + project);
        assertEquals(0, again.code, again.err);
        assertTrue(again.out.contains("written=1"), again.out);
        byte[] written = DoltEntities.canonicalFile("Account", "ada", 2, CANONICAL);
        assertArrayEquals(written, Files.readAllBytes(file));
        Capture stable = run("dolt", "entity", "export", "--project=" + project);
        assertEquals(0, stable.code, stable.err);
        assertTrue(stable.out.contains("skipped=1"), stable.out);
        assertArrayEquals(written, Files.readAllBytes(file));

        Files.writeString(file, original.replace("Ada", "Changed"), StandardCharsets.UTF_8);
        DoltEntities.put(project, "Account", "ada", 3, "{\"name\":\"FromDb\",\"password\":\"<password:qaDb>\"}");
        Capture conflict = run("dolt", "entity", "export", "--project=" + project);
        assertEquals(1, conflict.code, conflict.out);
        assertTrue(conflict.err.contains("conflict: dolt-entities/Account/ada.entity"), conflict.err);
        assertTrue(Files.readString(file).contains("Changed"));

        Capture forced = run("dolt", "entity", "export", "--force", "--project=" + project);
        assertEquals(0, forced.code, forced.err);
        assertTrue(Files.readString(file).contains("FromDb"));
        assertFalse(Files.readString(file).contains("hunter2"));

        Capture list = run("dolt", "entity", "list", "--project=" + project);
        assertTrue(list.out.contains("Account ada 3"), list.out);
        Capture removed = run("dolt", "entity", "remove", "Account", "ada", "--project=" + project);
        assertEquals(0, removed.code, removed.err);
        assertFalse(Files.exists(file));
        assertEquals("entities 0\n", run("dolt", "entity", "list", "--project=" + project).out);
    }

    @Test
    void cleartextSecretsAreRejectedAndNotStored() throws Exception {
        IllegalArgumentException put = assertThrows(IllegalArgumentException.class, () ->
                DoltEntities.put(project, "Account", "ada", 1, "{\"password\":\"hunter2\"}"));
        assertTrue(put.getMessage().contains("secret reference"), put.getMessage());
        if (DoltPaths.ready(project)) {
            assertFalse(DoltDatabase.storedText(project).contains("hunter2"));
        }

        Path file = project.resolve("dolt-entities/Account/secret.entity");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                type: Account
                id: secret
                version: 1
                payload: {"token":"hunter2"}
                """);
        IllegalArgumentException hydrate = assertThrows(IllegalArgumentException.class, () ->
                DoltHydrator.hydrate(project, new DoltHydrator.Options(java.util.List.of("dolt-entities"), false, false)));
        assertTrue(hydrate.getMessage().contains("secret reference"), hydrate.getMessage());
        try (DoltDatabase database = DoltDatabase.open(project)) {
            try (var statement = database.connection().createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM entities")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
        assertFalse(DoltDatabase.storedText(project).contains("hunter2"));
    }

    @Test
    void entityReferenceResolvesOnlyWhenDoltIsOn() throws Exception {
        Path file = project.resolve("dolt-entities/Account/ada.entity");
        Files.createDirectories(file.getParent());
        Files.write(file, DoltEntities.canonicalFile("Account", "ada", 1, CANONICAL));
        String previous = System.getProperty("pkb_dolt");
        String previousProject = System.getProperty("pkb_dolt_project");
        MappingProcessor.resetCommonMaps();
        ParsingMap map = new ParsingMap();
        map.put("kept", "Nia");
        try {
            System.setProperty("pkb_dolt", "false");
            System.setProperty("pkb_dolt_project", project.toString());
            assertEquals("<entity:Account#ada.name>", map.resolveWholeText("<entity:Account#ada.name>"));
            assertEquals("Nia", map.resolveWholeText("<kept>"));
            assertFalse(Files.exists(DoltPaths.database(project)));

            System.setProperty("pkb_dolt", "true");
            assertEquals("Ada", DoltEntities.lookup("entity:Account#ada.name"));
            assertEquals("Ada", map.resolveWholeText("<entity:Account#ada.name>"));
            assertEquals("user Ada is ready", map.resolveWholeText("user <entity:Account#ada.name> is ready"));
            assertEquals("Nia", map.resolveWholeText("<kept>"));
            assertTrue(String.valueOf(map.resolveWholeValue("<entity:Account#ada>")).contains("\"name\":\"Ada\""));
            assertFalse(Files.exists(DoltPaths.database(project)));
        } finally {
            if (previous == null) System.clearProperty("pkb_dolt");
            else System.setProperty("pkb_dolt", previous);
            if (previousProject == null) System.clearProperty("pkb_dolt_project");
            else System.setProperty("pkb_dolt_project", previousProject);
        }
    }

    @Test
    void entityReferenceInAScenarioReachesTheStepOnlyWhenDoltIsOn() throws Exception {
        Path features = project.resolve("features");
        Path entity = project.resolve("dolt-entities/Account/ada.entity");
        Files.createDirectories(features);
        Files.createDirectories(entity.getParent());
        Files.write(entity, DoltEntities.canonicalFile("Account", "ada", 1, CANONICAL));
        Files.writeString(features.resolve("account.feature"), """
                Feature: Dolt entity reference
                  @dolt-entity
                  Scenario: Account fields reach the step
                    * INFO: account <entity:Account#ada.name>
                    * dolt probe records <entity:Account#ada.name>
                    * dolt probe records <entity:Account#ada.password>
                """);
        Properties before = new Properties();
        before.putAll(System.getProperties());
        MappingProcessor.resetCommonMaps();
        try {
            System.setProperty("pkb_dolt", "false");
            System.setProperty("pkb_dolt_project", project.toString());
            DoltRuntime.reset();
            DoltScenarioHarness.Outcome off = runEntity();
            assertTrue(off.tests().stream().allMatch(row -> row.startsWith("SUCCESSFUL")), off.report());
            assertEquals(List.of("<entity:Account#ada.name>", "<entity:Account#ada.password>"), off.reached(), off.report());
            assertTrue(off.events().stream().anyMatch(row -> row.equals("scenario PASSED Account fields reach the step")), off.report());
            assertFalse(Files.exists(DoltPaths.database(project)));

            System.setProperty("pkb_dolt", "true");
            DoltRuntime.reset();
            DoltScenarioHarness.Outcome on = runEntity();
            assertTrue(on.tests().stream().allMatch(row -> row.startsWith("SUCCESSFUL")), on.report());
            assertEquals(List.of("Ada", "<password:qaDb>"), on.reached(), on.report());
            assertTrue(on.reached().stream().noneMatch(value -> value.contains("hunter2")), on.report());
            assertTrue(on.events().stream().anyMatch(row -> row.equals("scenario PASSED Account fields reach the step")), on.report());
            if (DoltPaths.ready(project)) {
                DoltEntities.Entity stored = DoltEntities.get(project, "Account", "ada");
                assertEquals(CANONICAL, stored.payloadJson());
                assertTrue(stored.secretRefs().contains("<password:qaDb>"), stored.secretRefs());
                assertFalse(stored.payloadJson().contains("hunter2"));
            }
            String onDisk = Files.readString(entity);
            assertTrue(onDisk.contains("<password:qaDb>"), onDisk);
            assertFalse(onDisk.contains("hunter2"));
        } finally {
            System.setProperties(before);
            DoltRuntime.reset();
            MappingProcessor.resetCommonMaps();
            DoltScenarioHarness.release();
        }
    }

    private DoltScenarioHarness.Outcome runEntity() {
        return DoltScenarioHarness.execute(
                project.resolve("features"),
                "tools.dscode.coredefinitions,tools.dscode.dolt",
                "@dolt-entity"
        );
    }

    private DoltFiles.Row row() throws Exception {
        try (DoltDatabase database = DoltDatabase.open(project)) {
            return DoltFiles.find(database.connection(), "dolt-entities/Account/ada.entity");
        }
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
