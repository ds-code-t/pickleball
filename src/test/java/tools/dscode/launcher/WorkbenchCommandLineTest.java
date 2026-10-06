package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void coordinationFlagsAreNotForwardedAndDoNotJoinTheName() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(new String[]{
                "isolate",
                "--name=The", "failing", "scenario",
                "--run-id", "run-42",
                "--agent", "agent-7",
                "--group=wave",
                "--sequence=4",
                "--who=Dan", "the", "author",
                "--why=check", "the", "row",
                "--learned=not", "yet"
        });
        assertEquals("The failing scenario", parsed.name());
        assertEquals("run-42", parsed.coordination().runId());
        assertEquals("agent-7", parsed.coordination().agentId());
        assertEquals("wave", parsed.coordination().group());
        assertEquals(4, parsed.coordination().sequence());
        assertEquals("Dan the author", parsed.coordination().who());
        assertEquals("check the row", parsed.coordination().why());
        assertEquals("not yet", parsed.coordination().learned());
        String joined = String.join("\n", parsed.forwarded());
        assertFalse(joined.contains("--run-id"));
        assertFalse(joined.contains("run-42"));
        assertFalse(joined.contains("--agent"));
        assertFalse(joined.contains("--who"));
        assertFalse(joined.contains("--why"));
        assertFalse(joined.contains("--learned"));
        assertFalse(joined.contains("--group"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("short-log"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("note"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("inbox"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("finish"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("presence"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("post"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("history"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("gc-runs"));
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
