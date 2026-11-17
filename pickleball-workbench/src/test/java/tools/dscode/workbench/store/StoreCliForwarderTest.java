package tools.dscode.workbench.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.workbench.launch.ConsumerClasspath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreCliForwarderTest {
    @TempDir
    Path project;

    @Test
    void commandForwardsToTheStoreCli() {
        Path jar = Path.of("pickleball.jar");
        List<String> command = StoreCliForwarder.command(jar, project, List.of("status"));
        int arg = store(command);
        assertEquals("status", command.get(arg));
        assertTrue(command.get(arg + 1).startsWith("--project="), command.toString());
        assertTrue(command.get(2).contains("pickleball.jar"), command.toString());
        assertEquals("-cp", command.get(1));
        assertFalse(command.contains("-jar"), command.toString());
    }

    @Test
    void classpathCommandIncludesSqliteAndJackson() throws Exception {
        Path jar = project.resolve("pickleball.jar");
        Files.write(jar, new byte[] {1});
        Path state = project.resolve(".pickleball/workbench");
        Files.createDirectories(state);
        Path sqlite = state.resolve("sqlite-jdbc-3.47.2.0.jar");
        Path databind = state.resolve("jackson-databind-2.20.0.jar");
        Path core = state.resolve("jackson-core-2.20.0.jar");
        Path annotations = state.resolve("jackson-annotations-2.20.jar");
        for (Path file : List.of(sqlite, databind, core, annotations)) Files.write(file, new byte[] {1});
        Files.writeString(state.resolve("classpath.txt"), String.join("\n",
                sqlite.toString(), databind.toString(), core.toString(), annotations.toString()) + "\n");
        List<String> command = StoreCliForwarder.command(jar, project, List.of("timeline", "--latest", "--json"));
        assertEquals("-cp", command.get(1));
        assertEquals(ConsumerClasspath.MAIN_CLASS, command.get(3));
        assertEquals("store", command.get(4));
        String classpath = command.get(2);
        assertTrue(classpath.contains(sqlite.toString()), classpath);
        assertTrue(classpath.contains(databind.toString()), classpath);
        assertTrue(classpath.contains(core.toString()), classpath);
        assertTrue(classpath.contains(annotations.toString()), classpath);
        assertTrue(classpath.contains("sqlite-jdbc"), classpath);
        assertTrue(classpath.contains("jackson-databind"), classpath);
        assertFalse(command.contains("-jar"), command.toString());

        Path bare = project.resolve("bare");
        Files.createDirectories(bare);
        List<String> fallback = StoreCliForwarder.command(jar, bare, List.of("status"));
        String resolved = fallback.get(2);
        assertTrue(resolved.contains("sqlite-jdbc"), resolved);
        assertTrue(resolved.contains("jackson-databind"), resolved);
        assertTrue(resolved.contains("jackson-core"), resolved);
        assertTrue(resolved.contains("jackson-annotations"), resolved);
    }

    @Test
    void historyAndExtractForwardAsCliArguments() {
        Path jar = Path.of("pickleball.jar");
        List<String> history = StoreCliForwarder.command(jar, project, List.of("history", "retain", "--days=7"));
        int arg = store(history);
        assertEquals("history", history.get(arg));
        assertEquals("retain", history.get(arg + 1));
        assertEquals("--days=7", history.get(arg + 2));
        assertTrue(history.get(arg + 3).startsWith("--project="), history.toString());
        List<String> extract = StoreCliForwarder.command(jar, project, List.of("extract", "--force"));
        int extractAt = store(extract);
        assertEquals("extract", extract.get(extractAt));
        assertEquals("--force", extract.get(extractAt + 1));
        List<String> feature = StoreCliForwarder.command(
                jar, project, List.of("feature", "put", "features/pay.feature", "--file=pay.feature"));
        int featureAt = store(feature);
        assertEquals("feature", feature.get(featureAt));
        assertEquals("put", feature.get(featureAt + 1));
        assertEquals("features/pay.feature", feature.get(featureAt + 2));
        assertEquals("--file=pay.feature", feature.get(featureAt + 3));
        assertTrue(feature.get(featureAt + 4).startsWith("--project="), feature.toString());
        List<String> query = StoreCliForwarder.command(jar, project, List.of("files", "list", "--json"));
        int queryAt = store(query);
        assertEquals("files", query.get(queryAt));
        assertEquals("list", query.get(queryAt + 1));
        assertEquals("--json", query.get(queryAt + 2));
    }

    @Test
    void sandboxForwardsAsCliArguments() {
        Path jar = Path.of("pickleball.jar");
        List<String> list = StoreCliForwarder.command(jar, project, List.of("sandbox", "list", "--json"));
        int listAt = store(list);
        assertEquals(List.of("sandbox", "list", "--json"), list.subList(listAt, listAt + 3));
        List<String> compile = StoreCliForwarder.command(jar, project, List.of("glue", "compile", "--sandbox=left"));
        int compileAt = store(compile);
        assertEquals(List.of("glue", "compile", "--sandbox=left"), compile.subList(compileAt, compileAt + 3));
        List<String> merge = StoreCliForwarder.command(
                jar, project, List.of("sandbox", "merge", "left", "--policy=branch-wins", "--json"));
        int mergeAt = store(merge);
        assertEquals(List.of("sandbox", "merge", "left", "--policy=branch-wins", "--json"), merge.subList(mergeAt, mergeAt + 5));
        List<String> changes = StoreCliForwarder.command(
                jar, project, List.of("changes", "--since=3", "--table=files", "--limit=10", "--json"));
        int changesAt = store(changes);
        assertEquals(List.of("changes", "--since=3", "--table=files", "--limit=10", "--json"), changes.subList(changesAt, changesAt + 5));
    }

    @Test
    void missingJarDoesNotInventADatabaseCommand() {
        StoreCliForwarder.Result result = StoreCliForwarder.run(project, List.of("hydrate", "--dry-run"), project.resolve("missing.jar"));
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("error:"), result.err());
        assertTrue(result.err().contains("pickleball jar"), result.err());
        assertTrue(result.command().isEmpty());
        assertTrue(StoreCliForwarder.visibleFailure(result).contains("pickleball jar"), result.err());
        assertTrue(StoreCliForwarder.visibleFailure(result).contains("exit 1"), result.err());
    }

    private static int store(List<String> command) {
        int at = command.indexOf("store");
        assertTrue(at > 3, command.toString());
        assertEquals("-cp", command.get(1));
        assertEquals(ConsumerClasspath.MAIN_CLASS, command.get(at - 1));
        assertFalse(command.contains("-jar"), command.toString());
        return at + 1;
    }
}
