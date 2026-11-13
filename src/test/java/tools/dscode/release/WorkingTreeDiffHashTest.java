package tools.dscode.release;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkingTreeDiffHashTest {
    @Test
    void blankStatusDoesNotHashTheDiff() {
        byte[] huge = new byte[1024];
        Arrays.fill(huge, (byte) 'x');
        assertEquals("", WorkingTreeDiffHash.hashParts("  \n", huge, huge, "file=abc"));
        assertEquals("", WorkingTreeDiffHash.hashParts("", huge, huge, "file=abc"));
    }

    @Test
    void streamedTrimMatchesTheOldStringConcatenation() throws Exception {
        String status = " M src/Example.java\n?? notes.txt";
        byte[] unstaged = payload("\n\t  ", "diff --git a/src/Example.java", "\0\0  \n");
        byte[] staged = payload("  ", "index 111..222\n+kept line\n", " \r\n");
        String untracked = "notes.txt=" + "ab".repeat(32);
        assertEquals(legacy(status, unstaged, staged, untracked),
                WorkingTreeDiffHash.hashParts(status, unstaged, staged, untracked));
    }

    @Test
    void multiMegabyteBinaryDiffMatchesAndSpillsALongTrimRun() throws Exception {
        byte[] body = new byte[2 * 1024 * 1024];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31);
        body[10] = (byte) 0xFF;
        body[11] = (byte) 0x80;
        byte[] unstaged = payload("\0\n", new String(body, StandardCharsets.ISO_8859_1), "\n\n");
        // A long interior trim run must survive. It is not a trailing trim.
        byte[] staged = payload("A", " ".repeat(300_000), "B");
        String untracked = "";
        String status = "M reports/report.xlsx";
        assertEquals(legacy(status, unstaged, staged, untracked),
                WorkingTreeDiffHash.hashParts(status, unstaged, staged, untracked));
        ByteArrayOutputStream direct = new ByteArrayOutputStream();
        WorkingTreeDiffHash.writeTrimmedUtf8(new java.io.ByteArrayInputStream(staged), direct);
        String trimmed = direct.toString(StandardCharsets.UTF_8);
        assertTrue(trimmed.startsWith("A"));
        assertTrue(trimmed.endsWith("B"));
        assertEquals(2 + 300_000, trimmed.length());
    }

    private static byte[] payload(String head, String body, String tail) {
        return (head + body + tail).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String legacy(String status, byte[] unstaged, byte[] staged, String untracked) throws Exception {
        String joined = status + "\n"
                + new String(unstaged, StandardCharsets.UTF_8).trim() + "\n"
                + new String(staged, StandardCharsets.UTF_8).trim() + "\n"
                + untracked;
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
        return WorkingTreeDiffHash.hex(digest);
    }
}
