package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupVerbCoverageTest {
    @TempDir
    Path project;

    @AfterEach
    void clear() {
        System.clearProperty("pkb_agent_popup_headless");
    }

    @Test
    void covers_command_popup_open() {
        System.setProperty("pkb_agent_popup_headless", "true");
        Capture capture = agent("popup-open", project.toString(), "--run-id=r1", "--headless=true");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("\"op\":\"popup-status\"") || capture.out.contains("popup-open"), capture.out);
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("popup-open"));
    }

    @Test
    void covers_command_popup_status() {
        Capture capture = agent("popup-status", project.toString(), "--run-id=r1");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("\"op\":\"popup-status\""), capture.out);
    }

    @Test
    void covers_command_popup_close() {
        Capture capture = agent("popup-close", project.toString(), "--run-id=r1");
        assertEquals(0, capture.code, capture.err + capture.out);
        assertTrue(capture.out.contains("popup-close"), capture.out);
        assertTrue(WorkbenchCliHelp.catalog().contains("popup-open popup-status popup-close"));
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

    private record Capture(int code, String out, String err) {
    }
}