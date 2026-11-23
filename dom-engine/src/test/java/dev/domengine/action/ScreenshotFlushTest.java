package dev.domengine.action;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ScreenshotFlushTest {
    @Test
    void pngIsClosedBeforeTheCallerReturns(@TempDir Path dir) throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        Path file = dir.resolve("shot.png");
        Observe.writePng(file, png);
        assertFalse(Files.exists(file.resolveSibling("shot.png.part")));
        byte[] opened = Files.readAllBytes(file);
        assertEquals(0x89, opened[0] & 0xff);
        assertEquals(0x50, opened[1] & 0xff);
        Path moved = dir.resolve("opened.png");
        Files.move(file, moved, StandardCopyOption.REPLACE_EXISTING);
        assertArrayEquals(png, Files.readAllBytes(moved));
    }
}
