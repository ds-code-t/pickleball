package tools.dscode.launcher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

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
    void planRefreshAndSelectStayOutOfTheName() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(new String[]{
                "discover",
                "--plan",
                "--refresh",
                "--tags=@smoke",
                "--name=Find",
                "term",
                "--select",
                "src/test/resources/features/search.feature:9",
                "--select=src/test/resources/features/search.feature:13"
        });
        assertTrue(parsed.planOnly());
        assertTrue(parsed.refreshIndex());
        assertFalse(parsed.fullScan());
        assertEquals("Find term", parsed.name());
        assertEquals("@smoke", parsed.tags());
        assertEquals(List.of(
                "src/test/resources/features/search.feature:9",
                "src/test/resources/features/search.feature:13"
        ), parsed.selects());
    }

    @Test
    void noNarrowAndFullScanSkipTopLevelNarrowing() {
        WorkbenchCommandLine.Parsed noNarrow = WorkbenchCommandLine.parse(new String[]{
                "discover", "--tags=@caller", "--name=The", "caller", "--no-narrow"
        });
        assertTrue(noNarrow.fullScan());
        assertEquals("The caller", noNarrow.name());
        assertEquals("@caller", noNarrow.tags());

        WorkbenchCommandLine.Parsed fullScan = WorkbenchCommandLine.parse(new String[]{
                "confirm", "--full-scan", "--tags=@caller"
        });
        assertTrue(fullScan.fullScan());
        assertEquals("@caller", fullScan.tags());
    }

    @Test
    void bareFeatureLineIsASelectionNotAProject() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(new String[]{
                "plan", "features/login.feature:4"
        });
        assertEquals("plan", parsed.command());
        assertEquals(List.of("features/login.feature:4"), parsed.selects());
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
    void unparseableTagOrNameIsAQuotingError() {
        IllegalArgumentException quotes = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"discover", "--tags=@a and \"oops"})
        );
        assertTrue(quotes.getMessage().contains("quoting"));
        assertTrue(quotes.getMessage().toLowerCase().contains("quote"));

        IllegalArgumentException parens = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"confirm", "--tags=(@a and @b"})
        );
        assertTrue(parens.getMessage().contains("quoting"));
        assertTrue(parens.getMessage().toLowerCase().contains("parenthesis"));

        IllegalArgumentException name = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{"plan", "--name=[unclosed"})
        );
        assertTrue(name.getMessage().contains("quoting"));
    }

    @Test
    void tagsFileAndEncodedFormBecomeTheParsedExpression() throws Exception {
        Path file = Files.createTempFile("pickleball-tags", ".txt");
        try {
            Files.writeString(file, "@smoke and (@wip or @slow)\n", StandardCharsets.UTF_8);
            WorkbenchCommandLine.Parsed fromFile = WorkbenchCommandLine.parse(new String[]{
                    "discover", "--tags-file=" + file.toAbsolutePath()
            });
            assertEquals("@smoke and (@wip or @slow)", fromFile.tags());
        } finally {
            Files.deleteIfExists(file);
        }
        String encoded = Base64.getEncoder().encodeToString("@nightly and not @wip".getBytes(StandardCharsets.UTF_8));
        WorkbenchCommandLine.Parsed fromEncoded = WorkbenchCommandLine.parse(new String[]{
                "discover", "--tags-b64=" + encoded
        });
        assertEquals("@nightly and not @wip", fromEncoded.tags());
    }

    @Test
    void balancedExpressionsStillParse() {
        WorkbenchCommandLine.Parsed parsed = WorkbenchCommandLine.parse(new String[]{
                "discover", "--tags=@smoke and not @wip", "--name=Find term"
        });
        assertEquals("@smoke and not @wip", parsed.tags());
        assertEquals("Find term", parsed.name());
    }

    @Test
    void splitTagsExpressionFailsBeforeAnythingRuns() {
        String[] commands = {"plan", "discover", "confirm", "isolate", "hint", "session-start"};
        for (String command : commands) {
            IllegalArgumentException equalsForm = assertThrows(
                    IllegalArgumentException.class,
                    () -> WorkbenchCommandLine.parse(new String[]{command, "--tags=@A", "and", "not", "@B"})
            );
            assertSplitTagsMessage(equalsForm.getMessage());

            IllegalArgumentException separateForm = assertThrows(
                    IllegalArgumentException.class,
                    () -> WorkbenchCommandLine.parse(new String[]{command, "--tags", "@A", "and", "not", "@B"})
            );
            assertSplitTagsMessage(separateForm.getMessage());
        }

        boolean[] maven = {false};
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int discover = WorkbenchAgentCommands.run(
                new String[]{"discover", "--tags=@A", "and", "not", "@B"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    maven[0] = true;
                    return 0;
                }
        );
        assertEquals(2, discover);
        assertFalse(maven[0]);
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        assertSplitTagsMessage(stderr.toString(StandardCharsets.UTF_8));

        boolean[] planned = {false};
        int plan = WorkbenchAgentCommands.run(
                new String[]{"plan", "--tags=@A", "and", "not", "@B"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    planned[0] = true;
                    return 0;
                }
        );
        assertEquals(2, plan);
        assertFalse(planned[0]);

        boolean[] started = {false};
        int isolate = WorkbenchSessionCommands.run(
                new String[]{"isolate", "--tags=@A", "and", "not", "@B"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                launch -> {
                    started[0] = true;
                    throw new AssertionError("isolate must not start");
                }
        );
        assertEquals(2, isolate);
        assertFalse(started[0]);
    }

    @Test
    void quotedTagsFileAndBase64AreUnchanged() throws Exception {
        WorkbenchCommandLine.Parsed quoted = WorkbenchCommandLine.parse(new String[]{
                "isolate", "--tags=@A and not @B"
        });
        assertEquals("@A and not @B", quoted.tags());
        assertEquals("@A and not @B", quoted.forwarded()[3]);

        WorkbenchCommandLine.Parsed separateQuoted = WorkbenchCommandLine.parse(new String[]{
                "confirm", "--tags", "@smoke and not @wip", "--name=Find term"
        });
        assertEquals("@smoke and not @wip", separateQuoted.tags());
        assertEquals("Find term", separateQuoted.name());

        Path file = Files.createTempFile("pickleball-tags", ".txt");
        try {
            Files.writeString(file, "@smoke and (@wip or @slow)\n", StandardCharsets.UTF_8);
            WorkbenchCommandLine.Parsed fromFile = WorkbenchCommandLine.parse(new String[]{
                    "plan", "--tags-file=" + file.toAbsolutePath(), "--run-id=from-file"
            });
            assertEquals("@smoke and (@wip or @slow)", fromFile.tags());
            assertEquals("from-file", fromFile.coordination().runId());
        } finally {
            Files.deleteIfExists(file);
        }

        String encoded = Base64.getEncoder().encodeToString(
                "@nightly and not @wip".getBytes(StandardCharsets.UTF_8)
        );
        WorkbenchCommandLine.Parsed fromEncoded = WorkbenchCommandLine.parse(new String[]{
                "discover", "--tags-b64=" + encoded, "--run-id=from-b64"
        });
        assertEquals("@nightly and not @wip", fromEncoded.tags());
        assertEquals("from-b64", fromEncoded.coordination().runId());
    }

    @Test
    void laterOptionIsNotAStrayTagWord() {
        WorkbenchCommandLine.Parsed equalsForm = WorkbenchCommandLine.parse(new String[]{
                "discover", "--tags=@A", "--run-id=x"
        });
        assertEquals("@A", equalsForm.tags());
        assertEquals("x", equalsForm.coordination().runId());

        WorkbenchCommandLine.Parsed separateForm = WorkbenchCommandLine.parse(new String[]{
                "confirm", "--tags", "@smoke", "--run-id", "run-7"
        });
        assertEquals("@smoke", separateForm.tags());
        assertEquals("run-7", separateForm.coordination().runId());

        WorkbenchCommandLine.Parsed named = WorkbenchCommandLine.parse(new String[]{
                "plan", "--tags=@smoke", "--name=The", "failing", "scenario", "--run-id=x"
        });
        assertEquals("@smoke", named.tags());
        assertEquals("The failing scenario", named.name());
        assertEquals("x", named.coordination().runId());

        IllegalArgumentException splitBeforeOption = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchCommandLine.parse(new String[]{
                        "isolate", "--tags=@A", "and", "not", "@B", "--run-id=x"
                })
        );
        assertSplitTagsMessage(splitBeforeOption.getMessage());
        assertFalse(splitBeforeOption.getMessage().contains("run-id"));
    }

    private static void assertSplitTagsMessage(String message) {
        assertTrue(message.contains("@A and not @B"), message);
        assertTrue(message.contains("--tags='@A and not @B'"), message);
        assertTrue(message.contains("--tags-file"), message);
        assertTrue(message.contains("--tags-b64"), message);
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
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("gc-versions"));
        assertTrue(WorkbenchCommandLine.isAgentCoreCommand("use-version"));
        WorkbenchCommandLine.Parsed switched = WorkbenchCommandLine.parse(new String[]{
                "use-version", "--version=2.1.14"
        });
        assertEquals("use-version", switched.command());
        assertEquals("2.1.14", switched.version());
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
