package tools.dscode.workbench.mcp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchAttachServerProgressTest {
    @Test
    void firstCliHealthMarksWorkerReadyOnce() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            AtomicBoolean marked = new AtomicBoolean();
            WorkbenchAttachServer.markWorkerReady(WorkbenchAttachServer.CLI_SESSION_MODE, marked);
            WorkbenchAttachServer.markWorkerReady(WorkbenchAttachServer.CLI_SESSION_MODE, marked);
            WorkbenchAttachServer.markWorkerReady(WorkbenchAttachServer.UI_ATTACH_MODE, new AtomicBoolean());
        } finally {
            System.setErr(previous);
        }
        String text = err.toString(StandardCharsets.UTF_8).strip();
        assertEquals("progress stage=worker-ready", text);
        assertFalse(text.contains("worker-ready\nprogress"));
        assertTrue(text.startsWith("progress stage=worker-ready"));
    }
}
