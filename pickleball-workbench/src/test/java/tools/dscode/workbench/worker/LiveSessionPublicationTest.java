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
            Files.writeString(script, "#!/bin/sh\necho descriptor=$PICKLEBALL_SESSION_DESCRIPTOR\nsleep 30\n");
            assertTrue(script.toFile().setExecutable(true));
            Workspace.writeRunVars(
                    Workspace.directory(project, "run-1"),
                    Map.of(DirectControlProvider.KEY, script.toString())
            );
            browser = new ProcessBuilder("sleep", "30").start();
            publication.afterStep(new LiveSessionPublication.Page(
                    "chrome", "127.0.0.1:9222", "https://example.test/home", List.of("window-a")
            ));
            String log = waitForLog(SessionDescriptorWriter.file(project, "run-1").getParent().resolve("provider.log"));
            assertTrue(log.contains("descriptor="));
            assertTrue(browser.isAlive());
            publication.closing();
            publication = null;
            assertTrue(browser.isAlive(), "provider detach must leave the browser running");
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

    private static String waitForLog(Path file) throws Exception {
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
