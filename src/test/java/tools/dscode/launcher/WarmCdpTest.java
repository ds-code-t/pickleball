package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.runtime.ResourceGate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Warm CDP opens only for an allowed warm hold, and spine stop closes it. */
class WarmCdpTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        System.clearProperty("pkb_warm");
        ResourceGate.clearInjected();
        WarmCdp.resetForTests();
        SpineCli.resetForTests();
        SpineCli.execute(new String[] {"spine", "stop", "--project=" + dir}, sink(), sink());
    }

    @Test
    void warmOffDoesNotConnect() {
        System.setProperty("pkb_warm", "off");
        SpineCli.inlineHoldForTests = true;
        AtomicInteger connects = new AtomicInteger();
        WarmCdp.useConnectorForTests(counter(connects, new AtomicInteger()));
        assertEquals(0, start());
        assertEquals(0, connects.get());
    }

    @Test
    void allowConnectsOnceAndStopCloses() {
        System.setProperty("pkb_warm", "on");
        SpineCli.inlineHoldForTests = true;
        ResourceGate.injectDecision(ResourceGate.Decision.ALLOW);
        AtomicInteger connects = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        WarmCdp.useConnectorForTests(counter(connects, closes));
        assertEquals(0, start(), "first start");
        assertEquals(1, connects.get());
        assertEquals(0, start(), "second start");
        assertEquals(1, connects.get());
        assertEquals(0, stop());
        assertEquals(1, closes.get());
    }

    @Test
    void refuseDoesNotConnect() {
        System.setProperty("pkb_warm", "on");
        SpineCli.inlineHoldForTests = true;
        ResourceGate.injectDecision(ResourceGate.Decision.REFUSE);
        AtomicInteger connects = new AtomicInteger();
        WarmCdp.useConnectorForTests(counter(connects, new AtomicInteger()));
        assertEquals(0, start());
        assertEquals(0, connects.get());
    }

    private int start() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SpineCli.execute(
                new String[] {"spine", "start", "--project=" + dir},
                sink(),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        if (code != 0) throw new AssertionError(err.toString(StandardCharsets.UTF_8));
        return code;
    }

    private int stop() {
        return SpineCli.execute(new String[] {"spine", "stop", "--project=" + dir}, sink(), sink());
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static WarmCdp.Connector counter(AtomicInteger connects, AtomicInteger closes) {
        return new WarmCdp.Connector() {
            @Override
            public void connect(String endpoint) {
                connects.incrementAndGet();
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }
}
