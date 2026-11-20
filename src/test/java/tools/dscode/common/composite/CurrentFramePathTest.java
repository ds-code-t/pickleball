package tools.dscode.common.composite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CurrentFramePathTest {
    @Test
    void emptyStackReportsTop() {
        assertEquals("top", ElementOperations.currentFramePath());
    }
}
