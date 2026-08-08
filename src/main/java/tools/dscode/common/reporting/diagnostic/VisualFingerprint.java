package tools.dscode.common.reporting.diagnostic;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;

/** Compact deterministic screenshot fingerprint. V1 is ~7.8 KB per image. */
public final class VisualFingerprint {
    public static final int VERSION = 1;
    static final int GRID_W = 64;
    static final int GRID_H = 36;
    static final int EDGE_W = 32;
    static final int EDGE_H = 18;
    static final int HIST_BINS = 64;
    private static final int MAGIC = 0x504B4246; // PKBF

    private final int width;
    private final int height;
    private final byte[] ycbcr;
    private final byte[] edges;
    private final short[] histogram;
    private final byte[] sha256;
    private final long dHash;

    private VisualFingerprint(
            int width, int height, byte[] ycbcr, byte[] edges,
            short[] histogram, byte[] sha256, long dHash
    ) {
        this.width = width;
        this.height = height;
        this.ycbcr = ycbcr;
        this.edges = edges;
        this.histogram = histogram;
        this.sha256 = sha256;
        this.dHash = dHash;
    }

    public static VisualFingerprint fromImageBytes(byte[] imageBytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (image == null) throw new IOException("Unsupported image format; V1 supports ImageIO PNG/JPEG inputs.");
        return fromImage(image);
    }

    public static VisualFingerprint fromImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width < 1 || height < 1) throw new IllegalArgumentException("Image must have positive dimensions");

        int[] canonical = canonicalRgb(image);
        byte[] grid = areaGrid(canonical, width, height, GRID_W, GRID_H);
        byte[] edges = edgeGrid(grid);
        short[] histogram = histogram(canonical);
        byte[] sha = sha256(canonical);
        long dhash = dHash(canonical, width, height);
        return new VisualFingerprint(width, height, grid, edges, histogram, sha, dhash);
    }

    public byte[] toBytes() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(8_000);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeByte(VERSION);
                out.writeInt(width);
                out.writeInt(height);
                out.writeShort(GRID_W);
                out.writeShort(GRID_H);
                out.write(ycbcr);
                out.writeShort(EDGE_W);
                out.writeShort(EDGE_H);
                out.write(edges);
                out.writeShort(HIST_BINS);
                for (short value : histogram) out.writeShort(value & 0xffff);
                out.writeLong(dHash);
                out.write(sha256);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static VisualFingerprint fromBytes(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a Pickleball visual fingerprint");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported fingerprint version: " + version);
            int width = in.readInt();
            int height = in.readInt();
            int gridW = in.readUnsignedShort();
            int gridH = in.readUnsignedShort();
            if (gridW != GRID_W || gridH != GRID_H) throw new IOException("Unexpected fingerprint grid size");
            byte[] grid = in.readNBytes(GRID_W * GRID_H * 3);
            if (grid.length != GRID_W * GRID_H * 3) throw new IOException("Truncated fingerprint grid");
            int edgeW = in.readUnsignedShort();
            int edgeH = in.readUnsignedShort();
            if (edgeW != EDGE_W || edgeH != EDGE_H) throw new IOException("Unexpected fingerprint edge size");
            byte[] edges = in.readNBytes(EDGE_W * EDGE_H);
            if (edges.length != EDGE_W * EDGE_H) throw new IOException("Truncated fingerprint edges");
            int bins = in.readUnsignedShort();
            if (bins != HIST_BINS) throw new IOException("Unexpected fingerprint histogram size");
            short[] hist = new short[HIST_BINS];
            for (int i = 0; i < hist.length; i++) hist[i] = (short) in.readUnsignedShort();
            long dhash = in.readLong();
            byte[] sha = in.readNBytes(32);
            if (sha.length != 32) throw new IOException("Truncated fingerprint hash");
            return new VisualFingerprint(width, height, grid, edges, hist, sha, dhash);
        }
    }

    public int width() { return width; }
    public int height() { return height; }
    byte[] grid() { return ycbcr; }
    byte[] edges() { return edges; }
    short[] histogram() { return histogram; }
    byte[] sha256() { return sha256; }
    long dHash() { return dHash; }

    private static int[] canonicalRgb(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] out = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                if (a != 255) {
                    r = (r * a + 255 * (255 - a) + 127) / 255;
                    g = (g * a + 255 * (255 - a) + 127) / 255;
                    b = (b * a + 255 * (255 - a) + 127) / 255;
                }
                out[y * width + x] = (r << 16) | (g << 8) | b;
            }
        }
        return out;
    }

    private static byte[] areaGrid(int[] rgb, int width, int height, int gridW, int gridH) {
        byte[] out = new byte[gridW * gridH * 3];
        for (int gy = 0; gy < gridH; gy++) {
            int y0 = gy * height / gridH;
            int y1 = Math.max(y0 + 1, (gy + 1) * height / gridH);
            for (int gx = 0; gx < gridW; gx++) {
                int x0 = gx * width / gridW;
                int x1 = Math.max(x0 + 1, (gx + 1) * width / gridW);
                long rs = 0, gs = 0, bs = 0, count = 0;
                for (int y = y0; y < Math.min(y1, height); y++) {
                    for (int x = x0; x < Math.min(x1, width); x++) {
                        int value = rgb[y * width + x];
                        rs += (value >>> 16) & 0xff;
                        gs += (value >>> 8) & 0xff;
                        bs += value & 0xff;
                        count++;
                    }
                }
                int r = (int) (rs / count);
                int g = (int) (gs / count);
                int b = (int) (bs / count);
                int base = (gy * gridW + gx) * 3;
                out[base] = (byte) clamp((77 * r + 150 * g + 29 * b + 128) >> 8);
                out[base + 1] = (byte) clamp(128 + ((-43 * r - 85 * g + 128 * b) >> 8));
                out[base + 2] = (byte) clamp(128 + ((128 * r - 107 * g - 21 * b) >> 8));
            }
        }
        return out;
    }

    private static byte[] edgeGrid(byte[] grid) {
        int[] coarse = new int[EDGE_W * EDGE_H];
        for (int y = 0; y < EDGE_H; y++) {
            for (int x = 0; x < EDGE_W; x++) {
                int y0 = y * 2;
                int x0 = x * 2;
                int sum = 0;
                for (int yy = 0; yy < 2; yy++) {
                    for (int xx = 0; xx < 2; xx++) {
                        int base = ((y0 + yy) * GRID_W + x0 + xx) * 3;
                        sum += grid[base] & 0xff;
                    }
                }
                coarse[y * EDGE_W + x] = sum / 4;
            }
        }
        byte[] edges = new byte[coarse.length];
        for (int y = 0; y < EDGE_H; y++) {
            for (int x = 0; x < EDGE_W; x++) {
                int here = coarse[y * EDGE_W + x];
                int right = coarse[y * EDGE_W + Math.min(x + 1, EDGE_W - 1)];
                int down = coarse[Math.min(y + 1, EDGE_H - 1) * EDGE_W + x];
                edges[y * EDGE_W + x] = (byte) clamp(Math.abs(here - right) + Math.abs(here - down));
            }
        }
        return edges;
    }

    private static short[] histogram(int[] rgb) {
        long[] counts = new long[HIST_BINS];
        for (int value : rgb) {
            int r = ((value >>> 16) & 0xff) >>> 6;
            int g = ((value >>> 8) & 0xff) >>> 6;
            int b = (value & 0xff) >>> 6;
            counts[(r << 4) | (g << 2) | b]++;
        }
        short[] normalized = new short[HIST_BINS];
        long total = Math.max(1, rgb.length);
        for (int i = 0; i < counts.length; i++) {
            normalized[i] = (short) Math.min(65535, (counts[i] * 65535L + total / 2) / total);
        }
        return normalized;
    }

    private static byte[] sha256(int[] rgb) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int value : rgb) {
                digest.update((byte) (value >>> 16));
                digest.update((byte) (value >>> 8));
                digest.update((byte) value);
            }
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static long dHash(int[] rgb, int width, int height) {
        int[] sample = new int[9 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 9; x++) {
                int sx = Math.min(width - 1, x * width / 9);
                int sy = Math.min(height - 1, y * height / 8);
                int value = rgb[sy * width + sx];
                int r = (value >>> 16) & 0xff;
                int g = (value >>> 8) & 0xff;
                int b = value & 0xff;
                sample[y * 9 + x] = (77 * r + 150 * g + 29 * b + 128) >> 8;
            }
        }
        long hash = 0;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++, bit++) {
                if (sample[y * 9 + x] > sample[y * 9 + x + 1]) hash |= 1L << bit;
            }
        }
        return hash;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VisualFingerprint that
                && width == that.width
                && height == that.height
                && Arrays.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        int result = 31 * width + height;
        return 31 * result + Arrays.hashCode(sha256);
    }
}
