package tools.dscode.dalton;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 content hashes. Same bytes always produce the same lowercase hex. */
public final class DaltonHashes {
    private DaltonHashes() {
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
