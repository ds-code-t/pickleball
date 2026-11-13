package tools.dscode.common.agentsurface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PopupLedgerTest {
    @TempDir
    Path project;

    @Test
    void twoProcessesKeepAMonotonicSequenceAndTheCapRolls() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<Process> processes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            processes.add(new ProcessBuilder(
                    java, "-cp", System.getProperty("java.class.path"),
                    "tools.dscode.common.agentsurface.PopupLedger",
                    "--append", project.toString(), "r1", "append"
            ).start());
        }
        for (Process process : processes) {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        }
        List<PopupLedger.Row> rows = PopupLedger.read(project, "r1");
        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).seq());
        assertEquals(2, rows.get(1).seq());
        String big = "x".repeat(900_000);
        PopupLedger.append(project, "r1", "append", "instructions", "", "", big, "", "");
        PopupLedger.append(project, "r1", "append", "instructions", "", "", "tail", "", "");
        List<PopupLedger.Row> rolled = PopupLedger.read(project, "r1");
        assertTrue(rolled.size() >= 3, rolled.toString());
        assertEquals("tail", rolled.get(rolled.size() - 1).html());
    }
}
