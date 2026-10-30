package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLogTest {
    @TempDir
    Path project;

    @Test
    void appendsOneTabSeparatedQueryLine() throws Exception {
        Instant when = Instant.parse("2026-10-29T00:00:00Z");
        AgentLog.append(project, when, "ada", "run-1", "query", "runs", null);
        AgentLog.append(project, when, null, null, "query", "x\ty\n", "rec");
        String log = Files.readString(AgentLog.file(project));
        assertTrue(log.startsWith("2026-10-29T00:00:00Z\tada\trun-1\tquery\truns\t-\n"), log);
        String second = log.lines().skip(1).findFirst().orElseThrow();
        assertEquals(6, second.split("\t", -1).length, second);
        assertTrue(second.endsWith("\t-\t-\tquery\tx y\trec"), second);
        assertEquals(AgentLog.PURPOSE_LIMIT, AgentLog.oneLine("p".repeat(300)).length());
    }
}
