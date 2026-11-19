package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.domengine.session.BrowserSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.launcher.WorkbenchAgentCommands;

import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelftestCpuBenchTest {
    @TempDir
    Path temp;

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void selftestUiCpuStaysUnderATenthOfACore() throws Exception {
        Assumptions.assumeTrue(BrowserSession.findChrome() != null, "skipped: no Chrome binary on this machine");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"selftest", temp.toString(), "ui", "--cpu"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, out + err);
        assertFalse(out.contains("\"base64\""), out);
        JsonNode node = new ObjectMapper().readTree(out);
        assertTrue(node.path("elapsedMs").asLong(-1) >= 0, out);
        assertEquals(!GraphicsEnvironment.isHeadless(), node.path("headed").asBoolean(!GraphicsEnvironment.isHeadless()), out);
        double cores = node.path("cores").asDouble(1);
        long p95 = node.path("p95Ms").asLong(9_999);
        System.out.println("MEASURE cores=" + cores + " p95Ms=" + p95 + " elapsedMs=" + node.path("elapsedMs").asLong());
        assertTrue(cores < 0.1, "cores=" + cores + " ticks=" + node.path("ticks") + " " + out);
        assertTrue(p95 <= 150, "p95Ms=" + p95 + " " + out);
    }
}
