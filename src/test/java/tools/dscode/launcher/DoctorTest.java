package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.control.protocol.LiveHostFile;
import tools.dscode.testsupport.PortableProcess;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 3: doctor reports chrome, the start host, and a dead host without a tautology. */
class DoctorTest {
    @TempDir
    Path temp;

    @Test
    void covers_command_doctor() {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                new String[]{"doctor", temp.toString()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertEquals(0, code, err);
        assertTrue(out.contains("chrome="), out);
        assertTrue(out.contains("chrome=present") || out.contains("chrome=absent"), out);
        assertTrue(out.contains("health=absent"), out);
        assertTrue(out.contains("session=absent"), out);
        assertTrue(out.contains("bridge=absent"), out);
        assertTrue(out.contains("worker=absent"), out);
        assertTrue(out.contains("page=absent"), out);
        assertTrue(out.contains("lease="), out);
        assertTrue(out.contains("leaseLeft="), out);
        assertTrue(out.contains("orphans="), out);
        assertTrue(out.contains("frame="), out);
        assertTrue(out.contains("placed="), out);
        assertTrue(out.contains("waits=0"), out);
    }

    @Test
    void deadHostPidReportsHealthDown() throws Exception {
        Process finished = PortableProcess.exited(0);
        LiveHostFile.write(temp, new LiveHostFile.Host(
                "http://127.0.0.1:9", "token", 9, finished.pid(), finished.pid(),
                "dead-host", false, "chrome", false, "", false, 0L, 0L
        ));
        Capture reported = run("doctor", temp.toString());
        assertEquals(0, reported.code, reported.err);
        assertTrue(reported.out.contains("health=down"), reported.out);
        assertTrue(reported.out.contains("pid=" + finished.pid()), reported.out);
        assertTrue(reported.out.contains("page=absent"), reported.out);
    }

    private Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
