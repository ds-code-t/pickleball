package tools.dscode.common.agentfast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.agentcontrol.AgentControl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingFieldsTest {
    @TempDir
    Path temp;

    @Test
    void everyReplyCarriesElapsedMsAndAWarmVerbFitsInOneSecond() throws Exception {
        AgentControl.execute(temp, "t", "ui-catalog", Map.of(), List.of(), "a");
        long started = System.nanoTime();
        AgentControl.Result warm = AgentControl.execute(temp, "t", "ui-catalog", Map.of(), List.of(), "a");
        long warmMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(warm.exit() == 0, String.valueOf(warm.body()));
        assertInstanceOf(Number.class, warm.body().get("elapsedMs"));
        assertTrue(((Number) warm.body().get("elapsedMs")).longValue() >= 0);
        assertInstanceOf(Map.class, warm.body().get("timing"));
        System.out.println("MEASURE warmMs=" + warmMs + " elapsedMs=" + warm.body().get("elapsedMs"));
        assertTrue(warmMs <= 1000, "warmMs=" + warmMs);
        StepTiming.note(temp, "step-1", 12);
        String log = Files.readString(temp.resolve(".pickleball/logs/step-timing.jsonl"));
        assertTrue(log.contains("\"id\":\"step-1\""));
        assertTrue(log.contains("\"elapsedMs\":12"));
    }
}
