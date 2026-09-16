package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchCommandLineTest {
    @Test
    void parseRetentionEqualsForm() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"discover", "--retention=all"}
        );
        assertEquals("all", parsed.retention());
        assertEquals("discover", parsed.command());
    }

    @Test
    void parseRetentionSeparateToken() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"confirm", "--retention", "NONE"}
        );
        assertEquals("none", parsed.retention());
    }

    @Test
    void invalidRetentionFailsAtParseListingAllowedValues() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"discover", "--retention=always"})
        );
        assertTrue(failure.getMessage().contains("all"));
        assertTrue(failure.getMessage().contains("failed"));
        assertTrue(failure.getMessage().contains("none"));
        assertTrue(failure.getMessage().contains("always"));
    }

    @Test
    void retentionIsNotAbsorbedIntoMavenExecNameJoining() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"discover", "--name=The", "failing", "scenario", "--retention", "all"}
        );
        assertEquals("The failing scenario", parsed.name());
        assertEquals("all", parsed.retention());
    }

    @Test
    void omittedRetentionStaysNull() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"hint"}
        );
        assertNull(parsed.retention());
    }
}
