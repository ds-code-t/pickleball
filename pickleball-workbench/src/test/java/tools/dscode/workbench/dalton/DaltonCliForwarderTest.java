package tools.dscode.workbench.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsToTheDaltonCli() {
        Path jar = Path.of("pickleball.jar");
        List<String> command = DaltonCliForwarder.command(jar, project, List.of("status"));
        assertEquals("dalton", command.get(3));
        assertEquals("status", command.get(4));
        assertTrue(command.get(5).startsWith("--project="), command.toString());
        assertTrue(command.get(2).endsWith("pickleball.jar"), command.toString());
        assertEquals("-jar", command.get(1));
    }

    @Test
    void historyAndExtractForwardAsCliArguments() {
        Path jar = Path.of("pickleball.jar");
        List<String> history = DaltonCliForwarder.command(jar, project, List.of("history", "retain", "--days=7"));
        assertEquals("dalton", history.get(3));
        assertEquals("history", history.get(4));
        assertEquals("retain", history.get(5));
        assertEquals("--days=7", history.get(6));
        assertTrue(history.get(7).startsWith("--project="), history.toString());
        List<String> extract = DaltonCliForwarder.command(jar, project, List.of("extract", "--force"));
        assertEquals("extract", extract.get(4));
        assertEquals("--force", extract.get(5));
        List<String> feature = DaltonCliForwarder.command(
                jar, project, List.of("feature", "put", "features/pay.feature", "--file=pay.feature"));
        assertEquals("feature", feature.get(4));
        assertEquals("put", feature.get(5));
        assertEquals("features/pay.feature", feature.get(6));
        assertEquals("--file=pay.feature", feature.get(7));
        assertTrue(feature.get(8).startsWith("--project="), feature.toString());
        List<String> query = DaltonCliForwarder.command(jar, project, List.of("files", "list", "--json"));
        assertEquals("files", query.get(4));
        assertEquals("list", query.get(5));
        assertEquals("--json", query.get(6));
    }

    @Test
    void missingJarDoesNotInventADatabaseCommand() {
        DaltonCliForwarder.Result result = DaltonCliForwarder.run(project, List.of("hydrate", "--dry-run"), project.resolve("missing.jar"));
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("error:"), result.err());
        assertTrue(result.err().contains("pickleball jar"), result.err());
        assertTrue(result.command().isEmpty());
    }
}
