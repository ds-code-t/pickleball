package tools.dscode.release;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * SHA-256 of the working tree the way {@code generatePickleballBuildProvenance}
 * defined it: trimmed {@code git status --porcelain}, a newline, the trimmed
 * UTF-8 {@code git diff --binary}, a newline, the trimmed staged binary diff,
 * a newline, and {@code path=sha256} lines for untracked files.
 *
 * <p>Diff bytes are decoded and written to a temp file as they arrive, then
 * hashed. A non-zero git exit contributes nothing, matching the old
 * {@code gitText} fallback. Trim characters are {@code c <= ' '}, the same
 * rule as {@link String#trim()} after a UTF-8 decode with replacement.
 */
public final class WorkingTreeDiffHash {
    private WorkingTreeDiffHash() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: WorkingTreeDiffHash <work-tree>");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        String status = gitText(root, "status", "--porcelain", "--untracked-files=normal");
        boolean dirty = !status.isBlank();
        String hash = "";
        if (dirty) {
            try {
                hash = hashDirtyTree(root, status);
            } catch (IOException | NoSuchAlgorithmException failure) {
                hash = "";
            }
        }
        System.out.println(dirty);
        System.out.println(hash);
    }

    /**
     * Same concatenation as the Gradle task. A blank status yields an empty
     * hash and does not read the diff arrays.
     */
    public static String hashParts(String status, byte[] unstaged, byte[] staged, String untracked) {
        if (status == null || status.isBlank()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateUtf8(digest, status);
            updateUtf8(digest, "\n");
            updateTrimmedUtf8(digest, unstaged == null ? InputStream.nullInputStream() : new ByteArrayInputStream(unstaged));
            updateUtf8(digest, "\n");
            updateTrimmedUtf8(digest, staged == null ? InputStream.nullInputStream() : new ByteArrayInputStream(staged));
            updateUtf8(digest, "\n");
            updateUtf8(digest, untracked == null ? "" : untracked);
            return hex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hashDirtyTree(Path root, String status) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateUtf8(digest, status);
        updateUtf8(digest, "\n");
        hashGitDiff(digest, root, "diff", "--binary");
        updateUtf8(digest, "\n");
        hashGitDiff(digest, root, "diff", "--cached", "--binary", "HEAD");
        updateUtf8(digest, "\n");
        updateUtf8(digest, untrackedFingerprint(root));
        return hex(digest.digest());
    }

    /** A non-zero exit leaves the digest unchanged. */
    private static void hashGitDiff(MessageDigest digest, Path root, String... gitArgs) {
        Path trimmed = null;
        Process process = null;
        try {
            trimmed = Files.createTempFile("pkb-diff-", ".trim");
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(List.of(gitArgs));
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try (OutputStream out = Files.newOutputStream(trimmed)) {
                writeTrimmedUtf8(process.getInputStream(), out);
            }
            int exit = process.waitFor();
            if (exit != 0) return;
            try (InputStream input = Files.newInputStream(trimmed)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
        } catch (Throwable ignored) {
            if (process != null) process.destroyForcibly();
        } finally {
            if (trimmed != null) {
                try { Files.deleteIfExists(trimmed); } catch (IOException ignored) { }
            }
        }
    }

    static void updateTrimmedUtf8(MessageDigest digest, InputStream raw) throws IOException {
        ByteArrayOutputStream trimmed = new ByteArrayOutputStream();
        writeTrimmedUtf8(raw, trimmed);
        digest.update(trimmed.toByteArray());
    }

    /**
     * UTF-8 decode with replacement, drop leading and trailing {@code c <= ' '},
     * encode the rest. The body is written through. A trailing trim run is held
     * in a small buffer and spilled to the output only when a later real
     * character arrives; at end of input that run is discarded.
     */
    static void writeTrimmedUtf8(InputStream raw, OutputStream out) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        ByteBuffer input = ByteBuffer.allocate(8192);
        CharBuffer decoded = CharBuffer.allocate(8192);
        byte[] chunk = new byte[8192];
        TrimWriter writer = new TrimWriter(out);
        boolean eof = false;
        while (!eof) {
            int read = raw.read(chunk);
            eof = read < 0;
            if (!eof) input.put(chunk, 0, read);
            input.flip();
            while (true) {
                var result = decoder.decode(input, decoded, eof);
                decoded.flip();
                writer.accept(decoded);
                decoded.clear();
                if (result.isOverflow()) continue;
                if (result.isError()) result.throwException();
                if (eof && result.isUnderflow()) {
                    var flushed = decoder.flush(decoded);
                    decoded.flip();
                    writer.accept(decoded);
                    decoded.clear();
                    if (flushed.isOverflow()) continue;
                }
                break;
            }
            input.compact();
        }
        writer.finish();
    }

    static void updateUtf8(MessageDigest digest, String text) {
        if (text == null || text.isEmpty()) return;
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String untrackedFingerprint(Path root) {
        String listing = gitText(root, "ls-files", "--others", "--exclude-standard");
        List<String> lines = new ArrayList<>();
        for (String line : listing.split("\n", -1)) {
            if (!line.isBlank()) lines.add(line + "=" + fileSha256(root.resolve(line)));
        }
        return String.join("\n", lines);
    }

    private static String fileSha256(Path file) {
        if (!Files.isRegularFile(file)) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException failure) {
            return "";
        }
    }

    private static String gitText(Path root, String... args) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(List.of(args));
            process = new ProcessBuilder(command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] bytes = process.getInputStream().readAllBytes();
            if (process.waitFor() != 0) return "";
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            if (process != null) process.destroyForcibly();
            return "";
        }
    }

    static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format("%02x", value & 0xff));
        return out.toString();
    }

    /**
     * Holds a trailing trim run. The run is only spaces and other
     * {@code c <= ' '} bytes, so it stays small for a real diff. A hostile
     * multi-megabyte trim run spills to a temp file instead of the heap.
     */
    private static final class TrimWriter {
        private static final int SPILL_AT = 256 * 1024;

        private final OutputStream out;
        private boolean started;
        private final StringBuilder content = new StringBuilder();
        private final ByteArrayOutputStream held = new ByteArrayOutputStream();
        private Path spill;
        private OutputStream spillOut;

        private TrimWriter(OutputStream out) {
            this.out = out;
        }

        private void accept(CharBuffer chars) throws IOException {
            while (chars.hasRemaining()) accept(chars.get());
        }

        private void accept(char value) throws IOException {
            if (value <= ' ') {
                if (!started) return;
                flushContent();
                hold(value);
                return;
            }
            flushHeld();
            started = true;
            content.append(value);
            if (content.length() >= 2048
                    && !Character.isHighSurrogate(content.charAt(content.length() - 1))) {
                flushContent();
            }
        }

        private void finish() throws IOException {
            flushContent();
            held.reset();
            closeSpill(true);
        }

        private void hold(char value) throws IOException {
            if (spillOut != null) {
                spillOut.write(value);
                return;
            }
            held.write(value);
            if (held.size() >= SPILL_AT) {
                spill = Files.createTempFile("pkb-trim-", ".bin");
                spillOut = Files.newOutputStream(spill);
                held.writeTo(spillOut);
                held.reset();
            }
        }

        private void flushHeld() throws IOException {
            if (spillOut != null) {
                spillOut.close();
                spillOut = null;
                try (InputStream input = Files.newInputStream(spill)) {
                    input.transferTo(out);
                }
                Files.deleteIfExists(spill);
                spill = null;
            }
            if (held.size() > 0) {
                held.writeTo(out);
                held.reset();
            }
        }

        private void flushContent() throws IOException {
            if (content.isEmpty()) return;
            out.write(content.toString().getBytes(StandardCharsets.UTF_8));
            content.setLength(0);
        }

        private void closeSpill(boolean delete) throws IOException {
            if (spillOut != null) {
                spillOut.close();
                spillOut = null;
            }
            if (delete && spill != null) {
                Files.deleteIfExists(spill);
                spill = null;
            }
        }
    }
}
