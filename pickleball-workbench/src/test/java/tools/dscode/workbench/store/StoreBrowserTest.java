package tools.dscode.workbench.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals(List.of("files", "list", "--json"), files.subList(4, 7));
        assertTrue(files.get(7).startsWith("--project="), files.toString());

        List<String> steps = StoreCliForwarder.command(
                jar, project, StoreBrowser.steps("features/pay.feature", "Two"));
        assertEquals("steps", steps.get(4));
        assertEquals("list", steps.get(5));
        assertEquals("--feature=features/pay.feature", steps.get(6));
        assertEquals("--scenario=Two", steps.get(7));
        assertEquals("--json", steps.get(8));

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
        assertEquals("feature", put.get(4));
        assertEquals("put", put.get(5));
        assertEquals("features/pay.feature", put.get(6));
        assertTrue(put.get(7).startsWith("--file="), put.toString());
        assertTrue(put.get(7).contains("edited.feature"), put.toString());

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
        assertEquals("entity", forwarded.get(4));
        assertEquals("put", forwarded.get(5));
        assertEquals("Customer", forwarded.get(6));
        assertEquals("7", forwarded.get(7));
        assertEquals("--version=3", forwarded.get(8));
        assertTrue(forwarded.get(9).startsWith("--payload-file="), forwarded.toString());

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
        assertEquals("run", warm.get(4));
        assertEquals("--dry-run", warm.get(5));
        assertTrue(warm.get(6).startsWith("--project="), warm.toString());
    }
}
