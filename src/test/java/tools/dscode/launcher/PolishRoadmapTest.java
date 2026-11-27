package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;
import tools.dscode.common.agentfast.PushChannel;

import tools.dscode.testsupport.PortableProcess;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolishRoadmapTest {
    @TempDir
    Path temp;

    @Test
    void helpNamesTheFlagsAgentsNeed() {
        assertTrue(page("events").contains("--follow"));
        assertTrue(page("events").contains("--until"));
        assertTrue(page("export-guidance").contains("--warm"));
        assertTrue(page("live-start").contains("live-start is start"));
        assertTrue(page("live-stop").contains("--all"));
        assertTrue(page("inspect-page").contains("--format=lines|json"));
        assertTrue(page("find").contains("--fields"));
        assertTrue(page("chat-poll").contains("--echo=hide"));
        assertTrue(page("ui-poll").contains("--group=type|role"));
        assertTrue(page("overlay-poll").contains("--priority=on"));
        assertTrue(page("act").contains("--pick"));
        assertTrue(page("chat-turn").contains("--until"));
        assertTrue(page("doctor").contains("orphans"));
        assertTrue(page("call").contains("--args-file"));
        assertTrue(page("frame").contains("--enter"));
        assertTrue(page("selftest").contains("--component"));
    }

    @Test
    void liveStopStaysOutsideTheController() {
        assertTrue(AgentHop.shouldHop("start", new String[]{"start", "--url=fixture:page", "--browser=chrome"}));
        assertTrue(!AgentHop.shouldHop("start", new String[]{"start", "--stop", "--all"}));
        assertTrue(!AgentHop.shouldHop("start", new String[]{"live-stop", "--all"}));
        assertTrue(!AgentHop.shouldHop("events", new String[]{"events", "--follow"}));
        assertTrue(!AgentHop.shouldHop("export-guidance", new String[]{"export-guidance", "--warm"}));
        assertTrue(AgentHop.shouldHop("doctor", new String[]{"doctor"}));
    }

    @Test
    void followExitsOnTheFirstPushedLine() {
        PushChannel.accept(temp, Map.of("type", "chat", "source", "agent", "text", "echo"));
        PushChannel.accept(temp, Map.of("type", "chat", "source", "human", "text", "ready now"));
        Capture capture = session(
                "events", temp.toString(), "--follow", "--timeout=0", "--until=text=ready", "--type=chat", "--cursor=0");
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("ready now"), capture.out);
        assertTrue(capture.out.contains("\"op\":\"events\"") || capture.out.contains("\"op\": \"events\""), capture.out);
        assertTrue(!capture.out.contains("echo") || capture.out.indexOf("ready now") < capture.out.indexOf("\"text\":\"echo\""), capture.out);
    }

    @Test
    void liveStopAllStopsTheControllerHandle() {
        Capture capture = agent("live-stop", "--all", temp.toString());
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("orphans="), capture.out);
        assertTrue(capture.out.contains("controller=stopped"), capture.out);
        assertTrue(capture.out.contains("stopped=false"), capture.out);
        assertTrue(!capture.err.contains("notice: live-stop stops the start host"), capture.err);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void warmPingIsUnderOneSecond() throws Exception {
        Path guidance = temp.resolve(".pickleball");
        Files.createDirectories(guidance);
        SpineSession.create(guidance, "agent-1");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path argFile = temp.resolve("warm-ping.args");
        String classpath = System.getProperty("java.class.path", "").replace('\\', '/').replace("\"", "\\\"");
        String guidanceArg = guidance.toAbsolutePath().toString().replace('\\', '/').replace("\"", "\\\"");
        Files.writeString(argFile, "-cp\n\"" + classpath + "\"\n"
                + "tools.dscode.launcher.PickleballWorkbenchLauncher\n"
                + "export-guidance\n\"" + guidanceArg + "\"\n--warm\n", StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(java, "@" + argFile.toAbsolutePath())
                .directory(temp.toFile()).start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread outPump = pump(process.getInputStream(), stdout);
        Thread errPump = pump(process.getErrorStream(), stderr);
        boolean finished = process.waitFor(80, TimeUnit.SECONDS);
        if (!finished) process.destroyForcibly();
        outPump.join(5_000);
        errPump.join(5_000);
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(finished, err);
        assertEquals(0, process.exitValue(), out + err);
        assertTrue(out.contains("warm=true"), out);
        assertTrue(out.contains("pingExit=0"), out + err);
        int pingMs = Integer.parseInt(out.replaceAll("(?s).*pingMs=", "").replaceAll("\\D.*", ""));
        assertTrue(pingMs >= 0 && pingMs <= 1000, "pingMs=" + pingMs + "\n" + out);
        agent("controller-stop", temp.toString());
        PortableProcess.releaseFile(temp.resolve(".pickleball/agent-controller.log"));
    }

    private static String page(String command) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int code = WorkbenchCliHelp.answer(
                new String[]{"help", command},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, command);
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private static Capture agent(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static Capture session(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchSessionCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private static Thread pump(java.io.InputStream input, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            try (input) {
                input.transferTo(sink);
            } catch (java.io.IOException ignored) {
                // The process ending closes the pipe.
            }
        });
        thread.start();
        return thread;
    }

    private record Capture(int code, String out, String err) {
    }
}
