package tools.dscode.workbench.dolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsToTheDoltCli() {
        Path jar = Path.of("pickleball.jar");
        List<String> command = DoltCliForwarder.command(jar, project, List.of("status"));
        assertEquals("dolt", command.get(3));
        assertEquals("status", command.get(4));
        assertTrue(command.get(5).startsWith("--project="), command.toString());
        assertTrue(command.get(2).endsWith("pickleball.jar"), command.toString());
        assertEquals("-jar", command.get(1));
    }

    @Test
    void historyAndExtractForwardAsCliArguments() {
        Path jar = Path.of("pickleball.jar");
        List<String> history = DoltCliForwarder.command(jar, project, List.of("history", "retain", "--days=7"));
        assertEquals("dolt", history.get(3));
        assertEquals("history", history.get(4));
        assertEquals("retain", history.get(5));
        assertEquals("--days=7", history.get(6));
        assertTrue(history.get(7).startsWith("--project="), history.toString());
        List<String> extract = DoltCliForwarder.command(jar, project, List.of("extract", "--force"));
        assertEquals("extract", extract.get(4));
        assertEquals("--force", extract.get(5));
        List<String> feature = DoltCliForwarder.command(
                jar, project, List.of("feature", "put", "features/pay.feature", "--file=pay.feature"));
        assertEquals("feature", feature.get(4));
        assertEquals("put", feature.get(5));
        assertEquals("features/pay.feature", feature.get(6));
        assertEquals("--file=pay.feature", feature.get(7));
        assertTrue(feature.get(8).startsWith("--project="), feature.toString());
        List<String> query = DoltCliForwarder.command(jar, project, List.of("files", "list", "--json"));
        assertEquals("files", query.get(4));
        assertEquals("list", query.get(5));
        assertEquals("--json", query.get(6));
    }

    @Test
    void sandboxForwardsAsCliArguments() {
        Path jar = Path.of("pickleball.jar");
        List<String> list = DoltCliForwarder.command(jar, project, List.of("sandbox", "list", "--json"));
        assertEquals(List.of("sandbox", "list", "--json"), list.subList(4, 7));
        List<String> compile = DoltCliForwarder.command(jar, project, List.of("glue", "compile", "--sandbox=left"));
        assertEquals(List.of("glue", "compile", "--sandbox=left"), compile.subList(4, 7));
    }

    @Test
    void missingJarDoesNotInventADatabaseCommand() {
        DoltCliForwarder.Result result = DoltCliForwarder.run(project, List.of("hydrate", "--dry-run"), project.resolve("missing.jar"));
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("error:"), result.err());
        assertTrue(result.err().contains("pickleball jar"), result.err());
        assertTrue(result.command().isEmpty());
    }
}
