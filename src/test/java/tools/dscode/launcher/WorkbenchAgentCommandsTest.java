package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchAgentCommandsTest {
    @TempDir
    Path tempDir;

    @Test
    void hintPrintsLadderRunVarsAndNextDiscover() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=chrome\n");

        Output output = run("hint", tempDir.toString());

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("pkb_browser=CHROME_HEADLESS"));
        assertTrue(output.stdout().contains("NEXT: run discover"));
        assertFalse(output.stdout().contains("MUST"));
        assertFalse(output.stdout().contains("pkb_parallel=80"));
    }

    @Test
    void exportGuidanceUsesDiagnosticCli() throws Exception {
        Path outputDir = tempDir.resolve("guidance");
        Output output = run("export-guidance", outputDir.toString());
        assertEquals(0, output.exitCode());
        assertTrue(Files.isRegularFile(outputDir.resolve("AGENT-GUIDE.md")));
        assertTrue(output.stdout().contains("NEXT: follow AGENT-GUIDE"));
        assertTrue(output.stdout().contains("Workbench discover"));
    }

    @Test
    void discoverWrapsMavenAndRecordsSnapshot() throws Exception {
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        List<List<String>> captured = new ArrayList<>();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke"},
                System.out,
                System.err,
                (project, command, out, err) -> {
                    captured.add(command);
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-1",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=4, pkb_reportingmode=diagnostic, pkb_tags=@smoke",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 1;
                }
        );

        assertEquals(1, exit);
        assertEquals(1, captured.size());
        assertTrue(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        assertTrue(captured.getFirst().contains("-Dpkb_run_purpose=workbench-discover"));
        LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.read(tempDir);
        assertTrue(snapshot.hasRunVars());
        assertTrue(snapshot.runProfile().contains("pkb_browser=CHROME_HEADLESS"));
    }

    @Test
    void confirmRequiresDiscoverSnapshot() {
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"confirm", tempDir.toString()},
                System.out,
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals(1, exit);
        String errors = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("No prior Discover snapshot"));
        assertFalse(errors.toLowerCase().contains("register"));
    }

    private Output run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        return new Output(exit, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Output(int exitCode, String stdout, String stderr) { }
}
