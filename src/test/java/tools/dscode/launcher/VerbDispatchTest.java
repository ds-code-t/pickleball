package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.McpTransport;
import tools.dscode.control.protocol.VerbArgs;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerbDispatchTest {
    @TempDir
    Path project;

    @Test
    void storeStatusJsonRunsInProcess() throws Exception {
        var before = liveChildren();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put(VerbArgs.ARGS, "status");
        flags.put("json", "true");
        flags.put("project", project.toString());
        int code = VerbDispatch.run(
                "store",
                flags,
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, errBytes.toString(StandardCharsets.UTF_8));
        String out = outBytes.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("\"database\""), out);
        assertEquals(before, liveChildren(), out);
    }

    @Test
    void cliTransportIsOptInAndDirectIsTheDefault() {
        String previous = System.getProperty(McpTransport.PROPERTY);
        try {
            System.clearProperty(McpTransport.PROPERTY);
            assertTrue(McpTransport.direct());
            assertFalse(McpTransport.cli());
            assertEquals(McpTransport.DIRECT, McpTransport.name());
            System.setProperty(McpTransport.PROPERTY, "cli");
            assertTrue(McpTransport.cli());
            assertFalse(McpTransport.direct());
            System.setProperty(McpTransport.PROPERTY, "CLI");
            assertTrue(McpTransport.cli());
            System.setProperty(McpTransport.PROPERTY, "nope");
            assertTrue(McpTransport.direct());
            System.setProperty(McpTransport.PROPERTY, " ");
            assertTrue(McpTransport.direct());
        } finally {
            if (previous == null) System.clearProperty(McpTransport.PROPERTY);
            else System.setProperty(McpTransport.PROPERTY, previous);
        }
    }

    @Test
    void onceDropsFollowOnAStreamVerb() {
        Map<String, String> flags = new LinkedHashMap<>();
        flags.put("follow", "true");
        flags.put("once", "true");
        flags.put(VerbArgs.ARGS, "tail");
        List<String> argv = List.of(VerbDispatch.argvFor("log", flags));
        assertFalse(argv.contains("--follow"));
        assertFalse(argv.contains("--once"));
        assertTrue(argv.contains("log"));
        assertTrue(argv.contains("tail"));
    }

    @Test
    void dispatchLogNamesTheVerbAndSkipsFlagValues() throws Exception {
        String previous = System.getProperty("pkb_engine_log");
        try {
            System.setProperty("pkb_engine_log", "dispatch");
            Map<String, String> flags = new LinkedHashMap<>();
            flags.put(VerbArgs.ARGS, "status");
            flags.put("json", "true");
            flags.put("project", project.toString());
            flags.put("password", "super-secret-value");
            VerbDispatch.run("store", flags, new PrintStream(new ByteArrayOutputStream()), new PrintStream(new ByteArrayOutputStream()));
            String log = Files.readString(project.resolve(".pickleball/engine/engine.log"));
            assertTrue(log.contains("dispatch"), log);
            assertTrue(log.contains("store"), log);
            assertTrue(log.contains("file=no"), log);
            assertFalse(log.contains("super-secret-value"), log);
        } finally {
            if (previous == null) System.clearProperty("pkb_engine_log");
            else System.setProperty("pkb_engine_log", previous);
        }
    }

    private static List<Long> liveChildren() {
        return ProcessHandle.current().children()
                .filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid)
                .sorted()
                .collect(Collectors.toList());
    }
}
