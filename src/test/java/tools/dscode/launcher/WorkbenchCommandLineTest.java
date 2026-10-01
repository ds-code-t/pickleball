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
    void exampleJoinsSplitTokensWithoutStealingTheName() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"discover", "--name=The", "failing", "scenario", "--example=1", "2", "5", "3.4", "7-11"}
        );
        assertEquals("The failing scenario", parsed.name());
        assertEquals("1 2 5 3.4 7-11", parsed.example());
    }

    @Test
    void nameDoesNotAbsorbTokensAfterExample() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"confirm", "--example=1", "2", "--name=The", "failing", "scenario"}
        );
        assertEquals("1 2", parsed.example());
        assertEquals("The failing scenario", parsed.name());
    }

    @Test
    void blankExampleIsNotForwarded() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"isolate", "--example="}
        );
        assertNull(parsed.example());
        assertTrue(java.util.Arrays.stream(parsed.forwarded()).noneMatch("--example"::equals));
    }

    @Test
    void isolateForwardsExampleAfterName() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"isolate", "--tags=@one", "--name=The", "failing", "scenario", "--example=1", "2"}
        );
        assertEquals("--tags", parsed.forwarded()[2]);
        assertEquals("@one", parsed.forwarded()[3]);
        assertEquals("--name", parsed.forwarded()[4]);
        assertEquals("The failing scenario", parsed.forwarded()[5]);
        assertEquals("--example", parsed.forwarded()[6]);
        assertEquals("1 2", parsed.forwarded()[7]);
    }

    @Test
    void omittedRetentionStaysNull() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"hint"}
        );
        assertNull(parsed.retention());
    }

    @Test
    void resolveRunVarsIsAnAgentCoreCommand() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(
                new String[]{"resolve-runvars", "."}
        );
        assertEquals("resolve-runvars", parsed.command());
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("resolve-runvars"));
    }
}
