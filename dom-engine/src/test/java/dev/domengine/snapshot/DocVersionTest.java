package dev.domengine.snapshot;

import dev.domengine.FakeCdp;
import dev.domengine.cdp.CdpConnection;
import dev.domengine.json.Jsons;
import dev.domengine.premap.DocVersion;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocVersionTest {
    @Test
    void versionIsLoaderAndMutationCount() {
        assertEquals("L1:0", DocVersion.of("L1", 0));
        assertEquals(":3", DocVersion.of(null, 3));
        assertEquals("L1:0", DocVersion.of("L1", -1));
        assertTrue(DocVersion.SCRIPT.contains("MutationObserver"));
        assertTrue(DocVersion.SCRIPT.contains("__pkbPremapCount"));
        assertEquals("pkb-premap", DocVersion.WORLD);
    }

    @Test
    void isolatedWorldReadReturnsTheCounter() throws Exception {
        try (FakeCdp cdp = FakeCdp.start()) {
            CdpConnection connection = CdpConnection.connect(
                    URI.create("ws://127.0.0.1:" + cdp.port + "/devtools/page/PAGE1"),
                    java.time.Duration.ofSeconds(5));
            try {
                assertEquals("L1:4", DocVersion.read(connection, "main", "L1"));
                var value = Jsons.object();
                value.putObject("result").put("value", 9);
                assertEquals(9, DocVersion.count(value));
            } finally {
                connection.close();
            }
        }
    }
}
