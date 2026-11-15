package tools.dscode.dolt;

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

class DoltHydrateTest {
    @TempDir
    Path project;

    @Test
    void hydratesConsumerFeaturesConfigsCallsAndSkipsUnchangedBytes() throws Exception {
        Path repo = Path.of("").toAbsolutePath().normalize();
        copy(repo.resolve("maven-consumer-project/src/test/resources/features/navigation.feature"),
                project.resolve("src/test/resources/features/navigation.feature"));
        copy(repo.resolve("maven-consumer-project/src/test/resources/configs/URL.yaml"),
                project.resolve("src/test/resources/configs/URL.yaml"));
        copy(repo.resolve("maven-consumer-project/src/test/resources/calls/service-call-definitions.feature"),
                project.resolve("src/test/resources/calls/service-call-definitions.feature"));
        write(project.resolve("src/test/resources/component/login.feature"),
                "Feature: Login\n  Scenario: In\n    Given ready\n");
        write(project.resolve("templates/note.ftl"), "Hello ${name}\n");
        write(project.resolve("resource-maps/app.yaml"), "map: app\n");
        byte[] cafe = new byte[] {(byte) 0x63, (byte) 0x61, (byte) 0x66, (byte) 0xC3, (byte) 0xA9, (byte) 0xFF};
        Files.createDirectories(project.resolve("src/test/resources/configs"));
        Files.write(project.resolve("src/test/resources/configs/cafe.yaml"), cafe);

        Capture first = run("dolt", "hydrate", "--project=" + project);
        assertEquals(0, first.code, first.err);
        assertTrue(first.out.contains("inserted=7"), first.out);

        assertRow("src/test/resources/features/navigation.feature", "feature",
                Files.readAllBytes(repo.resolve("maven-consumer-project/src/test/resources/features/navigation.feature")));
        assertRow("src/test/resources/configs/URL.yaml", "config",
                Files.readAllBytes(repo.resolve("maven-consumer-project/src/test/resources/configs/URL.yaml")));
        assertRow("src/test/resources/calls/service-call-definitions.feature", "call",
                Files.readAllBytes(repo.resolve("maven-consumer-project/src/test/resources/calls/service-call-definitions.feature")));
        assertEquals("component", row("src/test/resources/component/login.feature").sourceType());
        assertEquals("template", row("templates/note.ftl").sourceType());
        assertEquals("resource-map", row("resource-maps/app.yaml").sourceType());
        assertArrayEquals(cafe, row("src/test/resources/configs/cafe.yaml").raw());

        Capture second = run("dolt", "hydrate", "--project=" + project);
        assertEquals(0, second.code, second.err);
        assertTrue(second.out.contains("inserted=0"), second.out);
        assertTrue(second.out.contains("skipped=7"), second.out);

        Files.writeString(project.resolve("templates/note.ftl"), "Hello ${other}\n", StandardCharsets.UTF_8);
        Capture third = run("dolt", "hydrate", "--project=" + project);
        assertEquals(0, third.code, third.err);
        assertTrue(third.out.contains("updated=1"), third.out);
        assertTrue(third.out.contains("skipped=6"), third.out);
    }

    @Test
    void windowsSeparatorsAreStoredWithForwardSlashes() throws Exception {
        write(project.resolve("src/test/resources/features/win.feature"), "Feature: Win\n");
        assertEquals("src/test/resources/features/win.feature",
                DoltPaths.normalizeRelative("src\\test\\resources\\features\\win.feature"));
        Capture capture = run("dolt", "hydrate", "--project=" + project,
                "--path=src\\test\\resources\\features\\win.feature");
        assertEquals(0, capture.code, capture.err);
        DoltFiles.Row row = row("src/test/resources/features/win.feature");
        assertEquals("feature", row.sourceType());
        assertFalse(row.path().contains("\\"));
        assertArrayEquals("Feature: Win\n".getBytes(StandardCharsets.UTF_8), row.raw());
    }

    @Test
    void autoHydrateRunsOnlyWhenEnabled() throws Exception {
        String previous = System.getProperty("pkb_dolt");
        String previousProject = System.getProperty("pkb_dolt_project");
        Path other = Files.createTempDirectory("dolt-off");
        try {
            write(project.resolve("features/one.feature"), "Feature: One\n");
            System.setProperty("pkb_dolt_project", other.toString());
            System.setProperty("pkb_dolt", "false");
            DoltRuntime.reset();
            DoltRuntime.beforeFeatureLoad();
            assertFalse(Files.exists(DoltPaths.database(other)));

            System.setProperty("pkb_dolt_project", project.toString());
            System.setProperty("pkb_dolt", "true");
            DoltRuntime.reset();
            DoltRuntime.beforeFeatureLoad();
            assertEquals("feature", row("features/one.feature").sourceType());

            write(project.resolve("dolt.properties"), "enabled=true\n");
            System.clearProperty("pkb_dolt");
            assertTrue(DoltConfig.enabled(project));
            System.setProperty("pkb_dolt", "false");
            assertFalse(DoltConfig.enabled(project));
        } finally {
            restore("pkb_dolt", previous);
            restore("pkb_dolt_project", previousProject);
            DoltRuntime.reset();
        }
    }

    private void assertRow(String path, String type, byte[] expected) throws Exception {
        DoltFiles.Row row = row(path);
        assertEquals(type, row.sourceType());
        assertEquals(DoltHashes.sha256(expected), row.contentHash());
        assertArrayEquals(expected, row.raw());
    }

    private DoltFiles.Row row(String path) throws Exception {
        try (DoltDatabase database = DoltDatabase.open(project)) {
            return DoltFiles.find(database.connection(), path);
        }
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void copy(Path from, Path to) throws Exception {
        Files.createDirectories(to.getParent());
        Files.copy(from, to);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
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
