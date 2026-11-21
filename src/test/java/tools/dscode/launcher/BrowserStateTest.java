package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentfast.AgentEnvelope;
import tools.dscode.control.protocol.LiveHostFile;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dead Chrome is browser down. An expired hold is not held. live-stop clears the session. */
class BrowserStateTest {
    @TempDir
    Path temp;

    @Test
    void deadChromeIsDownEvenWhenTheHostJvmIsAlive() throws Exception {
        Process finished = new ProcessBuilder("true").start();
        finished.waitFor();
        long host = ProcessHandle.current().pid();
        LiveHostFile.write(temp, new LiveHostFile.Host(
                "http://127.0.0.1:9", "token", 9, host, finished.pid(),
                "still-open", false, "chrome-headless", true, "", true, 1L, 1L
        ));
        Map<String, Object> state = stateOf(AgentEnvelope.apply(Map.of("ok", true), 0, "ui-poll", temp, "still-open"));
        assertEquals("down", state.get("browser"));
        assertEquals("none", state.get("lease"));

        LiveHostFile.write(temp, new LiveHostFile.Host(
                "http://127.0.0.1:9", "token", 9, host, host,
                "still-open", false, "chrome-headless", true, "", true, 1L, 1L
        ));
        Map<String, Object> live = stateOf(AgentEnvelope.apply(Map.of("ok", true), 0, "ui-poll", temp, "still-open"));
        assertEquals("up", live.get("browser"));
        assertEquals("none", live.get("lease"));
    }

    @Test
    void expiredHoldIsNotHeldAndAFreshHoldIs() throws Exception {
        long host = ProcessHandle.current().pid();
        LiveHostFile.write(temp, new LiveHostFile.Host(
                "http://127.0.0.1:9", "token", 9, host, host,
                "held-run", false, "chrome", true, "", true, 1L, 1L
        ));
        Path hold = temp.resolve(".pickleball/live-browser/hold.json");
        Files.createDirectories(hold.getParent());
        Files.createDirectories(temp.resolve(".pickleball/workbench"));
        Files.writeString(hold, "{\"forever\":false,\"expires\":1}\n");
        Files.writeString(temp.resolve(".pickleball/workbench/pause-hold"), "30s\n");
        Map<String, Object> expired = stateOf(AgentEnvelope.apply(Map.of("ok", true), 0, "doctor", temp, "held-run"));
        assertEquals("up", expired.get("browser"));
        assertEquals("none", expired.get("lease"));
        assertEquals("0", LeaseHold.left(temp));

        Capture doctor = run("doctor");
        assertEquals(0, doctor.code, doctor.err);
        assertTrue(doctor.out.contains("lease=open"), doctor.out);
        assertTrue(doctor.out.contains("leaseLeft=0"), doctor.out);
        assertTrue(doctor.out.contains("chrome=present"), doctor.out);

        LeaseHold.arm(temp, "30s");
        Map<String, Object> fresh = stateOf(AgentEnvelope.apply(Map.of("ok", true), 0, "doctor", temp, "held-run"));
        assertEquals("held", fresh.get("lease"));
        Capture held = run("doctor");
        assertTrue(held.out.contains("lease=held"), held.out);
        assertFalse(held.out.contains("leaseLeft=0"), held.out);
    }

    @Test
    void liveStopClearsSessionAndChromePresence() throws Exception {
        Process finished = new ProcessBuilder("true").start();
        finished.waitFor();
        LiveHostFile.write(temp, new LiveHostFile.Host(
                "http://127.0.0.1:9", "token", 9, finished.pid(), finished.pid(),
                "gone", false, "chrome", false, "", false, 1L, 1L
        ));
        Path session = temp.resolve(".pickleball/consumer-session.json");
        Files.createDirectories(session.getParent());
        Files.writeString(session, "{\"id\":\"gone\",\"live\":true}\n");

        Capture stopped = run("live-stop", "--all");
        assertEquals(0, stopped.code, stopped.err + stopped.out);
        assertFalse(Files.exists(session), "session file remains");
        assertFalse(Files.exists(LiveHostFile.file(temp)));

        Capture doctor = run("doctor");
        assertEquals(0, doctor.code, doctor.err);
        assertTrue(doctor.out.contains("session=absent"), doctor.out);
        assertTrue(doctor.out.contains("chrome=absent"), doctor.out);
        assertTrue(doctor.out.contains("lease=open"), doctor.out);
        assertTrue(doctor.out.contains("health=absent") || doctor.out.contains("health=down"), doctor.out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stateOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("state");
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String[] command = new String[args.length + 1];
        command[0] = args[0];
        command[1] = temp.toString();
        System.arraycopy(args, 1, command, 2, args.length - 1);
        int code = WorkbenchAgentCommands.run(
                command,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
