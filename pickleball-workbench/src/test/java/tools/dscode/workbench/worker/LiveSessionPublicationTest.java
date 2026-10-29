package tools.dscode.workbench.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.DirectControlProvider;
import tools.dscode.control.protocol.RunView;
import tools.dscode.control.protocol.SessionDescriptor;
import tools.dscode.control.protocol.SessionDescriptorWriter;
import tools.dscode.control.protocol.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveSessionPublicationTest {
    @TempDir
    Path temp;

    @Test
    void isolateAndGuiPathPublishesCdpAndDetachesWithoutKillingTheBrowser() throws Exception {
        String previous = System.getProperty(DirectControlProvider.KEY);
        String member = System.getProperty(DirectControlProvider.RUNVARS_PROPERTY);
        System.clearProperty(DirectControlProvider.KEY);
        System.clearProperty(DirectControlProvider.RUNVARS_PROPERTY);
        Process browser = null;
        LiveSessionPublication publication = null;
        try {
            Path project = temp.toAbsolutePath().normalize();
            Path record = RunView.runDirectory(project, "run-1").resolve("record.json");
            Files.createDirectories(record.getParent());
            Files.writeString(record, "{\"runId\":\"run-1\",\"status\":\"RUNNING\"}\n");
            publication = new LiveSessionPublication(project, Map.of(
                    "pkb_run_id", "run-1",
                    "pkb_workspace", project.resolve("workspace").toString()
            ));
            publication.opened("session-1");
            SessionDescriptor opened = SessionDescriptorWriter.read(project, "run-1").orElseThrow();
            assertEquals(SessionDescriptor.State.PAUSED, opened.state());
            assertEquals("session-1", opened.sessionId());
            assertNull(opened.cdpDebuggerAddress());
            assertTrue(Files.readString(record).contains("sessionDescriptor"));

            publication.beforeStep();
            assertEquals(SessionDescriptor.State.RUNNING, SessionDescriptorWriter.read(project, "run-1").orElseThrow().state());
            publication.afterStep(new LiveSessionPublication.Page(
                    "chrome", "127.0.0.1:9222", "https://example.test/home", List.of("window-a")
            ));
            SessionDescriptor refreshed = SessionDescriptorWriter.read(project, "run-1").orElseThrow();
            assertEquals("127.0.0.1:9222", refreshed.cdpDebuggerAddress());
            assertEquals("chrome", refreshed.browserType());
            assertEquals("https://example.test/home", refreshed.currentUrl());
            assertEquals(List.of("window-a"), refreshed.windowHandles());
            assertEquals(SessionDescriptor.State.PAUSED, refreshed.state());
            assertEquals(opened.startedAt(), refreshed.startedAt());
            assertFalse(Files.isRegularFile(SessionDescriptorWriter.file(project, "run-1").getParent().resolve("provider.log")));

            Path script = project.resolve("composite-test");
            Files.writeString(script, """
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
                    mkdir -p "$dir"
                    if [ "$op" = "attach" ]; then
                      printf '%s\\n' "{\\"joined\\":\\"$id\\",\\"sessions\\":{\\"$id\\":{\\"id\\":\\"$id\\",\\"launched\\":false}}}" > "$dir/registry.json"
                      printf '%s\\n' "{\\"id\\":\\"$id\\",\\"launched\\":false,\\"pid\\":null}"
                      exit 0
                    fi
                    if [ "$op" = "close" ]; then
                      printf '%s\\n' '{"joined":null,"sessions":{}}' > "$dir/registry.json"
                      printf '%s\\n' "{\\"closed\\":true,\\"detached\\":true,\\"killed\\":false}"
                      exit 0
                    fi
                    echo "unexpected $op" >&2
                    exit 2
                    """);
            assertTrue(script.toFile().setExecutable(true));
            Workspace.writeRunVars(
                    Workspace.directory(project, "run-1"),
                    Map.of(DirectControlProvider.KEY, script.toString())
            );
            browser = new ProcessBuilder("sleep", "30").start();
            publication.afterStep(new LiveSessionPublication.Page(
                    "chrome", "127.0.0.1:9222", "https://example.test/home", List.of("window-a")
            ));
            String log = Files.readString(SessionDescriptorWriter.file(project, "run-1").getParent().resolve("provider.log"));
            assertTrue(log.contains("\"launched\":false"));
            assertFalse(log.contains("PICKLEBALL_SESSION_DESCRIPTOR"));
            SessionDescriptor attached = SessionDescriptorWriter.read(project, "run-1").orElseThrow();
            Path sessionDir = DirectControlProvider.sessionDirectory(project, "run-1");
            assertEquals(sessionDir.toString(), attached.compositeSessionDir());
            assertEquals("session-1", attached.compositeSessionId());
            assertTrue(browser.isAlive());
            publication.afterStep(new LiveSessionPublication.Page(
                    "chrome", "127.0.0.1:9222", "https://example.test/next", List.of("window-a", "window-b")
            ));
            SessionDescriptor kept = SessionDescriptorWriter.read(project, "run-1").orElseThrow();
            assertEquals(sessionDir.toString(), kept.compositeSessionDir());
            assertEquals("session-1", kept.compositeSessionId());
            assertEquals("https://example.test/next", kept.currentUrl());
            publication.closing();
            publication = null;
            assertTrue(browser.isAlive(), "provider detach must leave the browser running");
            String calls = Files.readString(Path.of(script.toString() + ".calls"));
            assertTrue(calls.contains("session attach"));
            assertTrue(calls.contains("session close"));
            assertTrue(calls.contains("--session-dir"));
            assertEquals(
                    SessionDescriptor.State.STOPPED,
                    SessionDescriptorWriter.read(project, "run-1").orElseThrow().state()
            );
        } finally {
            if (publication != null) publication.closing();
            if (browser != null) browser.destroyForcibly();
            restore(DirectControlProvider.KEY, previous);
            restore(DirectControlProvider.RUNVARS_PROPERTY, member);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
