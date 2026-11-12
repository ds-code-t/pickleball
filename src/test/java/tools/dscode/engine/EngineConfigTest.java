package tools.dscode.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineConfigTest {
    @TempDir
    Path project;

    @Test
    void defaultIsNativeAndPropertyBeatsTheFile() throws Exception {
        String previous = System.getProperty("pkb_engine");
        String previousProject = System.getProperty("pkb_engine_project");
        try {
            System.clearProperty("pkb_engine");
            System.setProperty("pkb_engine_project", project.toString());
            assertEquals(EngineConfig.NATIVE, EngineConfig.mode());
            assertTrue(EngineConfig.nativeSelected());
            Files.writeString(project.resolve("engine.properties"), "mode=cucumber\n");
            assertEquals(EngineConfig.CUCUMBER, EngineConfig.mode());
            assertFalse(EngineConfig.nativeSelected());
            System.setProperty("pkb_engine", "native");
            assertEquals(EngineConfig.NATIVE, EngineConfig.mode());
            System.setProperty("pkb_engine", "cucumber");
            assertEquals(EngineConfig.CUCUMBER, EngineConfig.mode());
            assertFalse(EngineConfig.nativeSelected());
        } finally {
            restore("pkb_engine", previous);
            restore("pkb_engine_project", previousProject);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
