package dev.domengine;

import dev.domengine.log.EngineLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineLogTest {
    @TempDir
    Path temp;

    @Test
    void staysOffUntilAnEntityIsNamed() throws Exception {
        EngineLog.configure(Map.of("COMPOSITE_LOG_DIR", temp.toString()));
        EngineLog.info("walk", "hidden");
        assertEquals(0, Files.list(temp).count());
    }

    @Test
    void dateStampEntityAndCap() throws Exception {
        EngineLog.configure(Map.of(
                "COMPOSITE_LOG", "walk",
                "COMPOSITE_LOG_DIR", temp.toString(),
                "COMPOSITE_LOG_CAP_BYTES", "80",
                "COMPOSITE_LOG_MAX_BYTES", "100000"
        ));
        EngineLog.info("query", "no");
        EngineLog.info("walk", "document nodes=3");
        Path log = Files.list(temp).filter(path -> path.getFileName().toString().startsWith("composite-")).findFirst().orElseThrow();
        String text = Files.readString(log);
        assertTrue(text.contains("walk id=walk/"), text);
        assertTrue(text.startsWith("20"), text);
        assertTrue(!text.contains(" query "), text);
        EngineLog.info("walk", "x".repeat(200));
        long dated = Files.list(temp).filter(path -> path.getFileName().toString().contains("composite-")).count();
        assertTrue(dated >= 2, Files.list(temp).toList().toString());
    }
}
