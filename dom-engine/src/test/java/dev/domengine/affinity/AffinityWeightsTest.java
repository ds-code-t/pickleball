package dev.domengine.affinity;

import dev.domengine.cdp.CdpException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AffinityWeightsTest {
    @Test
    void defaultsRenormaliseToOne() {
        AffinityWeights weights = AffinityWeights.defaults();
        assertEquals(0.30, weights.proximity(), 1e-9);
        assertEquals(0.20, weights.alignment(), 1e-9);
        assertEquals(0.15, weights.role(), 1e-9);
        assertEquals(0.10, weights.text(), 1e-9);
        assertEquals(0.15, weights.section(), 1e-9);
        AffinityWeights normalized = weights.normalized();
        double sum = normalized.proximity() + normalized.alignment() + normalized.role()
                + normalized.text() + normalized.section();
        assertEquals(1.0, sum, 1e-9);
    }

    @Test
    void unknownKeyIsAUsageError() {
        CdpException failure = assertThrows(CdpException.class, () -> AffinityWeights.parse("proximity=0.2,nope=1"));
        assertTrue(failure.getMessage().startsWith("usage:"), failure.getMessage());
    }

    @Test
    void envOverlayReplacesNamedKeys() {
        AffinityWeights weights = AffinityWeights.fromEnv(Map.of(
                AffinityWeights.ENV, "proximity=1,alignment=1,role=1,text=1,section=1"
        ));
        assertEquals(0.2, weights.normalized().proximity(), 1e-9);
        assertEquals(0.2, weights.normalized().section(), 1e-9);
    }
}
