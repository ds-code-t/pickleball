package dev.domengine.snapshot;

import dev.domengine.cdp.CdpException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;

/** PNG header, sha256, and optional base64. No image library. */
final class PngImage {
    private final int width;
    private final int height;
    private final String sha256;
    private final String base64;
    private final byte[] bytes;

    private PngImage(int width, int height, String sha256, String base64, byte[] bytes) {
        this.width = width;
        this.height = height;
        this.sha256 = sha256;
        this.base64 = base64;
        this.bytes = bytes;
    }

    static PngImage read(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new CdpException("screenshot file: " + failure.getMessage());
        }
        if (bytes.length < 24 || bytes[0] != (byte) 0x89 || bytes[1] != 0x50 || bytes[2] != 0x4E || bytes[3] != 0x47) {
            throw new CdpException("error: screenshot is not a png\nfix: Pass a PNG file to --screenshot.");
        }
        int width = readInt(bytes, 16);
        int height = readInt(bytes, 20);
        return new PngImage(width, height, sha256(bytes), Base64.getEncoder().encodeToString(bytes), bytes);
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    String sha256() {
        return sha256;
    }

    String base64() {
        return base64;
    }

    byte[] bytes() {
        return bytes;
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }
}
