package dev.domengine.desktop;

import dev.domengine.cdp.CdpException;
import dev.domengine.log.EngineLog;
import dev.domengine.snapshot.SnapshotWarnings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** One desktop screenshot. Native tools first, then a Robot child JVM. */
public final class DesktopScreenshot {
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    public record Rect(int x, int y, int width, int height) {
    }

    public record Shot(byte[] png, int width, int height, double scale, String tool) {
    }

    public record Result(int exit, byte[] stdout, String text, boolean timedOut) {
    }

    @FunctionalInterface
    public interface Runner {
        Result run(List<String> command, Duration timeout);
    }

    private DesktopScreenshot() {
    }

    public static Shot capture(Rect rect) {
        return capture(rect, systemRunner(), System.getenv(), osFamily(), sessionType());
    }

    static Shot capture(Rect rect, Runner runner, Map<String, String> env, String os, String session) {
        if (rect == null || rect.width <= 0 || rect.height <= 0) {
            throw new CdpException("error: screenshot rect is empty\nfix: Pass a window or screen rectangle with a positive size.");
        }
        Runner used = runner == null ? systemRunner() : runner;
        Map<String, String> environment = env == null ? Map.of() : env;
        String family = os == null ? osFamily() : os;
        String kind = session == null ? "" : session;
        if ("linux".equals(family) && kind.isBlank()) kind = sessionType(environment);
        Attempt taken = attemptNative(family, kind, rect, used);
        if (taken.shot != null && !denied(family, taken.shot.png, taken.detail)) return taken.shot;
        if ("macos".equals(family) && taken.shot != null && denied(family, taken.shot.png, taken.detail)) {
            throw new CdpException(
                    "error: screen recording permission denied\n"
                            + "fix: Allow Screen Recording for this terminal in System Settings, then retry screencapture.");
        }
        if (displayAvailable(family, environment)) {
            if (SnapshotWarnings.once("robot reason=native-tool-missing")) {
                EngineLog.warn("desktop-shot", "desktop-shot fallback robot reason=native-tool-missing");
            }
            Attempt robot = runOne(robotCommand(rect), used);
            if (robot.shot != null) return robot.shot;
        }
        throw new CdpException(
                "error: desktop screenshot failed\n"
                        + "fix: Install grim on Wayland, ImageMagick import or xwd on X11, or capture on a display.");
    }

    static List<String> command(String os, String session, Rect rect) {
        String family = os == null ? "" : os;
        if ("windows".equals(family)) return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", windowsScript(rect));
        if ("macos".equals(family)) {
            return List.of("screencapture", "-x", "-R", rect.x + "," + rect.y + "," + rect.width + "," + rect.height, "-t", "png", "-");
        }
        if ("wayland".equals(session)) {
            return List.of("grim", "-g", rect.x + "," + rect.y + " " + rect.width + "x" + rect.height, "-");
        }
        return List.of(
                "import", "-window", "root", "-crop",
                rect.width + "x" + rect.height + "+" + rect.x + "+" + rect.y, "png:-"
        );
    }

    static byte[] xwdToPng(byte[] xwd, Rect crop) {
        if (xwd == null || xwd.length < 100) throw new CdpException("error: xwd is too small\nfix: Capture with xwd -root again.");
        boolean msb = read32(xwd, 28, true) == 1 || read32(xwd, 0, true) > 0 && read32(xwd, 4, true) == 7;
        int header = read32(xwd, 0, msb);
        int width = read32(xwd, 16, msb);
        int height = read32(xwd, 20, msb);
        int bpp = read32(xwd, 44, msb);
        int bpl = read32(xwd, 48, msb);
        int red = read32(xwd, 56, msb);
        int green = read32(xwd, 60, msb);
        int blue = read32(xwd, 64, msb);
        int ncolors = read32(xwd, 76, msb);
        if (header < 100 || width <= 0 || height <= 0 || bpp < 24) {
            throw new CdpException("error: xwd header is not a direct-color image\nfix: Capture a 24-bit or 32-bit root window.");
        }
        int start = header + ncolors * 12;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int bytes = Math.max(1, bpp / 8);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = start + y * bpl + x * bytes;
                if (at + bytes > xwd.length) break;
                int pixel = 0;
                for (int i = 0; i < bytes; i++) {
                    pixel = msb ? (pixel << 8) | (xwd[at + i] & 0xff) : pixel | ((xwd[at + i] & 0xff) << (8 * i));
                }
                int r = shift(pixel, red);
                int g = shift(pixel, green);
                int b = shift(pixel, blue);
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        if (crop != null && crop.width > 0 && crop.height > 0) {
            int cw = Math.min(crop.width, width - Math.max(0, crop.x));
            int ch = Math.min(crop.height, height - Math.max(0, crop.y));
            if (cw > 0 && ch > 0) image = image.getSubimage(Math.max(0, crop.x), Math.max(0, crop.y), cw, ch);
        }
        return writePng(image);
    }

    static boolean denied(String os, byte[] png, String detail) {
        if (!"macos".equals(os)) return false;
        String text = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (text.contains("could not") || text.contains("permission") || png == null || png.length == 0) return true;
        return black(png);
    }

    static double scale(int requested, int actual) {
        if (requested <= 0 || actual <= 0) return 1;
        return actual / (double) requested;
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 5 && "robot".equals(args[0])) {
            Rect rect = new Rect(
                    Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                    Integer.parseInt(args[3]), Integer.parseInt(args[4])
            );
            System.out.write(robotPng(rect));
            return;
        }
        System.err.println("usage: DesktopScreenshot robot x y width height");
        System.exit(2);
    }

    private static Attempt attemptNative(String os, String session, Rect rect, Runner runner) {
        Attempt first = runOne(command(os, session, rect), runner);
        if (first.shot != null) return first;
        if ("linux".equals(os) && !"wayland".equals(session)) {
            Attempt xwd = runOne(List.of("xwd", "-root", "-silent"), runner);
            if (xwd.stdout != null && xwd.stdout.length > 24) {
                try {
                    byte[] png = xwdToPng(xwd.stdout, rect);
                    int[] size = pngSize(png);
                    return new Attempt(new Shot(png, size[0], size[1], scale(rect.width, size[0]), "xwd"), xwd.detail, xwd.stdout);
                } catch (CdpException failure) {
                    return new Attempt(null, failure.getMessage(), xwd.stdout);
                }
            }
        }
        return first;
    }

    private static Attempt runOne(List<String> command, Runner runner) {
        Result result = runner.run(command, TIMEOUT);
        if (result == null || result.timedOut || result.exit != 0 || result.stdout == null || result.stdout.length < 8) {
            return new Attempt(null, result == null ? "" : result.text, result == null ? null : result.stdout);
        }
        if (result.stdout[0] != (byte) 0x89) return new Attempt(null, result.text, result.stdout);
        int[] size = pngSize(result.stdout);
        int requested = 0;
        for (String token : command) {
            if (token.chars().filter(ch -> ch == 'x' || ch == '+').count() >= 1 && token.contains("x")) {
                int x = token.indexOf('x');
                try {
                    requested = Integer.parseInt(token.substring(0, x));
                } catch (NumberFormatException ignored) {
                    requested = 0;
                }
            }
        }
        double scale = requested > 0 ? scale(requested, size[0]) : 1;
        String tool = command.isEmpty() ? "" : command.get(0);
        return new Attempt(new Shot(result.stdout, size[0], size[1], scale, tool), result.text, result.stdout);
    }

    private static List<String> robotCommand(Rect rect) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return List.of(
                java, "-cp", System.getProperty("java.class.path"),
                "dev.domengine.desktop.DesktopScreenshot", "robot",
                Integer.toString(rect.x), Integer.toString(rect.y),
                Integer.toString(rect.width), Integer.toString(rect.height)
        );
    }

    private static byte[] robotPng(Rect rect) throws Exception {
        Robot robot = new Robot();
        BufferedImage image = robot.createScreenCapture(new Rectangle(rect.x, rect.y, rect.width, rect.height));
        return writePng(image);
    }

    private static String windowsScript(Rect rect) {
        return """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Drawing
                Add-Type @"
                using System;
                using System.Runtime.InteropServices;
                public static class Dpi {
                  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
                }
                "@
                [Dpi]::SetProcessDPIAware() | Out-Null
                $bmp = New-Object System.Drawing.Bitmap %d, %d
                $g = [System.Drawing.Graphics]::FromImage($bmp)
                $g.CopyFromScreen(%d, %d, 0, 0, $bmp.Size)
                $ms = New-Object System.IO.MemoryStream
                $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
                [Console]::OpenStandardOutput().Write($ms.ToArray(), 0, $ms.Length)
                """.formatted(rect.width, rect.height, rect.x, rect.y);
    }

    static Runner systemRunner() {
        return (command, timeout) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(false);
            try {
                Process process = builder.start();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ByteArrayOutputStream err = new ByteArrayOutputStream();
                Thread stdout = pump(process.getInputStream(), out);
                Thread stderr = pump(process.getErrorStream(), err);
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    killTree(process);
                    stdout.join(500);
                    stderr.join(500);
                    return new Result(124, out.toByteArray(), err.toString(StandardCharsets.UTF_8), true);
                }
                stdout.join(500);
                stderr.join(500);
                return new Result(process.exitValue(), out.toByteArray(), err.toString(StandardCharsets.UTF_8), false);
            } catch (Exception failure) {
                return new Result(127, new byte[0], failure.getMessage(), false);
            }
        };
    }

    private static void killTree(Process process) {
        process.descendants().forEach(handle -> handle.destroy());
        process.destroy();
        process.descendants().forEach(handle -> handle.destroyForcibly());
        process.destroyForcibly();
    }

    private static Thread pump(java.io.InputStream in, ByteArrayOutputStream out) {
        Thread thread = new Thread(() -> {
            try {
                in.transferTo(out);
            } catch (Exception ignored) {
                // Timeout still reports the failure.
            }
        }, "desktop-shot");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static boolean displayAvailable(String os, Map<String, String> env) {
        if ("windows".equals(os) || "macos".equals(os)) return true;
        String display = env.getOrDefault("DISPLAY", "");
        String wayland = env.getOrDefault("WAYLAND_DISPLAY", "");
        return !display.isBlank() || !wayland.isBlank();
    }

    private static String osFamily() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) return "windows";
        if (name.contains("mac")) return "macos";
        return "linux";
    }

    private static String sessionType() {
        return sessionType(System.getenv());
    }

    private static String sessionType(Map<String, String> env) {
        String type = env.getOrDefault("XDG_SESSION_TYPE", "");
        if ("wayland".equalsIgnoreCase(type) || !env.getOrDefault("WAYLAND_DISPLAY", "").isBlank()) return "wayland";
        return "x11";
    }

    private static boolean black(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getWidth() <= 0) return true;
            long sum = 0;
            int count = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    Color color = new Color(image.getRGB(x, y), true);
                    sum += color.getRed() + color.getGreen() + color.getBlue();
                    count++;
                }
            }
            return count == 0 || sum / (double) count < 8;
        } catch (Exception failure) {
            return false;
        }
    }

    private static int[] pngSize(byte[] png) {
        if (png.length < 24 || png[0] != (byte) 0x89) return new int[] {0, 0};
        int width = ((png[16] & 0xff) << 24) | ((png[17] & 0xff) << 16) | ((png[18] & 0xff) << 8) | (png[19] & 0xff);
        int height = ((png[20] & 0xff) << 24) | ((png[21] & 0xff) << 16) | ((png[22] & 0xff) << 8) | (png[23] & 0xff);
        return new int[] {width, height};
    }

    private static byte[] writePng(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception failure) {
            throw new CdpException("error: png encode failed\nfix: " + failure.getMessage());
        }
    }

    private static int read32(byte[] bytes, int offset, boolean msb) {
        if (offset + 4 > bytes.length) return 0;
        if (msb) {
            return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                    | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
        }
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static int shift(int pixel, int mask) {
        if (mask == 0) return 0;
        int shift = Integer.numberOfTrailingZeros(mask);
        int value = (pixel & mask) >>> shift;
        int bits = Integer.bitCount(mask);
        if (bits >= 8) return value >> (bits - 8);
        return value << (8 - bits);
    }

    private record Attempt(Shot shot, String detail, byte[] stdout) {
    }
}
