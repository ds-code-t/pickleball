package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.snapshot.SnapshotWarnings;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopScreenshotTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @Test
    void commandsMatchThePlatform() {
        DesktopScreenshot.Rect rect = new DesktopScreenshot.Rect(1, 2, 3, 4);
        assertEquals(List.of("grim", "-g", "1,2 3x4", "-"), DesktopScreenshot.command("linux", "wayland", rect));
        assertEquals(
                List.of("import", "-window", "root", "-crop", "3x4+1+2", "png:-"),
                DesktopScreenshot.command("linux", "x11", rect)
        );
        assertTrue(DesktopScreenshot.command("windows", "x11", rect).get(4).contains("SetProcessDPIAware"));
        assertTrue(DesktopScreenshot.command("windows", "x11", rect).get(4).contains("CopyFromScreen"));
        List<String> mac = DesktopScreenshot.command("macos", "", rect);
        assertEquals("screencapture", mac.get(0));
        assertTrue(mac.contains("-R"));
        assertTrue(mac.contains("1,2,3,4"));
    }

    @Test
    void xwdBecomesPngAndScaleIsImageOverPoints() throws Exception {
        byte[] xwd = xwd(2, 1, new int[] {0xff0000, 0x00ff00});
        byte[] png = DesktopScreenshot.xwdToPng(xwd, new DesktopScreenshot.Rect(0, 0, 2, 1));
        assertEquals((byte) 0x89, png[0]);
        assertEquals(2.0, DesktopScreenshot.scale(100, 200), 0);
        BufferedImage wide = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(wide, "png", out);
        byte[] widePng = out.toByteArray();
        AtomicReference<Duration> timeout = new AtomicReference<>();
        DesktopScreenshot.Shot shot = DesktopScreenshot.capture(
                new DesktopScreenshot.Rect(0, 0, 100, 50),
                (command, limit) -> {
                    timeout.set(limit);
                    if ("import".equals(command.get(0))) {
                        return new DesktopScreenshot.Result(0, widePng, "", false);
                    }
                    return new DesktopScreenshot.Result(127, new byte[0], "missing", false);
                },
                Map.of(),
                "linux",
                "x11"
        );
        assertEquals(Duration.ofSeconds(10), timeout.get());
        assertEquals(2.0, shot.scale(), 0);
        assertEquals(200, shot.width());
    }

    @Test
    void robotFallbackWarnsOnce() {
        SnapshotWarnings.resetForTests();
        List<String> tools = new ArrayList<>();
        DesktopScreenshot.Runner runner = (command, timeout) -> {
            tools.add(command.get(0));
            if (command.contains("robot")) return new DesktopScreenshot.Result(0, PNG, "", false);
            return new DesktopScreenshot.Result(127, new byte[0], "missing", false);
        };
        DesktopScreenshot.Rect rect = new DesktopScreenshot.Rect(0, 0, 4, 4);
        DesktopScreenshot.Shot first = DesktopScreenshot.capture(rect, runner, Map.of("DISPLAY", ":99"), "linux", "x11");
        DesktopScreenshot.Shot second = DesktopScreenshot.capture(rect, runner, Map.of("DISPLAY", ":99"), "linux", "x11");
        assertEquals(1, first.width());
        assertEquals(1, second.width());
        assertTrue(tools.stream().anyMatch(tool -> tool.contains("java")));
        assertFalse(SnapshotWarnings.once("robot reason=native-tool-missing"));
    }

    @Test
    void blackMacCaptureIsPermissionDenied() throws Exception {
        BufferedImage black = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        black.setRGB(0, 0, Color.BLACK.getRGB());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(black, "png", out);
        byte[] png = out.toByteArray();
        CdpException failure = assertThrows(CdpException.class, () -> DesktopScreenshot.capture(
                new DesktopScreenshot.Rect(0, 0, 1, 1),
                (command, timeout) -> new DesktopScreenshot.Result(0, png, "could not create image", false),
                Map.of(),
                "macos",
                ""
        ));
        assertTrue(failure.getMessage().contains("screen recording permission denied"), failure.getMessage());
        assertTrue(failure.getMessage().contains("fix:"), failure.getMessage());
    }

    private static byte[] xwd(int width, int height, int[] pixels) {
        int header = 100;
        int bpl = width * 4;
        byte[] bytes = new byte[header + height * bpl];
        put(bytes, 0, header);
        put(bytes, 4, 7);
        put(bytes, 16, width);
        put(bytes, 20, height);
        put(bytes, 28, 1);
        put(bytes, 44, 32);
        put(bytes, 48, bpl);
        put(bytes, 56, 0x00ff0000);
        put(bytes, 60, 0x0000ff00);
        put(bytes, 64, 0x000000ff);
        put(bytes, 76, 0);
        for (int i = 0; i < pixels.length; i++) put(bytes, header + i * 4, pixels[i]);
        return bytes;
    }

    private static void put(byte[] bytes, int offset, int value) {
        ByteBuffer.wrap(bytes).putInt(offset, value);
    }
}
