package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D1: a bare boolean flag stays true when the next token is another flag. */
class HeadedFlagTest {
    @Test
    void bareHeadedIsTrueBesideAnotherFlagAndWhenItIsLast() {
        WorkbenchCommandLine.Parsed beside = WorkbenchCommandLine.parse(new String[]{
                "start", "--headed", "--panel", "."
        });
        assertTrue(ConsumerReadyCommands.headedRequest(beside), " --headed --panel");
        WorkbenchCommandLine.Parsed last = WorkbenchCommandLine.parse(new String[]{
                "start", "--panel", "--headed", "."
        });
        assertTrue(ConsumerReadyCommands.headedRequest(last), "--headed last");
        WorkbenchCommandLine.Parsed off = WorkbenchCommandLine.parse(new String[]{
                "start", "--headed=false", "--panel", "."
        });
        assertFalse(ConsumerReadyCommands.headedRequest(off), "--headed=false");
        WorkbenchCommandLine.Parsed explicit = WorkbenchCommandLine.parse(new String[]{
                "start", "--headed=true", "--panel", "."
        });
        assertTrue(ConsumerReadyCommands.headedRequest(explicit));
    }
}
