package tools.dscode.control.protocol.surface;

import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeHighlightBoxTest {
    @Test
    void stripsFollowBoundsAndScale() {
        Rectangle[] strips = NativeHighlightBox.strips(100, 80, 200, 40, 2.0);
        assertEquals(new Rectangle(100, 80, 200, 6), strips[0]);
        assertEquals(new Rectangle(100, 114, 200, 6), strips[1]);
        assertEquals(new Rectangle(100, 80, 6, 40), strips[2]);
        assertEquals(new Rectangle(294, 80, 6, 40), strips[3]);
        assertEquals(0xff2d55, NativeHighlightBox.COLOR.getRGB() & 0xffffff);
        NativeHighlightBox.Drawn drawn = NativeHighlightBox.show("box", 100, 100, 200, 40);
        if (GraphicsEnvironment.isHeadless()) {
            assertFalse(drawn.drawn());
            assertEquals("headless", drawn.reason());
        } else {
            assertTrue(drawn.drawn());
        }
        NativeHighlightBox.hide("box");
    }

    @Test
    void hideAllClearsEveryDrawnBox() {
        NativeHighlightBox.show("all-a", 10, 10, 30, 20);
        NativeHighlightBox.show("all-b", 60, 10, 30, 20);
        if (GraphicsEnvironment.isHeadless()) {
            assertTrue(NativeHighlightBox.shown().isEmpty());
            assertTrue(NativeHighlightBox.hideAll().isEmpty());
        } else {
            assertTrue(NativeHighlightBox.shown().containsAll(java.util.List.of("all-a", "all-b")));
            assertTrue(NativeHighlightBox.hideAll().containsAll(java.util.List.of("all-a", "all-b")));
            assertEquals("absent", NativeHighlightBox.hide("all-a").reason());
        }
        assertTrue(NativeHighlightBox.shown().isEmpty());
    }
}
