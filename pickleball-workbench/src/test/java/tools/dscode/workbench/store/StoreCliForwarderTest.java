package tools.dscode.workbench.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsToTheStoreCli() {
        Path jar = Path.of("pickleball.jar");
        List<String> command = StoreCliForwarder.command(jar, project, List.of("status"));
        assertEquals("store", command.get(3));
        assertEquals("status", command.get(4));
        assertTrue(command.get(5).startsWith("--project="), command.toString());
        assertTrue(command.get(2).endsWith("pickleball.jar"), command.toString());
        assertEquals("-jar", command.get(1));
    }

    @Test
    void historyAndExtractForwardAsCliArguments() {
        Path jar = Path.of("pickleball.jar");
        List<String> history = StoreCliForwarder.command(jar, project, List.of("history", "retain", "--days=7"));
        assertEquals("store", history.get(3));
        assertEquals("history", history.get(4));
        assertEquals("retain", history.get(5));
        assertEquals("--days=7", history.get(6));
        assertTrue(history.get(7).startsWith("--project="), history.toString());
        List<String> extract = StoreCliForwarder.command(jar, project, List.of("extract", "--force"));
        assertEquals("extract", extract.get(4));
        assertEquals("--force", extract.get(5));
        List<String> feature = StoreCliForwarder.command(
                jar, project, List.of("feature", "put", "features/pay.feature", "--file=pay.feature"));
        assertEquals("feature", feature.get(4));
        assertEquals("put", feature.get(5));
        assertEquals("features/pay.feature", feature.get(6));
        assertEquals("--file=pay.feature", feature.get(7));
        assertTrue(feature.get(8).startsWith("--project="), feature.toString());
        List<String> query = StoreCliForwarder.command(jar, project, List.of("files", "list", "--json"));
        assertEquals("files", query.get(4));
        assertEquals("list", query.get(5));
        assertEquals("--json", query.get(6));
    }

    @Test
    void sandboxForwardsAsCliArguments() {
        Path jar = Path.of("pickleball.jar");
        List<String> list = StoreCliForwarder.command(jar, project, List.of("sandbox", "list", "--json"));
        assertEquals(List.of("sandbox", "list", "--json"), list.subList(4, 7));
        List<String> compile = StoreCliForwarder.command(jar, project, List.of("glue", "compile", "--sandbox=left"));
        assertEquals(List.of("glue", "compile", "--sandbox=left"), compile.subList(4, 7));
        List<String> merge = StoreCliForwarder.command(
                jar, project, List.of("sandbox", "merge", "left", "--policy=branch-wins", "--json"));
        assertEquals(List.of("sandbox", "merge", "left", "--policy=branch-wins", "--json"), merge.subList(4, 9));
        List<String> changes = StoreCliForwarder.command(
                jar, project, List.of("changes", "--since=3", "--table=files", "--limit=10", "--json"));
        assertEquals(List.of("changes", "--since=3", "--table=files", "--limit=10", "--json"), changes.subList(4, 9));
    }

    @Test
    void missingJarDoesNotInventADatabaseCommand() {
        StoreCliForwarder.Result result = StoreCliForwarder.run(project, List.of("hydrate", "--dry-run"), project.resolve("missing.jar"));
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("error:"), result.err());
        assertTrue(result.err().contains("pickleball jar"), result.err());
        assertTrue(result.command().isEmpty());
    }
}
