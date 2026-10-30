package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Default guidance is the short first-read. The long notes are opt-in. */
class DiagnosticCliGuidanceTest {
    @Test
    void guidancePrintsTheShortGuideOnly() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = DiagnosticCli.run(
                new String[]{"guidance"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)
        );
        assertEquals(0, status);
        assertEquals("", err.toString(StandardCharsets.UTF_8));
        String guide = out.toString(StandardCharsets.UTF_8);
        assertTrue(guide.contains("# Pickleball Consumer Agent Guide"));
        assertTrue(guide.contains("-Dexec.args=execute-step --text-file=step.txt"));
        assertTrue(guide.contains("The fingerprint comes from `workspace-show`"));
        assertFalse(guide.contains("# Workbench agent detail"));
        assertFalse(guide.contains("Moved out of the first-read guide"));
        assertFalse(guide.contains("Diagnostic investigation protocol"));
        assertFalse(guide.contains("Maintainer-only: pointer-eval harness"));
    }

    @Test
    void guidanceDetailAppendsTheLongNotes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = DiagnosticCli.run(
                new String[]{"guidance", "--detail"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, status);
        String guide = out.toString(StandardCharsets.UTF_8);
        assertTrue(guide.contains("# Pickleball Consumer Agent Guide"));
        assertTrue(guide.contains("# Workbench agent detail"));
        assertTrue(guide.contains("Moved out of the first-read guide"));
        assertTrue(guide.contains("Diagnostic investigation protocol"));
        assertTrue(guide.contains("Maintainer-only: pointer-eval harness"));
        assertTrue(guide.indexOf("# Pickleball Consumer Agent Guide") < guide.indexOf("# Workbench agent detail"));
    }
}
