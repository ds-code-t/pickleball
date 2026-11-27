package tools.dscode.engine.runtime;

import io.cucumber.java.en.Given;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.match.JavaStepHost;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.launcher.SpineCli;
import tools.dscode.store.StoreRun;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarmColdParityTest {
    public static class EchoGlue {
        @Given("native engine echo {string}")
        public String echo(String value) {
            return value;
        }
    }

    private static final String FEATURE = """
            Feature: Echo
              Scenario: Hi
                Given native engine echo "hi"
            """;

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        System.clearProperty("pkb_warm");
        WarmSpine.shared().reset();
        if (WarmProcessFile.alive(dir)) {
            SpineCli.execute(new String[] {"spine", "stop", "--project=" + dir}, sink(), sink());
        }
    }

    @Test
    void theSameScenarioPassesColdAndWarm() {
        System.setProperty("pkb_warm", "off");
        RunResult cold = PickleballEngine.run(FEATURE, EngineOptions.of(EchoGlue.class).project(dir));
        assertEquals(Status.PASSED, cold.status());
        assertEquals("hi", cold.scenarios().getFirst().steps().getFirst().returned());
        ClassLoader application = JavaStepHost.selectLoader(EchoGlue.class.getName(), dir);
        assertFalse(application instanceof WarmClassLoader);

        System.setProperty("pkb_warm", "on");
        RunResult warm = PickleballEngine.run(FEATURE, EngineOptions.of(EchoGlue.class).project(dir));
        assertEquals(Status.PASSED, warm.status());
        assertEquals("hi", warm.scenarios().getFirst().steps().getFirst().returned());
        ClassLoader loader = JavaStepHost.selectLoader(EchoGlue.class.getName(), dir);
        assertTrue(loader instanceof WarmClassLoader);
    }

    @Test
    void spineStartStatusAndStopOnAnEmptyProjectStayOffTheWrapper() throws Exception {
        List<String> cold = StoreRun.coldUnlessSpine(dir, List.of("-Dpkb_warm=on", "-Dpkb_warm_packages=glue"));
        assertTrue(cold.stream().noneMatch(token -> token.startsWith("-Dpkb_warm")));

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int started = SpineCli.execute(
                new String[] {"spine", "start", "--project=" + dir},
                new PrintStream(stdout),
                new PrintStream(stderr));
        assertEquals(0, started, stderr.toString(StandardCharsets.UTF_8));
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("warm=on"), out);
        assertTrue(Files.isRegularFile(dir.resolve(".pickleball/spine/warm.json")));
        assertFalse(Files.exists(dir.resolve(".pickleball/spine/wrapper-compiled")));
        assertTrue(WarmProcessFile.alive(dir));
        assertTrue(StoreRun.coldUnlessSpine(dir, List.of("-Dpkb_warm=on")).contains("-Dpkb_warm=on"));

        ByteArrayOutputStream statusOut = new ByteArrayOutputStream();
        assertEquals(0, SpineCli.execute(
                new String[] {"spine", "warm", "status", "--project=" + dir},
                new PrintStream(statusOut),
                sink()));
        String status = statusOut.toString(StandardCharsets.UTF_8);
        assertTrue(status.contains("warm=on"), status);
        assertTrue(status.contains("pid="), status);
        assertFalse(status.contains("pid=\n") && !status.contains("pid=" + WarmProcessFile.read(dir).orElseThrow().pid()));

        ByteArrayOutputStream stoppedOut = new ByteArrayOutputStream();
        assertEquals(0, SpineCli.execute(
                new String[] {"spine", "stop", "--project=" + dir},
                new PrintStream(stoppedOut),
                sink()));
        String stopped = stoppedOut.toString(StandardCharsets.UTF_8);
        assertTrue(stopped.contains("warm=off"), stopped);
        assertTrue(stopped.contains("pid="), stopped);
        assertFalse(WarmProcessFile.alive(dir));
        assertFalse(Files.exists(dir.resolve(".pickleball/spine/warm.json")));
    }

    @Test
    void sessionStaysOutOfThisClass() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(2, SpineCli.execute(new String[] {"spine", "session"}, sink(), new PrintStream(err)));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unknown spine command session"));
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
