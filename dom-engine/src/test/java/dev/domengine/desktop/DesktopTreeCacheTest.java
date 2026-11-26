package dev.domengine.desktop;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One cached desktop tree per window while warm is on. A refuse reads through. */
class DesktopTreeCacheTest {
    @AfterEach
    void reset() {
        DesktopTree.resetForTests();
        System.clearProperty("pkb_warm");
        System.clearProperty("pkb_engine_log");
        System.clearProperty("pkb_heap_refuse_percent");
    }

    @Test
    void warmOnHitsOnceUntilStructureChanged() {
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_engine_log", "gate");
        DesktopTree.useHeapForTests(0, 100);
        AtomicInteger loads = new AtomicInteger();
        DomNode tree = new DomNode();
        DesktopTree.cachedTree("win-1", 10, () -> {
            loads.incrementAndGet();
            return tree;
        });
        DesktopTree.cachedTree("win-1", 10, () -> {
            loads.incrementAndGet();
            return tree;
        });
        assertEquals(1, loads.get());
        assertTrue(DesktopTree.cacheNotesForTests().contains("cache hit"));
        DesktopTree.structureChanged("win-1");
        DesktopTree.cachedTree("win-1", 10, () -> {
            loads.incrementAndGet();
            return tree;
        });
        assertEquals(2, loads.get());
        assertEquals("cache miss", DesktopTree.cacheNotesForTests().getLast());
    }

    @Test
    void warmOffMissesBoth() {
        System.setProperty("pkb_warm", "off");
        System.setProperty("pkb_engine_log", "gate");
        AtomicInteger loads = new AtomicInteger();
        DesktopTree.cachedTree("win-1", 10, () -> {
            loads.incrementAndGet();
            return new DomNode();
        });
        DesktopTree.cachedTree("win-1", 10, () -> {
            loads.incrementAndGet();
            return new DomNode();
        });
        assertEquals(2, loads.get());
        assertEquals(List.of("cache miss", "cache miss"), DesktopTree.cacheNotesForTests());
    }

    @Test
    void oversizedTreeIsNotStored() {
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_engine_log", "gate");
        System.setProperty("pkb_heap_refuse_percent", "50");
        DesktopTree.useHeapForTests(0, 100);
        AtomicInteger loads = new AtomicInteger();
        DesktopTree.cachedTree("win-1", 60, () -> {
            loads.incrementAndGet();
            return new DomNode();
        });
        DesktopTree.cachedTree("win-1", 60, () -> {
            loads.incrementAndGet();
            return new DomNode();
        });
        assertEquals(2, loads.get());
        assertEquals(List.of("cache refuse", "cache refuse"), DesktopTree.cacheNotesForTests());
    }
}
