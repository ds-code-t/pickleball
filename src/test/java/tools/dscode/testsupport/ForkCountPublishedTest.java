package tools.dscode.testsupport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The root worker publishes {@code -Ppkb.test.forks} and keeps the 1536m ceiling. */
class ForkCountPublishedTest {
    @Test
    void rootWorkerPublishesForksAndHeapCeiling() {
        String forks = System.getProperty("pkb.test.forks");
        assertNotNull(forks, "pkb.test.forks");
        int count = Integer.parseInt(forks.trim());
        assertTrue(count >= 1, forks);
        assertEquals("1536m", System.getProperty("pkb.test.maxHeap"));
        long max = Runtime.getRuntime().maxMemory();
        long floor = 1024L * 1024L * 1024L;
        long ceiling = 1700L * 1024L * 1024L;
        assertTrue(max > floor && max < ceiling, "maxMemory=" + max);
    }
}
