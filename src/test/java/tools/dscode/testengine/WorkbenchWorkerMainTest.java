package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchWorkerMainTest {
    @Test
    void mainMarksJvmAndClasspathScanBeforeGlueInitialization() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            WorkbenchWorkerMain.reportStartupStages();
        } finally {
            System.setErr(previous);
        }
        String text = err.toString(StandardCharsets.UTF_8);
        int jvm = text.indexOf("progress stage=jvm-main");
        int scan = text.indexOf("progress stage=classpath-scan");
        assertTrue(jvm >= 0 && scan > jvm, text);
        System.out.println("windows-shaped-worker " + text.replace('\n', ' ').trim());
    }
}
