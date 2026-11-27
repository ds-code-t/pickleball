package dev.domengine.desktop;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The handler the Windows structure-changed callback invokes.
 * No UI Automation event is required for the cache drop.
 */
class WindowsStructureChangedTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
        System.clearProperty("pkb_warm");
    }

    @Test
    void handlerDropsTheCachedWindow() {
        System.setProperty("pkb_warm", "on");
        DesktopTree.useHeapForTests(0, 100);
        AtomicInteger loads = new AtomicInteger();
        DesktopTree.cachedTree("win-1", 10, () -> {
            loads.incrementAndGet();
            return new DomNode();
        });
        WindowsStructureEvents.onStructureChanged("win-1");
        DesktopTree.cachedTree("win-1", 10, () -> {
            loads.incrementAndGet();
            return new DomNode();
        });
        assertEquals(2, loads.get());
    }

    @Test
    void subscribeAndCloseDoNotFailOffTheEvent() {
        WindowsStructureEvents.start("win-1");
        assertTrue(WindowsStructureEvents.listening("win-1"));
        WindowsStructureEvents.stop("win-1");
        assertFalse(WindowsStructureEvents.listening("win-1"));
    }
}
