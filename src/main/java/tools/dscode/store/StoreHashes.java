package tools.dscode.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** SHA-256 content hashes. Same bytes always produce the same lowercase hex. */
public final class StoreHashes {
    private StoreHashes() {
    }

    public static String sha256(byte[] content) {
        byte[] body = content == null ? new byte[0] : content;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    /** Identity of an entity row: payload, secret references, and content hash. */
    public static String entityIdentity(String payloadJson, String secretRefs, String contentHash) {
        String raw = (payloadJson == null ? "" : payloadJson) + "\n"
                + (secretRefs == null ? "" : secretRefs) + "\n"
                + (contentHash == null ? "" : contentHash);
        return sha256(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256 of the code-source jar that contains {@link StoreHydrator}.
     * A directory code source (tests running from {@code build/classes}) is hashed
     * by streaming each file. The tree is not loaded into one byte array.
     */
    public static String jarHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var code = StoreHydrator.class.getProtectionDomain().getCodeSource();
            Path path = null;
            if (code != null && code.getLocation() != null) {
                path = Path.of(code.getLocation().toURI());
            }
            if (path == null) {
                digest.update("missing-code-source".getBytes(StandardCharsets.UTF_8));
            } else if (Files.isDirectory(path)) {
                hashDirectory(digest, path);
            } else if (Files.isRegularFile(path)) {
                hashStream(digest, path.getFileName().toString(), path);
            } else {
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("jar hash failed", failure);
        }
    }

    private static void hashDirectory(MessageDigest digest, Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                hashStream(digest, relative, file);
            }
        }
    }

    private static void hashStream(MessageDigest digest, String name, Path file) throws IOException {
        digest.update(name.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
    }

    /** Git blob object id: SHA-1 of {@code blob <len>\0} plus the bytes. */
    public static String gitBlob(byte[] content) {
        byte[] body = content == null ? new byte[0] : content;
        byte[] header = ("blob " + body.length + "\0").getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(header);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-1 is required", failure);
        }
    }
}
