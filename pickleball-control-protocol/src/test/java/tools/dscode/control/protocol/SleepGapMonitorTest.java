package tools.dscode.control.protocol;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleepGapMonitorTest {
    @Test
    void normalBeatsProduceNoGapAndAStallIsReportedWithItsLength() {
        SleepGapMonitor monitor = new SleepGapMonitor(SleepGapMonitor.INTERVAL_MS, SleepGapMonitor.THRESHOLD_MS);
        monitor.beat(0);
        monitor.beat(5_000);
        monitor.beat(10_000);
        monitor.beat(15_000);
        assertTrue(monitor.gaps().isEmpty());

        monitor.beat(15_000 + SleepGapMonitor.THRESHOLD_MS);
        assertTrue(monitor.gaps().isEmpty(), "a delta equal to the threshold is not a stall");

        long detected = 45_000 + 45_000;
        monitor.beat(detected);
        assertEquals(1, monitor.gaps().size());
        SleepGapMonitor.Gap gap = monitor.gaps().getFirst();
        assertEquals(45_000, gap.gapMs());
        assertEquals(Instant.ofEpochMilli(detected), gap.detectedAt());
    }
}
