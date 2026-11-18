package tools.dscode.workbench.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.workbench.launch.ConsumerClasspath;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreBrowserTest {
    @TempDir
    Path project;

    @Test
    void commandsForwardThroughTheCliAndJsonBecomesLabels() {
        Path jar = Path.of("pickleball.jar");
        List<String> files = StoreCliForwarder.command(jar, project, StoreBrowser.files());
        int filesAt = afterStore(files);
        assertEquals(List.of("files", "list", "--json"), files.subList(filesAt, filesAt + 3));
        assertTrue(files.get(filesAt + 3).startsWith("--project="), files.toString());

        List<String> steps = StoreCliForwarder.command(
                jar, project, StoreBrowser.steps("features/pay.feature", "Two"));
        int stepsAt = afterStore(steps);
        assertEquals("steps", steps.get(stepsAt));
        assertEquals("list", steps.get(stepsAt + 1));
        assertEquals("--feature=features/pay.feature", steps.get(stepsAt + 2));
        assertEquals("--scenario=Two", steps.get(stepsAt + 3));
        assertEquals("--json", steps.get(stepsAt + 4));

        String filesJson = """
                {"files":[
                  {"path":"features/pay.feature","sourceType":"feature","edited":true,"bytes":42,"contentHash":"abc","lastSyncedHash":"def"}
                ]}
                """;
        assertEquals(
                "features/pay.feature  feature  edited=yes  bytes=42",
                StoreBrowser.labels(filesJson).get(0));

        String show = """
                {"path":"features/pay.feature","keyword":"Feature","name":"Pay","language":"en","tags":"","description":"","scenarios":1,"text":"Feature: Pay\\n  Scenario: One\\n    Given ready\\n","scenarioCount":1,"scenarios":[{"path":"features/pay.feature","ord":1,"keyword":"Scenario","name":"One","line":2,"tags":"","description":""}]}
                """;
        assertEquals("Feature: Pay\n  Scenario: One\n    Given ready\n", StoreBrowser.editableText(show));
        assertEquals("features/pay.feature  Pay  scenarios=1", StoreBrowser.labels(show).get(0));

        Path edited = project.resolve("edited.feature");
        List<String> put = StoreCliForwarder.command(
                jar, project, StoreBrowser.featurePut("features/pay.feature", edited));
        int putAt = afterStore(put);
        assertEquals("feature", put.get(putAt));
        assertEquals("put", put.get(putAt + 1));
        assertEquals("features/pay.feature", put.get(putAt + 2));
        assertTrue(put.get(putAt + 3).startsWith("--file="), put.toString());
        assertTrue(put.get(putAt + 3).contains("edited.feature"), put.toString());

        String conflicts = """
                {"conflicts":[{"path":"features/pay.feature","kind":"untracked","message":"untracked DB edit on features/pay.feature"}]}
                """;
        assertEquals("untracked DB edit on features/pay.feature", StoreBrowser.labels(conflicts).get(0));
        assertEquals(List.of("Conflicts: none"), StoreBrowser.labels("{\"conflicts\":[]}"));

        assertEquals(
                List.of("Entity Customer 7 version 3"),
                StoreBrowser.labels("Customer 7 3\n"));
        assertEquals(List.of("Entities: none"), StoreBrowser.labels("entities 0\n"));
        assertEquals(List.of("Runs: none"), StoreBrowser.labels("runs 0\n"));

        Path payload = project.resolve("customer.json");
        List<String> entity = StoreBrowser.entityPut("Customer", "7", "3", payload);
        List<String> forwarded = StoreCliForwarder.command(jar, project, entity);
        int entityAt = afterStore(forwarded);
        assertEquals("entity", forwarded.get(entityAt));
        assertEquals("put", forwarded.get(entityAt + 1));
        assertEquals("Customer", forwarded.get(entityAt + 2));
        assertEquals("7", forwarded.get(entityAt + 3));
        assertEquals("--version=3", forwarded.get(entityAt + 4));
        assertTrue(forwarded.get(entityAt + 5).startsWith("--payload-file="), forwarded.toString());

        String binary = """
                {"path":"configs/cafe.yaml","sourceType":"config","edited":false,"bytes":2,"contentHash":"aa","lastSyncedHash":"aa","text":null,"base64":"/wE="}
                """;
        assertEquals("", StoreBrowser.editableText(binary));
        assertTrue(StoreBrowser.binary(binary));
        assertFalse(StoreBrowser.binary(show));
        assertEquals(
                "database present  files=2  scenarios=1  conflicts=0  skipped=1",
                StoreBrowser.labels("{\"database\":\"present\",\"files\":2,\"scenarios\":1,\"conflicts\":0,\"skipped\":1}").get(0));

        List<String> warm = StoreCliForwarder.command(jar, project, StoreBrowser.run(true));
        int warmAt = afterStore(warm);
        assertEquals("run", warm.get(warmAt));
        assertEquals("--dry-run", warm.get(warmAt + 1));
        assertTrue(warm.get(warmAt + 2).startsWith("--project="), warm.toString());
    }

    private static int afterStore(List<String> command) {
        int at = command.indexOf("store");
        assertTrue(at > 3, command.toString());
        assertEquals("-cp", command.get(1));
        assertEquals(ConsumerClasspath.MAIN_CLASS, command.get(at - 1));
        assertFalse(command.contains("-jar"), command.toString());
        return at + 1;
    }
}
