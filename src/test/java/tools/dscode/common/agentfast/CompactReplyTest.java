package tools.dscode.common.agentfast;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactReplyTest {
    @TempDir
    Path temp;

    @Test
    void oversizedInspectPageAndStepRepliesStayUnderTwoKib() throws Exception {
        Map<String, Object> huge = new LinkedHashMap<>();
        huge.put("ok", true);
        huge.put("sentence", "page source");
        huge.put("html", "x".repeat(3000));
        Map<String, Object> compact = CompactReply.shrink(temp, "page", huge);
        String json = new ObjectMapper().writeValueAsString(compact);
        System.out.println("MEASURE compactBytes=" + json.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= CompactReply.LIMIT, json);
        assertEquals(true, compact.get("compact"));
        Path full = Path.of(String.valueOf(compact.get("full")));
        assertTrue(Files.size(full) > CompactReply.LIMIT);

        String step = CompactReply.shrinkText(temp, "step", "s".repeat(3000));
        assertTrue(step.getBytes(StandardCharsets.UTF_8).length <= CompactReply.LIMIT, step);
        assertTrue(step.contains("\"compact\":true"));
        String inspect = CompactReply.shrinkText(temp, "inspect", "phrase=Save\n");
        assertEquals("phrase=Save\n", inspect);

        Map<String, Object> shot = ScreenshotReply.write(temp.resolve("shot.png"), new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47});
        assertEquals(4, shot.get("byteSize"));
        assertEquals("image/png", shot.get("mime"));
        assertFalse(shot.containsKey("base64"));
        assertEquals(4, Files.size(temp.resolve("shot.png")));
    }
}
