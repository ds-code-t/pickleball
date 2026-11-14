package dev.domengine.premap;

import dev.domengine.tree.DomNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreMapLookupDefaultsTest {
    @Test
    void passThroughAnswersNothing() {
        assertTrue(PreMapLookup.PASS_THROUGH.current("session", "1").isEmpty());
        assertTrue(PreMapLookup.PASS_THROUGH.candidates("role", "button").isEmpty());
        assertNotSame(PreMapLookup.PASS_THROUGH, PreMapLookup.installed());
        assertTrue(PreMapLookup.installed().current("session", "v").isEmpty());
        assertTrue(PreMapLookup.installed().candidates("text", "Save").isEmpty());
    }

    @Test
    void forTreeCachesByIdentityAndStillPassesCandidatesThrough() {
        PreMapLookup.resetForTests();
        DomNode root = new DomNode();
        root.nodeType = 9;
        PreMapLookup first = PreMapLookup.forTree(root);
        PreMapLookup second = PreMapLookup.forTree(root);
        assertTrue(first.candidates("role", "button").isEmpty());
        assertTrue(second.current("offline", "fixture").isEmpty());
        PreMapLookup.store("s", PreMap.empty("s", "1"));
        assertTrue(PreMapLookup.installed().current("s", "1").isEmpty());
    }
}
