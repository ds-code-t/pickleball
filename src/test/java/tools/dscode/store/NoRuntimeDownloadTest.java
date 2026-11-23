package tools.dscode.store;

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

/** Replaces {@code StoreInstallerTest}. The store downloads nothing and listens on nothing. */
class NoRuntimeDownloadTest {
    @TempDir
    Path project;

    @Test
    void openingAndUsingTheStoreStartsNoProcessAndNoSocket() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n", StandardCharsets.UTF_8);
        Set<Long> processes = descendantPids();
        Set<String> sockets = sockets();
        assertEquals(0, StoreCli.execute(new String[] {"store", "hydrate", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, StoreCli.execute(new String[] {"store", "status", "--project=" + project},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream())));
        String status = out.toString(StandardCharsets.UTF_8);
        assertTrue(status.contains("store=embedded"), status);
        assertNoNewDescendant(processes);
        assertEquals(sockets, sockets());
        assertFalse(Files.exists(project.resolve(".pickleball/store/server.json")));
        assertTrue(StorePaths.ready(project));
    }

    @Test
    void installAndServerAreUnknownCommands() {
        Capture install = run("store", "install", "--project=" + project);
        assertEquals(2, install.code, install.err);
        assertTrue(install.err.contains("Unknown Store command: install"), install.err);
        assertTrue(install.err.contains("fix: store help"), install.err);
        Capture server = run("store", "server", "start", "--project=" + project);
        assertEquals(2, server.code, server.err);
        assertTrue(server.err.contains("Unknown Store command: server"), server.err);
        String help = StoreCli.help();
        assertFalse(help.contains("store install"), help);
        assertFalse(help.contains("store server"), help);
        assertFalse(Files.exists(project.resolve(".pickleball")));
    }

    @Test
    void hydrateStartsNoChildProcess() throws Exception {
        writeFeature();
        Set<Long> processes = descendantPids();
        assertEquals(0, StoreCli.execute(new String[] {"store", "hydrate", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())));
        assertNoNewDescendant(processes);
    }

    @Test
    void statusOpensNoSocket() throws Exception {
        writeFeature();
        assertEquals(0, StoreCli.execute(new String[] {"store", "hydrate", "--project=" + project},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())));
        Set<String> sockets = sockets();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, StoreCli.execute(new String[] {"store", "status", "--project=" + project},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream())));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("store=embedded"));
        assertEquals(sockets, sockets());
    }

    @Test
    void installIsAnUnknownCommand() {
        Capture install = run("store", "install", "--check", "--project=" + project);
        assertEquals(2, install.code, install.err);
        assertTrue(install.err.contains("Unknown Store command: install"), install.err);
        assertTrue(install.err.contains("fix: store help"), install.err);
        assertFalse(Files.exists(project.resolve(".pickleball")));
    }

    @Test
    void serverIsAnUnknownCommand() {
        Capture server = run("store", "server", "status", "--json", "--project=" + project);
        assertEquals(2, server.code, server.err);
        assertTrue(server.err.contains("Unknown Store command: server"), server.err);
        assertFalse(Files.exists(project.resolve(".pickleball/store/server.json")));
    }

    @Test
    void helpDoesNotOfferInstallOrServer() {
        String help = StoreCli.help();
        assertFalse(help.contains("store install"), help);
        assertFalse(help.contains("store server"), help);
        assertTrue(help.contains("org.sqlite.tmpdir"), help);
    }

    @Test
    void aMissingBinarySettingDoesNotDownload() throws Exception {
        writeFeature();
        String bin = System.getProperty("pkb_store_bin");
        String auto = System.getProperty("pkb_store_auto_install");
        try {
            System.setProperty("pkb_store_bin", project.resolve("missing-store").toString());
            System.setProperty("pkb_store_auto_install", "false");
            Capture hydrate = run("store", "hydrate", "--project=" + project);
            assertEquals(0, hydrate.code, hydrate.err);
            assertFalse(hydrate.err.toLowerCase(java.util.Locale.ROOT).contains("not installed"), hydrate.err);
            assertTrue(StorePaths.ready(project));
            assertFalse(Files.exists(project.resolve("missing-store")));
        } finally {
            restore("pkb_store_bin", bin);
            restore("pkb_store_auto_install", auto);
        }
    }

    @Test
    void theReleaseCatalogIsNotOnTheClasspath() {
        assertEquals(null, NoRuntimeDownloadTest.class.getClassLoader()
                .getResource("META-INF/pickleball/store-release.properties"));
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

    /** Pids of processes this JVM started. A descendant that exits is not a store launch. */
    private static Set<Long> descendantPids() {
        Set<Long> pids = new HashSet<>();
        ProcessHandle.current().descendants().forEach(handle -> pids.add(handle.pid()));
        return pids;
    }

    private static void assertNoNewDescendant(Set<Long> before) {
        Set<Long> started = descendantPids();
        started.removeAll(before);
        assertTrue(started.isEmpty(), "store started a process: " + started);
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
        int code = StoreCli.execute(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
