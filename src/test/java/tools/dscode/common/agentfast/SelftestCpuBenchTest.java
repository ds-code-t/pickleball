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
import java.util.List;
import java.util.Optional;
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

    @Test
    void cpuSampleWithoutProcUsesNanos() {
        long pid = ProcessHandle.current().pid();
        SelftestUi.CpuSample sample = SelftestUi.cpuSample(pid, false);
        boolean hasNanos = ProcessHandle.current().info().totalCpuDuration().isPresent();
        assertEquals(hasNanos, sample.supported());
        if (hasNanos) assertFalse(sample.ticks());
    }

    @Test
    void emptyHandleClockSumsThePsTree() {
        List<String> rows = List.of(
                "10 1 0:00.10",
                "11 10 0:00.20",
                "12 11 0:00.30",
                "13 9 1:00.00"
        );
        SelftestUi.CpuSample sample = SelftestUi.cpuSample(10L, Optional.empty(), rows);
        assertTrue(sample.supported());
        assertFalse(sample.ticks());
        assertEquals(600_000_000L, sample.nanos());
        SelftestUi.CpuSample handle = SelftestUi.cpuSample(10L, Optional.of(5L), rows);
        assertEquals(5L, handle.nanos());
        assertFalse(SelftestUi.cpuSample(99L, Optional.empty(), rows).supported());
    }

    @Test
    void psCpuTextParsesMacCentisecondsAndLinuxDays() {
        assertEquals(10_000_000L, SelftestUi.parsePsCpu("0:00.01"));
        assertEquals(224 * 60L * 1_000_000_000L + 7_090_000_000L, SelftestUi.parsePsCpu("224:07.09"));
        assertEquals(1_000_000_000L, SelftestUi.parsePsCpu("00:00:01"));
        assertEquals((86_400L + 3_600L + 120L + 3L) * 1_000_000_000L, SelftestUi.parsePsCpu("1-01:02:03"));
        assertEquals(-1L, SelftestUi.parsePsCpu("-"));
        assertEquals(-1L, SelftestUi.parsePsCpu(""));
        assertEquals(-1L, SelftestUi.parsePsCpu("not-a-time"));
    }
}
