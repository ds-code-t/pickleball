package dev.domengine.image;

import dev.domengine.cdp.CdpException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure Java template match. PGM (P2) fixtures. Normalized cross-correlation, 0..1. */
public final class TemplateMatch {
    private TemplateMatch() {
    }

    public record Gray(int width, int height, int[] pixels) {
    }

    public static Gray read(Path path) {
        try {
            return parse(Files.readString(path));
        } catch (CdpException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CdpException("invalid template " + path);
        }
    }

    public static Gray parse(String text) {
        List<String> tokens = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            for (String token : trimmed.split("\\s+")) if (!token.isEmpty()) tokens.add(token);
        }
        if (tokens.isEmpty() || !"P2".equals(tokens.get(0))) throw new CdpException("invalid template");
        int width = Integer.parseInt(tokens.get(1));
        int height = Integer.parseInt(tokens.get(2));
        int max = Integer.parseInt(tokens.get(3));
        int[] pixels = new int[width * height];
        if (tokens.size() < 4 + pixels.length) throw new CdpException("invalid template");
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (int) Math.round(255.0 * Integer.parseInt(tokens.get(4 + i)) / Math.max(1, max));
        }
        return new Gray(width, height, pixels);
    }

    /** 1 when the images are identical. Negative correlation becomes 0. */
    public static double score(Gray template, Gray crop) {
        if (template == null || crop == null) return 0;
        if (template.width == crop.width && template.height == crop.height) return ncc(template.pixels, crop.pixels);
        if (template.width > crop.width || template.height > crop.height) {
            Gray fitted = resize(template, crop.width, crop.height);
            return ncc(fitted.pixels, crop.pixels);
        }
        double best = 0;
        for (int y = 0; y <= crop.height - template.height; y++) {
            for (int x = 0; x <= crop.width - template.width; x++) {
                int[] window = window(crop, x, y, template.width, template.height);
                best = Math.max(best, ncc(template.pixels, window));
            }
        }
        return best;
    }

    public static Gray crop(Gray viewport, int x, int y, int width, int height) {
        if (viewport == null || width <= 0 || height <= 0) return null;
        int left = Math.max(0, x);
        int top = Math.max(0, y);
        int right = Math.min(viewport.width, x + width);
        int bottom = Math.min(viewport.height, y + height);
        if (right <= left || bottom <= top) return null;
        return windowGray(viewport, left, top, right - left, bottom - top);
    }

    private static Gray windowGray(Gray source, int x, int y, int width, int height) {
        return new Gray(width, height, window(source, x, y, width, height));
    }

    private static int[] window(Gray source, int x, int y, int width, int height) {
        int[] pixels = new int[width * height];
        for (int row = 0; row < height; row++) {
            System.arraycopy(source.pixels, (y + row) * source.width + x, pixels, row * width, width);
        }
        return pixels;
    }

    private static Gray resize(Gray source, int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            int sy = Math.min(source.height - 1, y * source.height / height);
            for (int x = 0; x < width; x++) {
                int sx = Math.min(source.width - 1, x * source.width / width);
                pixels[y * width + x] = source.pixels[sy * source.width + sx];
            }
        }
        return new Gray(width, height, pixels);
    }

    static double ncc(int[] left, int[] right) {
        int n = Math.min(left.length, right.length);
        if (n == 0) return 0;
        double meanL = 0;
        double meanR = 0;
        for (int i = 0; i < n; i++) {
            meanL += left[i];
            meanR += right[i];
        }
        meanL /= n;
        meanR /= n;
        double num = 0;
        double denL = 0;
        double denR = 0;
        for (int i = 0; i < n; i++) {
            double a = left[i] - meanL;
            double b = right[i] - meanR;
            num += a * b;
            denL += a * a;
            denR += b * b;
        }
        double den = Math.sqrt(denL * denR);
        if (den == 0) return denL == 0 && denR == 0 ? 1 : 0;
        return Math.max(0, num / den);
    }

    public static String why(double score, String template) {
        return String.format(Locale.ROOT, "image matched conf=%.2f template=%s", score, template);
    }
}
