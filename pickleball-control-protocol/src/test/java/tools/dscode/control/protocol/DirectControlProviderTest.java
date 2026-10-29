package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectControlProviderTest {
    @TempDir
    Path temp;

    @Test
    void absentKeyStartsNothing() {
        DirectControlProvider.Outcome outcome = DirectControlProvider.open(request(true, "  ", null));
        assertEquals(DirectControlProvider.Kind.NOT_CONFIGURED, outcome.kind());
        assertNull(outcome.handle());
        assertFalse(Files.isRegularFile(PickleballLocalLayout.root(temp).resolve("agent-log")));
    }

    @Test
    void normalRunIgnoresTheKey() throws IOException {
        Path script = script("#!/bin/sh\nsleep 30\n");
        DirectControlProvider.Outcome outcome = DirectControlProvider.open(request(false, script.toString(), "127.0.0.1:9222"));
        assertEquals(DirectControlProvider.Kind.NOT_LIVE, outcome.kind());
        assertNull(outcome.handle());
        assertFalse(Files.isRegularFile(log()));
    }

    @Test
    void missingBinarySkipsWithAClearMessage() {
        DirectControlProvider.Outcome outcome = DirectControlProvider.open(liveComposite(Map.of()));
        assertEquals(DirectControlProvider.Kind.BINARY_MISSING, outcome.kind());
        assertNull(outcome.handle());
        assertTrue(outcome.message().contains("composite binary not found"));
    }

    @Test
    void presentBinaryAttachesAndDetachLeavesTheBrowserRunning() throws Exception {
        Path script = script("""
                #!/bin/sh
                echo "descriptor=$PICKLEBALL_SESSION_DESCRIPTOR"
                sleep 30
                """);
        Process browser = new ProcessBuilder("sleep", "30").start();
        DirectControlProvider.Handle provider = null;
        try {
            DirectControlProvider.Outcome outcome = DirectControlProvider.open(request(true, script.toString(), "127.0.0.1:9222"));
            assertEquals(DirectControlProvider.Kind.STARTED, outcome.kind());
            provider = outcome.handle();
            assertEquals(List.of(
                    script.toAbsolutePath().normalize().toString(),
                    "session", "attach",
                    "--cdp", "http://127.0.0.1:9222",
                    "--id", "session-1",
                    "--i-accept-local-attach"
            ), provider.command());
            assertTrue(provider.alive());
            assertTrue(browser.isAlive());
            String log = waitForLog();
            assertTrue(log.contains("descriptor=" + descriptor().toAbsolutePath().normalize()));
            provider.detach();
            assertFalse(provider.alive());
            assertTrue(browser.isAlive(), "detach must not stop the browser");
            String shortLog = Files.readString(PickleballLocalLayout.root(temp).resolve("agent-log"));
            assertTrue(shortLog.contains("\tstart\t"));
            assertTrue(shortLog.contains("\tstop\t"));
        } finally {
            if (provider != null) provider.detach();
            browser.destroyForcibly();
        }
    }

    @Test
    void providerExitDoesNotKillTheBrowser() throws Exception {
        Path script = script("#!/bin/sh\nexit 0\n");
        Process browser = new ProcessBuilder("sleep", "30").start();
        DirectControlProvider.Handle provider = null;
        try {
            provider = DirectControlProvider.open(request(true, script.toString(), "http://127.0.0.1:9333")).handle();
            assertTrue(provider.waitFor(Duration.ofSeconds(5)));
            assertFalse(provider.alive());
            assertTrue(browser.isAlive(), "provider exit must not stop the browser");
            provider.detach();
            assertTrue(browser.isAlive());
        } finally {
            if (provider != null) provider.detach();
            browser.destroyForcibly();
        }
    }

    @Test
    void realCompositeIsOptional() {
        Optional<Path> binary = DirectControlProvider.resolveBinary(
                DirectControlProvider.TOKEN, temp, System.getenv());
        Assumptions.assumeTrue(
                binary.isPresent(),
                "Direct-control provider skipped: composite binary not found."
        );
        assertTrue(Files.isExecutable(binary.orElseThrow()));
    }

    @Test
    void configuredReadsTheWorkspaceThenTheSystemProperty() throws IOException {
        String previous = System.getProperty(DirectControlProvider.KEY);
        String member = System.getProperty(DirectControlProvider.RUNVARS_PROPERTY);
        try {
            System.clearProperty(DirectControlProvider.KEY);
            System.clearProperty(DirectControlProvider.RUNVARS_PROPERTY);
            Path workspace = Workspace.directory(temp, "run-1");
            Workspace.writeRunVars(workspace, Map.of(DirectControlProvider.KEY, "composite"));
            assertEquals("composite", DirectControlProvider.configured(temp, "run-1").orElseThrow());
            Workspace.writeRunVars(workspace, Map.of(DirectControlProvider.KEY, "  "));
            assertTrue(DirectControlProvider.configured(temp, "run-1").isEmpty());
            System.setProperty(DirectControlProvider.RUNVARS_PROPERTY, "/opt/composite");
            assertEquals("/opt/composite", DirectControlProvider.configured(temp, "run-1").orElseThrow());
            System.setProperty(DirectControlProvider.KEY, "composite");
            assertEquals("composite", DirectControlProvider.configured(temp, "run-1").orElseThrow());
        } finally {
            restore(DirectControlProvider.KEY, previous);
            restore(DirectControlProvider.RUNVARS_PROPERTY, member);
        }
    }

    private DirectControlProvider.Request request(boolean live, String configured, String cdp) {
        return new DirectControlProvider.Request(
                live,
                configured,
                temp,
                "run-1",
                "session-1",
                cdp,
                descriptor(),
                log(),
                null,
                Map.of()
        );
    }

    private DirectControlProvider.Request liveComposite(Map<String, String> env) {
        return new DirectControlProvider.Request(
                true,
                "composite",
                temp,
                "run-1",
                "session-1",
                "127.0.0.1:9222",
                descriptor(),
                log(),
                null,
                env
        );
    }

    private Path descriptor() {
        return temp.resolve("descriptor.json");
    }

    private Path log() {
        return temp.resolve("session").resolve("provider.log");
    }

    private Path script(String body) throws IOException {
        Path file = temp.resolve("composite-" + System.nanoTime());
        Files.writeString(file, body);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    private String waitForLog() throws Exception {
        Path file = log();
        for (int i = 0; i < 50; i++) {
            if (Files.isRegularFile(file) && Files.size(file) > 0) return Files.readString(file);
            Thread.sleep(20);
        }
        return Files.isRegularFile(file) ? Files.readString(file) : "";
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
