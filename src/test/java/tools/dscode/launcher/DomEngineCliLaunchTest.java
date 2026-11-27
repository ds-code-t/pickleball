package tools.dscode.launcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.SpineSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomEngineCliLaunchTest {
    @TempDir
    Path project;

    @Test
    void launcherForwardsDomengineAndLeavesOtherCommandsAlone() throws Exception {
        SpineSession.create(project, "agent-1");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = PickleballWorkbenchLauncher.dispatchDomEngineIfAny(
                new String[]{"domengine", "version", "--project=" + project},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, code, stderr.toString(StandardCharsets.UTF_8));
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("3.0.0"), text);
        JsonNode envelope = new ObjectMapper().readTree(text);
        assertEquals(1, envelope.path("v").asInt(), text);
        assertEquals("domengine version", envelope.path("op").asText(), text);
        assertFalse(envelope.has("command"), text);
        assertFalse(envelope.has("count"), text);
        assertFalse(envelope.has("total"), text);
        assertFalse(envelope.has("rows"), text);
        assertEquals("3.0.0", envelope.path("items").path(0).path("version").asText(), text);
        assertEquals(-1, PickleballWorkbenchLauncher.dispatchDomEngineIfAny(
                new String[]{"engine", "help"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())
        ));
    }

    @Test
    void missingDomEngineClassesUseTheClasspathForm() throws Exception {
        SpineSession.create(project, "agent-1");
        PickleballWorkbenchLauncher.domEngineCall = rest -> {
            throw new NoClassDefFoundError("com/fasterxml/jackson/databind/ObjectMapper");
        };
        try {
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            int code = PickleballWorkbenchLauncher.dispatchDomEngineIfAny(
                    new String[]{"domengine", "query", "--project=" + project},
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                    new PrintStream(stderr, true, StandardCharsets.UTF_8)
            );
            String text = stderr.toString(StandardCharsets.UTF_8);
            assertEquals(1, code, text);
            assertTrue(text.contains("java -cp <classpath> tools.dscode.launcher.PickleballWorkbenchLauncher domengine"), text);
            assertTrue(text.contains("thin"), text);
            assertFalse(text.contains("build a fat jar"), text);
        } finally {
            PickleballWorkbenchLauncher.resetDomEngineCall();
        }
    }
}
