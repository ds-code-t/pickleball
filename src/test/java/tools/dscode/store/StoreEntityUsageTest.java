package tools.dscode.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.usage.LookupProbe;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.Status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreEntityUsageTest {
    private static final String PAYLOAD = "{\"name\":\"Ada\",\"password\":\"<password:qaDb>\"}";

    @TempDir
    Path project;

    @Test
    void lookupWithoutAStepDoesNotCreateADatabase() throws Exception {
        writeEntity();
        String previous = System.getProperty("pkb_store");
        String previousProject = System.getProperty("pkb_store_project");
        try {
            System.setProperty("pkb_store", "true");
            System.setProperty("pkb_store_project", project.toString());
            StoreRunContext.clear();
            assertEquals("Ada", StoreEntities.lookup("entity:Account#ada.name"));
            assertEquals("<password:qaDb>", StoreEntities.lookup("entity:Account#ada.password"));
            assertFalse(Files.exists(StorePaths.database(project)));
        } finally {
            restore("pkb_store", previous);
            restore("pkb_store_project", previousProject);
            StoreRunContext.clear();
        }
    }

    @Test
    void lookupAndUseRecordMaskedReferencesOnly() throws Exception {
        writeEntity();
        String previous = System.getProperty("pkb_store");
        String previousProject = System.getProperty("pkb_store_project");
        try {
            System.setProperty("pkb_store", "true");
            System.setProperty("pkb_store_project", project.toString());
            String step = StoreExecution.recordStep(project, new StoreExecution.StepDraft(
                    "usage-run", "features/pay.feature|2", "Pay", null, null, 1,
                    "the total is 10", 3, "PASSED", null, null));
            StoreRunContext.activate(project, step);
            assertEquals("Ada", StoreEntities.lookup("entity:Account#ada.name"));
            assertEquals("<password:qaDb>", StoreEntities.lookup("entity:Account#ada.password"));
            StoreEntities.Usage mention = StoreEntities.note(project, null, "Account", "ada", "name", "mention", null);
            assertEquals("<entity:Account#ada.name>", mention.valueRef());
            Capture use = run("store", "entity", "use", "Account", "ada", "--field=password",
                    "--operation=write", "--value-ref=hunter2-cleartext", "--step=" + step, "--project=" + project);
            assertEquals(0, use.code, use.err);
            assertTrue(use.out.contains("ref=~MASK;"), use.out);
            StoreRunContext.clear();
            Capture bare = run("store", "entity", "use", "Account", "ada", "--project=" + project);
            assertEquals(1, bare.code, bare.out);
            assertTrue(bare.err.contains("active step"), bare.err);

            List<StoreEntities.Usage> rows = StoreEntities.usages(project);
            assertEquals(4, rows.size(), rows.toString());
            assertEquals("read", rows.get(0).operation());
            assertEquals("name", rows.get(0).field());
            assertEquals("<entity:Account#ada.name>", rows.get(0).valueRef());
            assertEquals("read", rows.get(1).operation());
            assertEquals("<password:qaDb>", rows.get(1).valueRef());
            assertEquals("mention", rows.get(2).operation());
            assertEquals("write", rows.get(3).operation());
            assertEquals("~MASK;", rows.get(3).valueRef());
            assertTrue(rows.stream().noneMatch(row -> "Ada".equals(row.valueRef()) || row.valueRef().contains("hunter2")));
            String db = StoreDatabase.storedText(project);
            assertFalse(db.contains("hunter2-cleartext"), db);
        } finally {
            restore("pkb_store", previous);
            restore("pkb_store_project", previousProject);
            StoreRunContext.clear();
        }
    }

    @Test
    void unknownOperationIsRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                StoreEntities.note(project, "step", "Account", "ada", "name", "delete", null));
    }

    @Test
    void aRealStepRecordsTheLookup() throws Exception {
        writeEntity();
        String previous = System.getProperty("pkb_store");
        String previousProject = System.getProperty("pkb_store_project");
        String track = System.getProperty("pkb_store_track");
        String run = System.getProperty("pkb_run_id");
        try {
            System.setProperty("pkb_store", "true");
            System.setProperty("pkb_store_track", "true");
            System.setProperty("pkb_store_project", project.toAbsolutePath().normalize().toString());
            System.setProperty("pkb_run_id", "usage-live");
            StoreRunContext.clear();
            var result = PickleballEngine.run("""
                    Feature: Usage
                      Scenario: Read the account
                        * the account name is read
                    """, EngineOptions.of(LookupProbe.class).project(project));
            assertEquals(Status.PASSED, result.status(), result.message());
            List<StoreEntities.Usage> rows = StoreEntities.usages(project);
            assertTrue(rows.stream().anyMatch(row ->
                    "Account".equals(row.type())
                            && "ada".equals(row.id())
                            && "name".equals(row.field())
                            && "read".equals(row.operation())
                            && "<entity:Account#ada.name>".equals(row.valueRef())), rows.toString());
            assertTrue(rows.stream().noneMatch(row -> "Ada".equals(row.valueRef())));
        } finally {
            restore("pkb_store", previous);
            restore("pkb_store_project", previousProject);
            restore("pkb_store_track", track);
            restore("pkb_run_id", run);
            StoreRunContext.clear();
            StoreExecution.finishScenario("PASSED");
        }
    }

    private void writeEntity() throws Exception {
        Path file = project.resolve("store-entities/Account/ada.entity");
        Files.createDirectories(file.getParent());
        Files.write(file, StoreEntities.canonicalFile("Account", "ada", 1, PAYLOAD));
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

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private record Capture(int code, String out, String err) {
    }
}
