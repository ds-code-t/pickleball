// file: tools/dscode/common/reporting/logging/Attachment.java
package tools.dscode.common.reporting.logging;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public record Attachment(String name, String mime, String path, StorageKind storageKind) {

    private static final Duration STALE_TEMP_AGE = Duration.ofHours(12);
    private static final String TEMP_ROOT_PROPERTY = "dscode.logging.attachmentTempRoot";
    private static final String TEMP_ROOT_DIR_NAME = "dscode-logging-attachments";
    private static final AtomicBoolean STALE_CLEANUP_DONE = new AtomicBoolean(false);
    private static final AtomicLong ATTACHMENT_SEQUENCE = new AtomicLong();

    private static final String RUN_ID = newRunId();
    private static final Path RUN_TEMP_DIR = initRunTempDir();

    public enum StorageKind {
        INLINE_TEXT,
        INLINE_BASE64,
        FILE_BINARY,
        FILE_BASE64
    }

    public Attachment(String name, String mime, String path) {
        this(name, mime, path, inferStorageKind(mime));
    }

    public Attachment {
        storageKind = storageKind == null ? inferStorageKind(mime) : storageKind;
    }

    public static Attachment of(String name, String mime, String payload) {
        if (isBase64Mime(mime)) {
            return base64File(name, baseMime(mime), payload);
        }
        return new Attachment(name, mime, payload, StorageKind.INLINE_TEXT);
    }

    public static Attachment binaryFile(String name, String mime, Path file) {
        return new Attachment(name, mime, file == null ? null : file.toString(), StorageKind.FILE_BINARY);
    }

    public static Attachment base64File(String name, String mime, String base64) {
        try {
            Path dir = RUN_TEMP_DIR;
            Files.createDirectories(dir);

            String safeName = safeFilePart(name == null || name.isBlank() ? "attachment" : name);
            if (safeName.isBlank()) safeName = "attachment";

            long seq = ATTACHMENT_SEQUENCE.incrementAndGet();
            Path file = dir.resolve(String.format(Locale.ROOT, "%08d-%s.b64", seq, safeName));
            Files.writeString(file, rawBase64(base64), StandardCharsets.US_ASCII);
            return new Attachment(name, baseMime(mime), file.toString(), StorageKind.FILE_BASE64);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write base64 attachment temp file", e);
        }
    }

    public boolean isInlineBase64() {
        return storageKind == StorageKind.INLINE_BASE64;
    }

    public boolean isFileBase64() {
        return storageKind == StorageKind.FILE_BASE64;
    }

    public boolean isFileBinary() {
        return storageKind == StorageKind.FILE_BINARY;
    }

    public boolean isFileBacked() {
        return isFileBase64() || isFileBinary();
    }

    public String mediaType() {
        return baseMime(mime);
    }

    public static Path currentRunTempDir() {
        return RUN_TEMP_DIR;
    }

    public static void cleanupCurrentRunTempFiles() {
        deleteRecursively(RUN_TEMP_DIR);
    }

    private static StorageKind inferStorageKind(String mime) {
        return isBase64Mime(mime) ? StorageKind.INLINE_BASE64 : StorageKind.INLINE_TEXT;
    }

    private static boolean isBase64Mime(String mime) {
        return mime != null && mime.toLowerCase(Locale.ROOT).contains("base64");
    }

    private static String baseMime(String mime) {
        if (mime == null || mime.isBlank()) return "application/octet-stream";
        String m = mime.trim();
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi);
        m = m.replace("base64", "").trim();
        if (m.endsWith("/")) m = m.substring(0, m.length() - 1);
        return m.isBlank() ? "application/octet-stream" : m;
    }

    private static String rawBase64(String maybeDataUri) {
        if (maybeDataUri == null) return "";
        String s = maybeDataUri.trim();
        int comma = s.indexOf(',');
        if (s.regionMatches(true, 0, "data:", 0, 5) && comma >= 0 && comma + 1 < s.length()) {
            return s.substring(comma + 1).trim();
        }
        return s;
    }

    private static Path initRunTempDir() {
        Path root = tempRoot();
        cleanupStaleRunDirs(root);
        Path run = root.resolve(RUN_ID);
        try {
            Files.createDirectories(run);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create attachment temp directory: " + run, e);
        }
        return run;
    }

    private static Path tempRoot() {
        String configured = System.getProperty(TEMP_ROOT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DSCODE_LOGGING_ATTACHMENT_TEMP_ROOT");
        }
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("java.io.tmpdir"), TEMP_ROOT_DIR_NAME).toAbsolutePath().normalize();
    }

    private static void cleanupStaleRunDirs(Path root) {
        if (!STALE_CLEANUP_DONE.compareAndSet(false, true)) return;

        try {
            Files.createDirectories(root);
            Instant cutoff = Instant.now().minus(STALE_TEMP_AGE);

            try (Stream<Path> children = Files.list(root)) {
                children
                        .filter(Files::isDirectory)
                        .filter(p -> p.getFileName() != null && p.getFileName().toString().startsWith("run-"))
                        .filter(p -> isOlderThan(p, cutoff))
                        .forEach(Attachment::deleteRecursively);
            }
        } catch (Throwable ignored) {
            // Temp cleanup must never break logging.
        }
    }

    private static boolean isOlderThan(Path p, Instant cutoff) {
        try {
            FileTime t = Files.getLastModifiedTime(p);
            return t.toInstant().isBefore(cutoff);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void deleteRecursively(Path p) {
        if (p == null || !Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Throwable ignored) {
                    // Best-effort cleanup only.
                }
            });
        } catch (Throwable ignored) {
            // Best-effort cleanup only.
        }
    }

    private static String newRunId() {
        String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.now());
        return "run-" + ts + "-pid" + pid() + "-" + UUID.randomUUID();
    }

    private static long pid() {
        try {
            return ProcessHandle.current().pid();
        } catch (Throwable ignored) {
            try {
                String jvm = ManagementFactory.getRuntimeMXBean().getName();
                int at = jvm.indexOf('@');
                return Long.parseLong(at < 0 ? jvm : jvm.substring(0, at));
            } catch (Throwable ignoredAgain) {
                return -1L;
            }
        }
    }

    private static String safeFilePart(String text) {
        if (text == null) return "attachment";
        String s = text.trim()
                .replaceAll("[\\r\\n\\t]+", "-")
                .replaceAll("[^a-zA-Z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (s.length() > 120) s = s.substring(0, 120).replaceAll("-+$", "");
        return s.isBlank() ? "attachment" : s;
    }
}
