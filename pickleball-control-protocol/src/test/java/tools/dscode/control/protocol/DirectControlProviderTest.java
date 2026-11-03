package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Path script = fakeComposite();
        publishPaused();
        Process browser = new ProcessBuilder("sleep", "30").start();
        DirectControlProvider.Handle provider = null;
        try {
            DirectControlProvider.Outcome outcome = DirectControlProvider.open(request(true, script.toString(), "127.0.0.1:9222"));
            assertEquals(DirectControlProvider.Kind.STARTED, outcome.kind());
            provider = outcome.handle();
            Path sessionDir = DirectControlProvider.sessionDirectory(temp, "run-1");
            assertEquals(List.of(
                    script.toAbsolutePath().normalize().toString(),
                    "session", "attach",
                    "--cdp", "http://127.0.0.1:9222",
                    "--id", "session-1",
                    "--i-accept-local-attach",
                    "--session-dir", sessionDir.toString()
            ), provider.command());
            assertTrue(provider.attachJson().contains("\"launched\":false"));
            assertEquals("session-1", provider.compositeSessionId());
            assertEquals(sessionDir, provider.sessionDirectory());
            assertTrue(provider.alive(), "registry session stays up after attach exits");
            assertTrue(provider.waitFor(Duration.ofSeconds(5)));
            assertTrue(browser.isAlive());
            String log = Files.readString(log());
            assertTrue(log.contains("\"id\":\"session-1\""));
            assertFalse(log.contains("PICKLEBALL_SESSION_DESCRIPTOR"));
            SessionDescriptor published = SessionDescriptorWriter.read(temp, "run-1").orElseThrow();
            assertEquals(sessionDir.toString(), published.compositeSessionDir());
            assertEquals("session-1", published.compositeSessionId());
            assertEquals(SessionDescriptor.State.PAUSED, published.state());
            provider.detach();
            assertFalse(provider.alive());
            assertTrue(browser.isAlive(), "detach must not stop the browser");
            String calls = Files.readString(calls(script));
            assertTrue(calls.contains("session attach"));
            assertTrue(calls.contains("session close"));
            assertTrue(calls.contains("--session-dir " + sessionDir));
            assertTrue(Files.readString(log()).contains("\"killed\":false"));
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
        Path script = fakeComposite();
        Process browser = new ProcessBuilder("sleep", "30").start();
        DirectControlProvider.Handle provider = null;
        try {
            provider = DirectControlProvider.open(request(true, script.toString(), "http://127.0.0.1:9333")).handle();
            assertTrue(provider.waitFor(Duration.ofSeconds(5)));
            assertTrue(provider.alive(), "attach process exit leaves the registry session");
            assertTrue(browser.isAlive(), "provider exit must not stop the browser");
            provider.detach();
            assertFalse(provider.alive());
            assertTrue(browser.isAlive());
            assertTrue(Files.readString(calls(script)).contains("session close"));
        } finally {
            if (provider != null) provider.detach();
            browser.destroyForcibly();
        }
    }

    @Test
    void nonZeroAttachFailsClearly() throws Exception {
        Path script = script("""
                #!/bin/sh
                echo '{"ok":false,"error":"no DevTools"}' >&2
                exit 7
                """);
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                DirectControlProvider.open(request(true, script.toString(), "127.0.0.1:9222")));
        assertTrue(failure.getMessage().contains("exited 7"));
        assertTrue(failure.getMessage().contains("no DevTools"));
        Path shortLog = PickleballLocalLayout.root(temp).resolve("agent-log");
        assertFalse(Files.isRegularFile(shortLog) && Files.readString(shortLog).contains("\tstart\t"));
    }

    @Test
    void realCompositeAttachesQueriesAndCloseLeavesChromeRunning() throws Exception {
        Optional<Path> binary = DirectControlProvider.resolveBinary(
                DirectControlProvider.TOKEN, temp, System.getenv());
        Optional<Path> chrome = chromeBinary();
        Assumptions.assumeTrue(
                binary.isPresent() && chrome.isPresent(),
                "skipped: composite binary or Chrome is not present"
        );
        Path page = temp.resolve("page.html");
        Files.writeString(page, """
                <!DOCTYPE html>
                <html><head><title>composite</title></head>
                <body><h1 id="marker">pickleball-composite-marker</h1></body></html>
                """);
        Path profile = temp.resolve("chrome-profile");
        Files.createDirectories(profile);
        int port = freePort();
        Process browser = new ProcessBuilder(
                chrome.orElseThrow().toString(),
                "--headless=new",
                "--no-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--no-first-run",
                "--remote-debugging-port=" + port,
                "--user-data-dir=" + profile,
                page.toUri().toString()
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        DirectControlProvider.Handle provider = null;
        try {
            assertTrue(waitForDebugger(port), "Chrome debugger did not open");
            publishPaused();
            DirectControlProvider.Outcome outcome = DirectControlProvider.open(new DirectControlProvider.Request(
                    true,
                    binary.orElseThrow().toString(),
                    temp,
                    "run-1",
                    "session-1",
                    "127.0.0.1:" + port,
                    descriptor(),
                    log(),
                    binary.orElseThrow(),
                    null
            ));
            assertEquals(DirectControlProvider.Kind.STARTED, outcome.kind());
            provider = outcome.handle();
            assertTrue(provider.alive());
            assertTrue(provider.attachJson().contains("\"launched\": false")
                    || provider.attachJson().contains("\"launched\":false"));
            assertTrue(browser.isAlive());
            String query = query(binary.orElseThrow(), provider, "#marker");
            assertTrue(query.contains("pickleball-composite-marker") || query.contains("\"count\": 1")
                    || query.contains("\"count\":1"), query);
            provider.detach();
            provider = null;
            assertTrue(browser.isAlive(), "session close must leave Chrome running");
            assertTrue(waitForDebugger(port), "Chrome debugger must still answer after close");
            SessionDescriptor published = SessionDescriptorWriter.read(temp, "run-1").orElseThrow();
            assertEquals(
                    DirectControlProvider.sessionDirectory(temp, "run-1").toString(),
                    published.compositeSessionDir()
            );
            assertEquals("session-1", published.compositeSessionId());
        } finally {
            if (provider != null) provider.detach();
            if (browser.isAlive()) {
                browser.destroy();
                if (!browser.waitFor(5, TimeUnit.SECONDS)) browser.destroyForcibly();
            }
        }
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

    private Path fakeComposite() throws IOException {
        return script("""
                #!/bin/sh
                echo "$@" >> "$0.calls"
                dir=""
                id=""
                op=""
                prev=""
                for arg in "$@"; do
                  if [ "$prev" = "--session-dir" ]; then dir=$arg; fi
                  if [ "$prev" = "--id" ]; then id=$arg; fi
                  if [ "$prev" = "session" ]; then op=$arg; fi
                  prev=$arg
                done
                if [ -z "$dir" ] || [ -z "$id" ] || [ -z "$op" ]; then
                  echo "missing args" >&2
                  exit 2
                fi
                mkdir -p "$dir"
                if [ "$op" = "attach" ]; then
                  printf '%s\\n' "{\\"joined\\":\\"$id\\",\\"sessions\\":{\\"$id\\":{\\"id\\":\\"$id\\",\\"launched\\":false}}}" > "$dir/registry.json"
                  printf '%s\\n' "{\\"id\\":\\"$id\\",\\"cdp\\":\\"http://127.0.0.1:9222\\",\\"launched\\":false,\\"pid\\":null,\\"profile\\":null,\\"allowlist\\":[],\\"created\\":\\"t\\",\\"process\\":false,\\"url\\":null,\\"kind\\":\\"browser\\",\\"backend\\":\\"\\",\\"app_name\\":\\"\\",\\"fixture\\":null}"
                  exit 0
                fi
                if [ "$op" = "close" ]; then
                  printf '%s\\n' '{"joined":null,"sessions":{}}' > "$dir/registry.json"
                  printf '%s\\n' "{\\"id\\":\\"$id\\",\\"closed\\":true,\\"detached\\":true,\\"killed\\":false,\\"launched\\":false}"
                  exit 0
                fi
                echo "unexpected $op" >&2
                exit 2
                """);
    }

    private void publishPaused() {
        Instant now = Instant.parse("2026-10-29T16:00:00Z");
        SessionDescriptorWriter.publish(temp, new SessionDescriptor(
                "session-1",
                "run-1",
                temp.resolve("workspace").toAbsolutePath().normalize().toString(),
                "chrome",
                "127.0.0.1:9222",
                "https://example.test/",
                List.of("window-a"),
                SessionDescriptor.State.PAUSED,
                now,
                now
        ), true);
    }

    private static Path calls(Path script) {
        return Path.of(script.toString() + ".calls");
    }

    private static String query(Path binary, DirectControlProvider.Handle provider, String css) throws Exception {
        Path out = Files.createTempFile("composite-query", ".out");
        try {
            Process process = new ProcessBuilder(
                    binary.toString(),
                    "--session-dir", provider.sessionDirectory().toString(),
                    "query",
                    "--session", provider.compositeSessionId(),
                    "--css", css
            ).redirectOutput(out.toFile()).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            String output = Files.readString(out);
            assertTrue(finished && process.exitValue() == 0, output);
            return output;
        } finally {
            Files.deleteIfExists(out);
        }
    }

    private static boolean waitForDebugger(int port) throws Exception {
        URI version = URI.create("http://127.0.0.1:" + port + "/json/version");
        for (int i = 0; i < 50; i++) {
            try {
                HttpURLConnection connection = (HttpURLConnection) version.toURL().openConnection();
                connection.setConnectTimeout(200);
                connection.setReadTimeout(200);
                if (connection.getResponseCode() == 200) return true;
            } catch (IOException ignored) {
                // Chrome is still starting.
            }
            Thread.sleep(100);
        }
        return false;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Optional<Path> chromeBinary() throws IOException {
        List<Path> candidates = new ArrayList<>();
        String named = System.getenv("CHROME_BIN");
        if (named != null && !named.isBlank()) candidates.add(Path.of(named));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(":")) {
                for (String name : List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome")) {
                    candidates.add(Path.of(dir, name));
                }
            }
        }
        Path browsers = Path.of("/opt/pw-browsers");
        if (Files.isDirectory(browsers)) {
            try (Stream<Path> walk = Files.walk(browsers, 5)) {
                walk.filter(file -> {
                    String name = file.getFileName().toString();
                    return Files.isExecutable(file) && (name.equals("chrome") || name.equals("chrome-headless-shell") || name.equals("chromium"));
                }).forEach(candidates::add);
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
