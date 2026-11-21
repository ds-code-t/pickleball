package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Live frame observation must not pretend a raw switch is still the top document. */
class FrameCombineTest {
    @Test
    void liveFrameWinsWhenTheStackSaysTop() {
        assertEquals("Box", ElementOperations.combineFrame("Box", "top"));
        assertEquals("Box", ElementOperations.combineFrame("Box", ""));
        assertEquals("Box", ElementOperations.combineFrame("Box", null));
    }

    @Test
    void trackedPathWinsWhenBothAreInsideAFrame() {
        assertEquals("Box/Inner", ElementOperations.combineFrame("Inner", "Box/Inner"));
    }

    @Test
    void liveTopKeepsATrackedFrameAndABlankLiveKeepsTheStack() {
        assertEquals("Box", ElementOperations.combineFrame("top", "Box"));
        assertEquals("top", ElementOperations.combineFrame("top", "top"));
        assertEquals("top", ElementOperations.combineFrame("top", ""));
        assertEquals("Box", ElementOperations.combineFrame("", "Box"));
        assertEquals("top", ElementOperations.combineFrame(null, null));
    }
}
