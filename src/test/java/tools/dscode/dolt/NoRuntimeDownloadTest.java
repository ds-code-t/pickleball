package tools.dscode.dolt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Replaces {@code DoltInstallerTest}. The store downloads nothing and listens on nothing. */
class NoRuntimeDownloadTest {
    @TempDir
    Path project;

    @Test
    void openingAndUsingTheStoreStartsNoProcessAndNoSocket() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n", StandardCharsets.UTF_8);
        long processes = ProcessHandle.current().descendants().count();
        Set<String> sockets = sockets();
        assertEquals(0, DoltCli.execute(new String[] {"dolt", "hydrate", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, DoltCli.execute(new String[] {"dolt", "status", "--project=" + project},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream())));
        String status = out.toString(StandardCharsets.UTF_8);
        assertTrue(status.contains("store=embedded"), status);
        assertEquals(processes, ProcessHandle.current().descendants().count());
        assertEquals(sockets, sockets());
        assertFalse(Files.exists(project.resolve(".pickleball/dolt/server.json")));
        assertTrue(DoltPaths.ready(project));
    }

    @Test
    void installAndServerAreUnknownCommands() {
        Capture install = run("dolt", "install", "--project=" + project);
        assertEquals(2, install.code, install.err);
        assertTrue(install.err.contains("Unknown Dolt command: install"), install.err);
        assertTrue(install.err.contains("fix: dolt help"), install.err);
        Capture server = run("dolt", "server", "start", "--project=" + project);
        assertEquals(2, server.code, server.err);
        assertTrue(server.err.contains("Unknown Dolt command: server"), server.err);
        String help = DoltCli.help();
        assertFalse(help.contains("dolt install"), help);
        assertFalse(help.contains("dolt server"), help);
        assertFalse(Files.exists(project.resolve(".pickleball")));
    }

    @Test
    void hydrateStartsNoChildProcess() throws Exception {
        writeFeature();
        long processes = ProcessHandle.current().descendants().count();
        assertEquals(0, DoltCli.execute(new String[] {"dolt", "hydrate", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())));
        assertEquals(processes, ProcessHandle.current().descendants().count());
    }

    @Test
    void statusOpensNoSocket() throws Exception {
        writeFeature();
        assertEquals(0, DoltCli.execute(new String[] {"dolt", "hydrate", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())));
        Set<String> sockets = sockets();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, DoltCli.execute(new String[] {"dolt", "status", "--project=" + project},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream())));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("store=embedded"));
        assertEquals(sockets, sockets());
    }

    @Test
    void installIsAnUnknownCommand() {
        Capture install = run("dolt", "install", "--check", "--project=" + project);
        assertEquals(2, install.code, install.err);
        assertTrue(install.err.contains("Unknown Dolt command: install"), install.err);
        assertTrue(install.err.contains("fix: dolt help"), install.err);
        assertFalse(Files.exists(project.resolve(".pickleball")));
    }

    @Test
    void serverIsAnUnknownCommand() {
        Capture server = run("dolt", "server", "status", "--json", "--project=" + project);
        assertEquals(2, server.code, server.err);
        assertTrue(server.err.contains("Unknown Dolt command: server"), server.err);
        assertFalse(Files.exists(project.resolve(".pickleball/dolt/server.json")));
    }

    @Test
    void helpDoesNotOfferInstallOrServer() {
        String help = DoltCli.help();
        assertFalse(help.contains("dolt install"), help);
        assertFalse(help.contains("dolt server"), help);
        assertTrue(help.contains("org.sqlite.tmpdir"), help);
    }

    @Test
    void aMissingBinarySettingDoesNotDownload() throws Exception {
        writeFeature();
        String bin = System.getProperty("pkb_dolt_bin");
        String auto = System.getProperty("pkb_dolt_auto_install");
        try {
            System.setProperty("pkb_dolt_bin", project.resolve("missing-dolt").toString());
            System.setProperty("pkb_dolt_auto_install", "false");
            Capture hydrate = run("dolt", "hydrate", "--project=" + project);
            assertEquals(0, hydrate.code, hydrate.err);
            assertFalse(hydrate.err.toLowerCase(java.util.Locale.ROOT).contains("not installed"), hydrate.err);
            assertTrue(DoltPaths.ready(project));
            assertFalse(Files.exists(project.resolve("missing-dolt")));
        } finally {
            restore("pkb_dolt_bin", bin);
            restore("pkb_dolt_auto_install", auto);
        }
    }

    @Test
    void theReleaseCatalogIsNotOnTheClasspath() {
        assertEquals(null, NoRuntimeDownloadTest.class.getClassLoader()
                .getResource("META-INF/pickleball/dolt-release.properties"));
    }

    private void writeFeature() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n", StandardCharsets.UTF_8);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static Set<String> sockets() throws Exception {
        Set<String> found = new HashSet<>();
        Path fd = Path.of("/proc/self/fd");
        if (!Files.isDirectory(fd)) return found;
        try (var stream = Files.list(fd)) {
            for (Path entry : stream.toList()) {
                try {
                    String target = Files.readSymbolicLink(entry).toString();
                    if (target.startsWith("socket:")) found.add(target);
                } catch (java.io.IOException ignored) {
                    // A descriptor can close between the list and the read.
                }
            }
        }
        return found;
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = DoltCli.execute(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
