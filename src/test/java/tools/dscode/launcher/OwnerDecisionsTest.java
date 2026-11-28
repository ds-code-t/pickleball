package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveBridgeFile;
import tools.dscode.control.protocol.LiveHostFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner decisions, Dan 2026-11-28 (t1239u): start --run-id, progress session, doctor gate, doctor --prune. */
class OwnerDecisionsTest {
    @TempDir
    Path project;

    @Test
    void startUsesTheGivenRunIdAndMakesOneOnlyWhenNoneIsGiven() {
        assertEquals("agent-7", ConsumerReadyCommands.liveId("agent-7"));
        assertTrue(ConsumerReadyCommands.liveId(null).matches("live[0-9a-f]{12}"));
        assertTrue(ConsumerReadyCommands.liveId(" ").startsWith("live"));
        assertNull(ConsumerReadyCommands.liveId("../escape"), "an unsafe id is a usage error, not a path");
    }

    @Test
    void progressNamesTheRealSession() {
        assertEquals("s-flag", WorkbenchAgentCommands.progressSession("s-flag", "run-a", "live1"));
        assertEquals("run-a", WorkbenchAgentCommands.progressSession(null, "run-a", "live1"));
        assertEquals("live1", WorkbenchAgentCommands.progressSession(null, "session", "live1"));
        assertEquals("session", WorkbenchAgentCommands.progressSession(null, "session", null));
    }

    @Test
    void readOnlyDoctorSkipsTheSessionGateAndPruneDoesNot() {
        assertTrue(VerbDispatch.sessionExempt(new String[]{"doctor"}));
        assertTrue(VerbDispatch.sessionExempt(new String[]{"doctor", "--attach", "/tmp/p"}));
        assertTrue(VerbDispatch.sessionExempt(new String[]{"doctor", "--desktop"}));
        assertFalse(VerbDispatch.sessionExempt(new String[]{"doctor", "--prune"}), "--prune deletes records");
        assertFalse(VerbDispatch.sessionExempt(new String[]{"doctor", "--prune=true"}));
        assertFalse(VerbDispatch.sessionExempt(new String[]{"snap"}), "other verbs keep the gate");
    }

    @Test
    void pruneRemovesOnlyRecordsWhoseProcessIsGone() throws Exception {
        long dead = deadPid();
        LiveHostFile.write(project, new LiveHostFile.Host("http://127.0.0.1:1", "t", 1, dead, 0L, "live1", false,
                "chrome-headless", true, "", true, 0L, 0L));
        Path session = project.resolve(".pickleball").resolve(ConsumerReadyCommands.SESSION_NAME);
        Files.writeString(session, "{\"id\":\"live1\"}");
        assertEquals("live1", ConsumerReadyCommands.liveSessionId(project));
        LiveBridgeFile.write(project, new LiveBridgeFile.Bridge("127.0.0.1", 2, "t", ProcessHandle.current().pid(), "w1"));

        List<String> removed = ConsumerReadyCommands.prune(project);
        assertEquals(2, removed.size(), removed.toString());
        assertTrue(removed.get(0).contains("live-browser/current.json") && removed.get(0).contains("gone"), removed.toString());
        assertFalse(Files.exists(LiveHostFile.file(project)));
        assertTrue(Files.exists(LiveBridgeFile.file(project)), "a live worker record is never touched");
        assertFalse(Files.exists(session));
        assertTrue(ConsumerReadyCommands.prune(project).isEmpty(), "a second prune removes nothing");
    }

    @Test
    void pruneLeavesALiveHostAlone() throws Exception {
        LiveHostFile.write(project, new LiveHostFile.Host("http://127.0.0.1:1", "t", 1, ProcessHandle.current().pid(), 0L,
                "live1", false, "chrome-headless", true, "", true, 0L, 0L));
        assertTrue(ConsumerReadyCommands.prune(project).isEmpty());
        assertTrue(Files.exists(LiveHostFile.file(project)));
    }

    private static long deadPid() throws Exception {
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
        long pid = process.pid();
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        return pid;
    }
}
