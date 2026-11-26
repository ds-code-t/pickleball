package tools.dscode.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One owner per debug port. Port 9222 rejects a second attach. */
class DebugPortOwnerTest {
    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        DebugPortOwner.resetForTests();
    }

    @Test
    void secondAttachTo9222FailsAndAnotherPortSucceeds() throws Exception {
        DebugPortOwner.attach(9222, "first", temp);
        assertFalse(Files.exists(temp.resolve(".pickleball").resolve("workbench").resolve("cli-session.json")));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DebugPortOwner.attach(9222, "second", temp));
        assertTrue(failure.getMessage().contains("error:"));
        assertTrue(failure.getMessage().contains("9222"));
        assertTrue(failure.getMessage().contains("fix:"));
        DebugPortOwner.attach(9333, "second", temp);
        assertTrue(DebugPortOwner.ports().contains("9333 second"));

        Path session = temp.resolve(".pickleball").resolve("workbench").resolve("cli-session.json");
        Files.createDirectories(session.getParent());
        Files.writeString(session, "{\"pid\":1,\"token\":\"abc\"}");
        DebugPortOwner.attach(9444, "recorded", temp);
        String text = Files.readString(session);
        assertTrue(text.contains("9444"));
        assertTrue(text.contains("recorded"));
        assertEquals(1, new ObjectMapper().readTree(text).path("pid").asInt());
    }
}
