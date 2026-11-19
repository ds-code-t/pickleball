package tools.dscode.common.agentfast;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The host forgets the oldest run once the run cap is full, so that run's reason logs again.
 */
class FallbackRunCapTest {
    @TempDir
    Path temp;

    @AfterEach
    void reset() {
        FallbackLog.resetForTests();
    }

    @Test
    void oldestRunLogsAgainAfterTheRunCap() throws Exception {
        FallbackLog.note(temp, "run-0", "act", "cdp", "js", "same");
        Path log = temp.resolve(".pickleball/logs/fallback-chain.jsonl");
        assertEquals(1, count(Files.readString(log), "\"run\":\"run-0\""));
        FallbackLog.note(temp, "run-0", "act", "cdp", "js", "same");
        assertEquals(1, count(Files.readString(log), "\"run\":\"run-0\""));

        for (int index = 1; index <= FallbackLog.RUNS_CAP; index++) {
            FallbackLog.note(temp, "run-" + index, "act", "cdp", "js", "same");
        }
        FallbackLog.note(temp, "run-0", "act", "cdp", "js", "same");
        String text = Files.readString(log);
        assertEquals(2, count(text, "\"run\":\"run-0\""), text);
        FallbackLog.note(temp, "run-" + FallbackLog.RUNS_CAP, "act", "cdp", "js", "same");
        assertEquals(1, count(Files.readString(log), "\"run\":\"run-" + FallbackLog.RUNS_CAP + "\""));
    }

    private static int count(String text, String token) {
        int found = 0;
        int from = 0;
        while (from >= 0) {
            int at = text.indexOf(token, from);
            if (at < 0) return found;
            found++;
            from = at + token.length();
        }
        return found;
    }
}
