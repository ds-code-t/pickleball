package tools.dscode.control.protocol.speed;

import org.junit.jupiter.api.Test;
import tools.dscode.testsupport.PortableProcess;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A recorded start instant stops a reused pid from being killed. */
class ProcessStartGuardTest {
    @Test
    void wrongStartInstantDoesNotKillThisJvm() {
        ProcessHandle self = ProcessHandle.current();
        assertTrue(self.info().startInstant().isPresent(), "this JVM publishes no start instant");
        assertFalse(ProcessTree.killIfStarted(self.pid(), 1L));
        assertTrue(self.isAlive());
    }

    @Test
    void matchingStartInstantKillsSleep() throws Exception {
        Process sleep = PortableProcess.sleepBuilder(30).redirectErrorStream(true).start();
        try {
            ProcessHandle handle = ProcessHandle.of(sleep.pid()).orElseThrow();
            long started = handle.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            assertTrue(started > 0, "sleep published no start instant");
            assertTrue(ProcessTree.killIfStarted(sleep.pid(), started));
            assertTrue(sleep.waitFor(5, TimeUnit.SECONDS), "sleep was still alive");
        } finally {
            if (sleep.isAlive()) sleep.destroyForcibly();
        }
    }
}
