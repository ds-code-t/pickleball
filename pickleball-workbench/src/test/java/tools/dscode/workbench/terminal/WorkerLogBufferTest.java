package tools.dscode.workbench.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerLogBufferTest {
    @Test
    void filtersParsedLevelsAndTreatsUnmarkedScenarioOutputAsInfo() {
        WorkerLogBuffer buffer = new WorkerLogBuffer();
        buffer.appendRaw("""
                [TRACE] hidden
                [DEBUG] detail
                [INFO] Given navigate to: URL.home
                [WARNING] retry
                [ERROR] failed
                Status: SUCCESS
                """);

        buffer.setMinimum(WorkerLogBuffer.Level.WARNING);
        assertEquals(2, buffer.visible().size());
        assertEquals(WorkerLogBuffer.Level.WARNING, buffer.visible().getFirst().level());
        assertEquals(WorkerLogBuffer.Level.ERROR, buffer.visible().get(1).level());

        buffer.setMinimum(WorkerLogBuffer.Level.INFO);
        assertEquals(4, buffer.visible().size());
        assertEquals(WorkerLogBuffer.Level.INFO, buffer.visible().getFirst().level());
        assertEquals("Status: SUCCESS", buffer.visible().get(3).raw());
    }
}
