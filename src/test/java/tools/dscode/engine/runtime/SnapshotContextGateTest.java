package tools.dscode.engine.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.dscode.engine.context.ExecutionContext;
import tools.dscode.engine.match.JavaStepHost;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Heap gate through {@link ExecutionContext#open}. Readings are injected.
 * No browser is started.
 */
class SnapshotContextGateTest {
    @AfterEach
    void reset() {
        while (ExecutionContext.current() != null) ExecutionContext.current().close();
        ResourceGate.clearInjected();
        WarmSpine.shared().stopChildren();
        System.clearProperty("pkb_warm");
        System.clearProperty("pkb_store_run_classpath");
        System.clearProperty("pkb_engine_log");
    }

    @Test
    void warmOffIgnoresTheGate() {
        System.setProperty("pkb_warm", "off");
        ResourceGate.inject(() -> new ResourceGate.Reading(90, 100, 1, 1, 1));
        try (ExecutionContext context = ExecutionContext.open(null)) {
            assertNull(context.warmLoader());
            assertEquals(0, WarmSpine.shared().liveChildren());
        }
    }

    @Test
    void belowTheLineStaysInProcess() {
        System.setProperty("pkb_warm", "on");
        ResourceGate.inject(() -> new ResourceGate.Reading(50, 100, 1, 1, 1));
        try (ExecutionContext context = ExecutionContext.open(null)) {
            assertNotNull(context.warmLoader());
            assertEquals(0, WarmSpine.shared().liveChildren());
        }
    }

    @Test
    void betweenTheLinesStartsAColdChildAndRefusesWhenThePoolIsFull() throws Exception {
        Path classes = Path.of("build", "classes", "java", "main");
        assertTrue(Files.isDirectory(classes), classes.toString());
        System.setProperty("pkb_store_run_classpath", classes.toAbsolutePath().normalize().toString());
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_engine_log", "gate");
        ResourceGate.inject(() -> new ResourceGate.Reading(75, 100, 1, 1, 1));
        ExecutionContext child = ExecutionContext.open(null);
        try {
            assertNull(child.warmLoader());
            assertEquals(1, WarmSpine.shared().liveChildren());
            ClassLoader selected = JavaStepHost.selectLoader("example.Glue", Path.of("").toAbsolutePath());
            ClassLoader application = JavaStepHost.class.getClassLoader();
            if (application == null) application = ClassLoader.getSystemClassLoader();
            assertSame(application, selected);

            ResourceGate.inject(() -> new ResourceGate.Reading(90, 100, 1, 1, 1));
            IllegalStateException refused = assertThrows(IllegalStateException.class, () -> ExecutionContext.open(null));
            assertEquals(ResourceGate.REFUSED, refused.getMessage());
            assertTrue(refused.getMessage().contains("error:"));
            assertTrue(refused.getMessage().contains("fix:"));
            assertEquals(1, WarmSpine.shared().liveChildren());
        } finally {
            child.close();
        }
        assertEquals(0, WarmSpine.shared().liveChildren());
    }
}
