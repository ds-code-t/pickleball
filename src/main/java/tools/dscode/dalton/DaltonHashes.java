package tools.dscode.dalton;

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
}
