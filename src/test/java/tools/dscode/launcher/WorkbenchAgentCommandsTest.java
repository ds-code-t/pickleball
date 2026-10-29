package tools.dscode.launcher;

import io.cucumber.core.gherkin.Pickle;
import io.cucumber.core.runner.modularexecutions.CucumberScanUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;
import tools.dscode.common.reporting.diagnostic.LastDiscoverSnapshot;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PKB_props;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchAgentCommandsTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void captureSeedWithoutAConsumerRunner() {
        WorkspaceLaunch.testCapture = workspace -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : Workspace.CONTEXT_KEYS) values.put(key, key);
            values.put(PKB_props.PKB_BROWSER, "CHROME_HEADLESS");
            values.put(PKB_props.PKB_PARALLEL, "auto");
            Workspace.writeRunVars(workspace, values);
        };
    }

    @AfterEach
    void clearCoordination() {
        WorkspaceLaunch.testCapture = null;
        AgentCoordination.clearCurrent();
        System.clearProperty(PKB_props.PKB_RUN_ID);
        System.clearProperty(PKB_props.PKB_AGENT_ID);
        System.clearProperty("pkb_configpath");
    }

    @Test
    void confirmRejectsStepTimeout() {
        Output output = run("confirm", tempDir.toString(), "--step-timeout=5");
        assertEquals(2, output.exitCode());
        assertTrue(output.stderr().contains("use --run-timeout"), output.stderr());
        assertTrue(output.stderr().contains("per-step limits apply to execute-step"), output.stderr());
    }

    @Test
    void hintPrintsLadderRunVarsAndNextDiscover() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=chrome\n");

        String previousParallel = System.getProperty("pkb_parallel");
        String previousRunVarsParallel = System.getProperty("pkb_runvars.pkb_parallel");
        System.setProperty("pkb_parallel", "80");
        System.setProperty("pkb_runvars.pkb_parallel", "80");
        try {
            Output output = run("hint", tempDir.toString());

            assertEquals(0, output.exitCode());
            assertTrue(output.stdout().contains("runvars.properties"));
            assertTrue(output.stdout().contains("workspace/configs"));
            assertTrue(output.stdout().contains("-Dpkb_workspace"));
            assertTrue(output.stdout().contains("selection keys"));
            assertTrue(output.stdout().contains("workspace-show"));
            assertTrue(output.stdout().contains("workspace-edit"));
            assertTrue(output.stdout().contains("workspace-seed"));
            assertTrue(output.stdout().contains("pkb_browser=chrome"));
            assertFalse(output.stdout().contains("pkb_browser=CHROME_HEADLESS"));
            assertTrue(output.stdout().contains("pkb_parallel=auto"));
            assertFalse(output.stdout().contains("pkb_runvars="));
            assertFalse(output.stdout().contains("Recommended complete diagnostic Discover"));
            assertTrue(output.stdout().contains("NEXT: run discover"));
            assertTrue(output.stdout().contains("Dry-run resolve"));
            assertTrue(output.stdout().contains("launcher JVM"));
            assertTrue(output.stdout().contains("not the Discover worker"));
            assertTrue(output.stdout().contains("Do not treat resolve-runvars as the environment Discover will use"));
            assertTrue(output.stdout().contains("run record after Discover"));
            assertFalse(output.stdout().contains("pkb_runvarssealed"));
            assertTrue(output.stdout().contains("pkb_overriderunvars") || output.stdout().contains("sealed="));
            assertTrue(output.stdout().contains("provenance="));
            assertFalse(output.stdout().contains("MUST"));
            assertTrue(output.stdout().contains("pkb_parallel=80"));
        } finally {
            restoreProperty("pkb_parallel", previousParallel);
            restoreProperty("pkb_runvars.pkb_parallel", previousRunVarsParallel);
        }
    }

    @Test
    void resolveRunVarsPrintsDryRunWithoutStartingTests() throws Exception {
        Path resources = tempDir.resolve("src/test/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("pickleball.properties"), "pkb_browser=chrome\n");

        Output output = run("resolve-runvars", tempDir.toString());

        assertEquals(0, output.exitCode());
        assertTrue(output.stdout().contains("Dry-run resolve"));
        assertTrue(output.stdout().contains("launcher JVM"));
        assertTrue(output.stdout().contains("not the Discover worker"));
        assertTrue(output.stdout().contains("Do not treat resolve-runvars as the environment Discover will use"));
        assertTrue(output.stdout().contains("run record after Discover"));
        assertFalse(output.stdout().contains("pkb_runvarssealed"));
        assertTrue(output.stdout().contains("sealed="));
        assertTrue(output.stdout().contains("pkb_overriderunvars"));
        assertTrue(output.stdout().contains("provenance="));
        assertFalse(output.stdout().contains("NEXT: run discover"));
    }

    @Test
    void agentGuideSaysResolveRunVarsIsNotTheDiscoverEnvironment() throws Exception {
        String line = "Do not treat resolve-runvars as the environment Discover will use.";
        for (String path : List.of(
                "docs/consumer-agent-guide.md",
                "src/main/resources/META-INF/pickleball/guidance/AGENT-GUIDE.md",
                "src/main/resources/META-INF/pickleball/guidance/docs/consumer-agent-guide.md"
        )) {
            String text = Files.readString(Path.of(path));
            assertTrue(text.contains(line), path);
            assertTrue(text.contains(
                    "The Workbench GUI is only a lightweight head over state and controls that already exist under the hood."
            ), path);
            assertFalse(text.contains("pkb_runvarssealed"), path);
            assertTrue(text.contains("pkb_overriderunvars"), path);
            assertTrue(text.contains("Never supply `pkb_run_profile` as input")
                    || text.contains("Never supply pkb_run_profile as input"), path);
        }
    }

    @Test
    void exportGuidanceUsesJdkLocalStore() throws Exception {
        Path outputDir = tempDir.resolve("guidance");
        Output output = run("export-guidance", outputDir.toString());
        assertEquals(0, output.exitCode());
        assertTrue(Files.isRegularFile(outputDir.resolve("AGENT-GUIDE.md")));
        assertTrue(output.stdout().contains("NEXT: follow AGENT-GUIDE"));
        assertTrue(output.stdout().contains("Workbench discover"));
    }

    @Test
    void discoverWrapsMavenAndRecordsSnapshot() throws Exception {
        writeProjectWrapper();
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
        assertTrue(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_workspace=")));
        assertTrue(captured.getFirst().contains("-Dpkb_tags=@smoke"));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_reportretention=")));
        assertTrue(captured.getFirst().contains("-Dpkb_run_purpose=workbench-discover"));
        LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.read(tempDir);
        assertTrue(snapshot.hasRunVars());
        assertTrue(snapshot.runProfile().contains("pkb_browser=CHROME_HEADLESS"));
    }

    @Test
    void discoverNextIsConfirmNotIsolateMavenExec() throws Exception {
        writeProjectWrapper();
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                System.err,
                (project, command, out, err) -> {
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-next",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=4, pkb_reportingmode=diagnostic, pkb_tags=@smoke",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );

        assertEquals(1, exit);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("status=EMPTY"));
        assertFalse(text.contains("status=PASSED"));
        assertTrue(text.contains("NEXT: confirm"));
        assertTrue(text.contains("isolate"));
        assertTrue(text.contains("execute-step"));
        assertTrue(text.contains("open-scenario"));
        assertTrue(text.contains("--example"));
        assertTrue(text.contains("Do not open the GUI for your own testing"));
        assertTrue(text.contains("While testing for yourself, stay headless"));
        assertFalse(text.contains("Do not start the GUI"));
        assertFalse(text.contains("only controls"));
    }

    @Test
    void discoverRetentionAllWritesAll() throws Exception {
        writeProjectWrapper();
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        List<List<String>> captured = new ArrayList<>();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke", "--retention=all"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                System.err,
                (project, command, out, err) -> {
                    captured.add(command);
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-all",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_parallel=4, pkb_reportingmode=diagnostic, pkb_reportretention=all, pkb_tags=@smoke",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );

        assertEquals(1, exit);
        String printed = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("status=EMPTY"));
        assertFalse(printed.contains("status=PASSED"));
        assertTrue(printed.contains("pkb_reportretention=all"));
        assertFalse(printed.contains("pkb_reportretention=failed"));
        assertTrue(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_workspace=")));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_reportretention=")));
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

    @Test
    void shortLogNoteInboxAndFinishUseTheProjectBoard() throws Exception {
        Output note = run("note", tempDir.toString(), "--agent=agent-board", "--text=looking at row 2");
        assertEquals(0, note.exitCode(), note.stderr());
        Output log = run("short-log", tempDir.toString());
        assertEquals(0, log.exitCode(), log.stderr());
        assertTrue(log.stdout().contains("agent-board"));
        assertTrue(log.stdout().contains("looking at row 2"));

        Output write = run(
                "inbox", tempDir.toString(), "--write", "--to=agent-other",
                "--from=agent-board", "--text=window is taken"
        );
        assertEquals(0, write.exitCode(), write.stderr());
        Output hidden = run("inbox", tempDir.toString(), "--list", "--agent=agent-board");
        assertEquals(0, hidden.exitCode(), hidden.stderr());
        assertTrue(hidden.stdout().contains("(no inbox notes)"));
        Output listed = run("inbox", tempDir.toString(), "--list", "--agent=agent-other");
        assertTrue(listed.stdout().contains("window is taken"));
        Output taken = run("inbox", tempDir.toString(), "--take", "--agent=agent-other");
        assertTrue(taken.stdout().contains("window is taken"));
        Output empty = run("inbox", tempDir.toString(), "--list", "--agent=agent-other");
        assertTrue(empty.stdout().contains("(no inbox notes)"));

        Output discover = run(
                "finish", tempDir.toString(), "--run-id=missing-run", "--learned=no such run"
        );
        assertEquals(1, discover.exitCode());

        AgentCoordination.begin(tempDir, AgentCoordination.Request.of(
                "run-finish", "agent-board", null, null, null, null, "discover"
        ));
        Output finished = run(
                "finish", tempDir.toString(), "--run-id=run-finish", "--learned=the empty row fails"
        );
        assertEquals(0, finished.exitCode(), finished.stderr());
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-finish").resolve("record.json")
        );
        assertTrue(record.contains("the empty row fails"));
        assertTrue(record.contains("STOPPED"));
        String board = Files.readString(AgentCoordination.logFile(tempDir));
        assertTrue(board.contains("\tstart\t"));
        assertTrue(board.contains("\tstop\t"));
        assertTrue(board.contains("record.json"));
        assertTrue(Files.isDirectory(AgentCoordination.runDirectory(tempDir, "run-finish")));
    }

    @Test
    void boardCommandsDoNotConsumeAPostAndSweepOnlyExpiredRows() throws Exception {
        Output presence = run("presence", tempDir.toString(), "--touch", "--agent=agent-board", "--run-id=run-board");
        assertEquals(0, presence.exitCode(), presence.stderr());
        assertTrue(Files.isRegularFile(tempDir.resolve(".pickleball/presence/agent-board.json")));

        Output post = run(
                "post", tempDir.toString(), "--write", "--to=all", "--from=agent-board",
                "--text=I have the window", "--ttl=1h"
        );
        assertEquals(0, post.exitCode(), post.stderr());
        Output listed = run("post", tempDir.toString(), "--list");
        assertTrue(listed.stdout().contains("I have the window"));
        Output taken = run("inbox", tempDir.toString(), "--take", "--agent=agent-board");
        assertTrue(taken.stdout().contains("(no inbox notes)"));
        assertTrue(run("post", tempDir.toString(), "--list").stdout().contains("I have the window"));

        Output history = run("history", tempDir.toString(), "--append", "--text=2.1.14 files fix branch commit merge run-board");
        assertEquals(0, history.exitCode(), history.stderr());
        assertTrue(run("history", tempDir.toString(), "--tail").stdout().contains("run-board"));

        Output sweep = run("gc-runs", tempDir.toString());
        assertEquals(0, sweep.exitCode(), sweep.stderr());
        assertTrue(Files.isRegularFile(tempDir.resolve(".pickleball/presence/agent-board.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve(".pickleball/history.log")));
    }

    @Test
    void discoverDoesNotCopyConfigsIntoTheRunDirectory() throws Exception {
        writeProjectWrapper();
        Path resources = tempDir.resolve("src/test/resources");
        Path qa = resources.resolve("qa-configs");
        Files.createDirectories(qa);
        Path marker = qa.resolve("MARKER.yaml");
        String markerText = "marker: qa-only\n";
        Files.writeString(marker, markerText, StandardCharsets.UTF_8);
        Files.writeString(
                resources.resolve("pickleball.properties"),
                "pkb_configpath=classpath:qa-configs\n",
                StandardCharsets.UTF_8
        );

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        String resolved = "classpath:qa-configs";
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--run-id=run-qa", "--agent=agent-qa"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    assertTrue(command.stream().anyMatch(item -> item.equals("-Dpkb_run_id=run-qa")));
                    assertTrue(command.stream().anyMatch(item -> item.equals("-Dpkb_agent_id=agent-qa")));
                    assertFalse(command.stream().anyMatch(item -> item.startsWith("-Dpkb_configpath=")));
                    Path config = AgentCoordination.runDirectory(project, "run-qa").resolve("config");
                    assertFalse(Files.isRegularFile(config.resolve("CHROME_HEADLESS.yaml")));
                    assertFalse(Files.isRegularFile(config.resolve("MARKER.yaml")));
                    try {
                        Files.createDirectories(config);
                        Files.writeString(
                                config.resolve("CHROME_HEADLESS.yaml"),
                                "bundled: only\n",
                                StandardCharsets.UTF_8
                        );
                        ClassLoader previous = Thread.currentThread().getContextClassLoader();
                        try (URLClassLoader loader = new URLClassLoader(
                                new URL[]{resources.toUri().toURL()},
                                previous
                        )) {
                            Thread.currentThread().setContextClassLoader(loader);
                            System.setProperty(PKB_props.PKB_RUN_ID, "run-qa");
                            System.setProperty(PKB_props.PKB_AGENT_ID, "agent-qa");
                            System.clearProperty("pkb_configpath");
                            AgentCoordination.Run adopted = AgentCoordination.openConsumerRun(
                                    project,
                                    new StringBuilder()
                            );
                            assertEquals("run-qa", adopted.runId());
                            String recorded = System.getProperty("pkb_configpath");
                            assertTrue(recorded == null || recorded.equals(resolved), () -> String.valueOf(recorded));
                            assertFalse(adopted.configDirectory().toString().equals(recorded));
                        } finally {
                            Thread.currentThread().setContextClassLoader(previous);
                            System.clearProperty(PKB_props.PKB_RUN_ID);
                            System.clearProperty(PKB_props.PKB_AGENT_ID);
                            System.clearProperty("pkb_configpath");
                        }
                        Path catalog = project.resolve("reports/diagnostic-runs");
                        Files.createDirectories(catalog);
                        Files.writeString(catalog.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-qa",
                                      "runProfile": "pkb_configpath=classpath:qa-configs, pkb_browser=CHROME_HEADLESS",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure.getMessage(), failure);
                    }
                    return 0;
                }
        );

        assertEquals(0, exit, () -> stderr.toString(StandardCharsets.UTF_8) + stdout.toString(StandardCharsets.UTF_8));
        Path copied = AgentCoordination.runDirectory(tempDir, "run-qa").resolve("config").resolve("MARKER.yaml");
        assertFalse(Files.isRegularFile(copied));
        assertEquals(markerText, Files.readString(marker));
    }

    @Test
    void discoverHandsMavenOnlyTheMatchingFeatureAndLeavesListPicklesAlone() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("needle.feature"), """
                @needle
                Feature: Needle
                  Scenario: The one
                    Given one
                """);
        for (int i = 0; i < 40; i++) {
            Files.writeString(features.resolve("skip-" + i + ".feature"), """
                    Feature: Skip %d
                      Scenario: Untagged %d
                        Given skip
                    """.formatted(i, i));
        }
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        List<List<String>> captured = new ArrayList<>();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@needle"},
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
                                      "runId": "run-needle",
                                      "runProfile": "pkb_tags=@needle",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(0, exit);
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_features=")));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        String top = topLevelArgument(captured.getFirst());
        assertTrue(top.contains("needle.feature"), top);
        assertFalse(top.contains("skip-0.feature"), top);
        assertFalse(top.contains("skip-39.feature"), top);

        String modular = Files.readString(Path.of("src/main/java/tools/dscode/coredefinitions/ModularScenarios.java"));
        assertTrue(modular.contains("CucumberScanUtil.listPickles(scanOptions)"));
        String scan = Files.readString(Path.of("src/main/java/io/cucumber/core/runner/modularexecutions/CucumberScanUtil.java"));
        assertTrue(scan.contains("public static List<Pickle> listPickles"));
    }

    @Test
    void discoverLaunchesOnlyTheTwoMatchingFeatureFiles() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        for (String name : List.of("keep-a.feature", "keep-b.feature")) {
            Files.writeString(features.resolve(name), """
                    @keep
                    Feature: %s
                      Scenario: The one
                        Given one
                    """.formatted(name));
        }
        Files.writeString(features.resolve("skip.feature"), """
                Feature: Skip
                  Scenario: Untagged
                    Given skip
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        List<List<String>> captured = new ArrayList<>();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@keep"},
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
                                      "runId": "run-keep",
                                      "runProfile": "pkb_tags=@keep",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(0, exit);
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_features=")));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        String top = topLevelArgument(captured.getFirst());
        assertTrue(top.contains("keep-a.feature"), top);
        assertTrue(top.contains("keep-b.feature"), top);
        assertFalse(top.contains("skip.feature"), top);
        assertEquals(2, top.substring(top.indexOf('=') + 1).split(",").length, top);
    }

    @Test
    void narrowedTopLevelLeavesConfiguredFeaturesForANestedLookup() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Path spaced = features.resolve("Business Exception");
        Files.createDirectories(spaced);
        Path caller = features.resolve("caller.feature");
        Path callee = spaced.resolve("callee.feature");
        Files.writeString(caller, """
                @caller
                Feature: Caller
                  Scenario: The caller
                    Given one
                """);
        Files.writeString(callee, """
                @callee
                Feature: Callee
                  Scenario: The callee
                    Given two
                """);
        Path windowsNamed = features.resolve("windows callee.feature");
        Files.writeString(windowsNamed, """
                @windows-callee
                Feature: Windows callee
                  Scenario: The windows callee
                    Given three
                """);
        CucumberScanUtil.clearCache();
        String configured = features.toAbsolutePath().normalize().toString().replace('\\', '/');
        List<Pickle> nested = CucumberScanUtil.listPickles(Map.of(
                PKB_props.PKB_FEATURES, configured,
                PKB_props.PKB_TAGS, "@callee"
        ));
        assertEquals(1, nested.size());
        assertEquals("The callee", nested.getFirst().getName());
        List<Pickle> windowsNested = CucumberScanUtil.listPickles(Map.of(
                PKB_props.PKB_FEATURES, configured,
                PKB_props.PKB_TAGS, "@windows-callee"
        ));
        assertEquals("The windows callee", windowsNested.getFirst().getName());
        IllegalArgumentException narrowedAway = assertThrows(
                IllegalArgumentException.class,
                () -> CucumberScanUtil.listPickles(Map.of(
                        PKB_props.PKB_FEATURES, caller.toAbsolutePath().normalize().toString(),
                        PKB_props.PKB_TAGS, "@callee"
                ))
        );
        assertTrue(narrowedAway.getMessage().toLowerCase(Locale.ROOT).contains("no scenarios"), narrowedAway.getMessage());

        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        List<List<String>> captured = new ArrayList<>();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@caller"},
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
                                      "runId": "run-nested",
                                      "runProfile": "pkb_tags=@caller",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(0, exit);
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_features=")));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        String top = topLevelArgument(captured.getFirst());
        assertTrue(top.contains("caller.feature"), top);
        assertFalse(top.contains("callee.feature"), top);
        assertTrue(top.contains("wd-file:"), top);
        assertFalse(top.contains("\\"), top);

        String windowsTop = "wd-file:C:/proj/Business Exception/callee.feature";
        Map<String, String> suite = Map.of(
                PKB_props.PKB_FEATURES, configured,
                "cucumber.features", configured
        );
        Map<String, String> overlay = DynamicSuiteEngine.overlayTopLevelFeatures(suite, windowsTop);
        assertEquals(configured, overlay.get(PKB_props.PKB_FEATURES));
        assertEquals(windowsTop, overlay.get("cucumber.features"));
        assertEquals(configured, suite.get(PKB_props.PKB_FEATURES));

        List<List<String>> fullScan = new ArrayList<>();
        int skipped = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@caller", "--full-scan"},
                System.out,
                System.err,
                (project, command, out, err) -> {
                    fullScan.add(command);
                    return 0;
                }
        );
        assertEquals(0, skipped);
        assertTrue(fullScan.getFirst().stream().noneMatch(item -> item.startsWith("-D" + DynamicSuiteEngine.TOP_LEVEL_FEATURES_PROPERTY + "=")));
    }

    private static String topLevelArgument(List<String> command) {
        String prefix = "-D" + DynamicSuiteEngine.TOP_LEVEL_FEATURES_PROPERTY + "=";
        return command.stream().filter(item -> item.startsWith(prefix)).findFirst().orElseThrow();
    }

    @Test
    void discoverFailureIgnoreStillReportsFailedFromTheRunIndex() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("boom.feature"), """
                @boom
                Feature: Boom
                  Scenario: It fails
                    Given no
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@boom", "--run-id=run-fail"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Path index = AgentCoordination.runDirectory(project, "run-fail")
                                .resolve("diagnostic").resolve("run-fail").resolve("run-index.json");
                        Files.createDirectories(index.getParent());
                        Files.writeString(index, """
                                {
                                  "schemaVersion": 1,
                                  "runId": "run-fail",
                                  "outcome": "FAILED",
                                  "counts": { "total": 1, "passed": 0, "failed": 1, "unknown": 0 }
                                }
                                """);
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-old",
                                      "outcome": "PASSED",
                                      "runProfile": "pkb_tags=@old",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("summary status=FAILED"), text);
        assertFalse(text.contains("summary status=PASSED"), text);
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-fail").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("\"status\" : \"FAILED\""), record);
        assertFalse(record.contains("\"status\" : \"PASSED\""), record);
    }

    @Test
    void discoverFailureIgnoreUsesSurefireWhenTheRunIndexIsMissing() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("boom.feature"), """
                @boom
                Feature: Boom
                  Scenario: It fails
                    Given no
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        Path stale = tempDir.resolve("target/surefire-reports/TEST-old.xml");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "<testsuite name=\"old\" tests=\"1\" failures=\"3\" errors=\"0\"></testsuite>\n");
        Files.setLastModifiedTime(stale, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(120)));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@boom", "--run-id=run-surefire"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Path report = project.resolve("target/surefire-reports/TEST-boom.xml");
                        Files.createDirectories(report.getParent());
                        Files.writeString(report, """
                                <testsuite name="boom" tests="1" failures="1" errors="0"></testsuite>
                                """);
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-surefire",
                                      "outcome": "PASSED",
                                      "runProfile": "pkb_tags=@boom",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("summary status=FAILED"));
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-surefire").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("\"status\" : \"FAILED\""), record);
    }

    @Test
    void discoverUpgradesAWorkerPassedRecordWhenTheFlatIndexHasAnError() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("boom.feature"), """
                @boom
                Feature: Boom
                  Scenario: It errors
                    Given no
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@boom", "--run-id=run-error"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        AgentCoordination.finish(project, "run-error", null, "PASSED");
                        Path index = AgentCoordination.runDirectory(project, "run-error")
                                .resolve("diagnostic").resolve("run-index.json");
                        Files.createDirectories(index.getParent());
                        Files.writeString(index, """
                                {
                                  "runId": "run-error",
                                  "outcome": "ERROR",
                                  "counts": { "total": 1, "passed": 0, "failed": 0, "errors": 1 },
                                  "scenarios": [ { "name": "It errors", "outcome": "ERROR" } ]
                                }
                                """);
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-error",
                                      "outcome": "ERROR",
                                      "runProfile": "pkb_tags=@boom",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("summary status=FAILED"), text);
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-error").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("\"status\" : \"FAILED\""), record);
        assertTrue(record.contains("\"failureCount\" : 1"), record);
        assertFalse(record.contains("\"status\" : \"PASSED\""), record);
    }

    @Test
    void discoverFailureIgnoreUsesASurefireErrorWhenTheRunIndexIsMissing() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("boom.feature"), """
                @boom
                Feature: Boom
                  Scenario: It errors
                    Given no
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@boom", "--run-id=run-xml-error"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Path report = project.resolve("target/surefire-reports/TEST-error.xml");
                        Files.createDirectories(report.getParent());
                        Files.writeString(report, """
                                <testsuite name="boom" tests="1" failures="0" errors="1"></testsuite>
                                """);
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-xml-error",
                                      "outcome": "PASSED",
                                      "runProfile": "pkb_tags=@boom",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-xml-error").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("\"status\" : \"FAILED\""), record);
        assertTrue(record.contains("\"failureCount\" : 1"), record);
    }

    @Test
    void secondDiscoverUsesItsOwnRunIndexNotThePreviousCatalog() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("alpha.feature"), """
                @alpha
                Feature: Alpha
                  Scenario: Alpha one
                    Given a
                """);
        Files.writeString(features.resolve("beta.feature"), """
                @beta
                Feature: Beta
                  Scenario: Beta one
                    Given b
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        for (String name : List.of("old-1", "old-2", "old-3", "old-4")) {
            writeScenarioEvents(catalogDir.resolve("run-old").resolve("scenarios").resolve(name));
        }
        String alphaCatalog = """
                {
                  "schemaVersion": 1,
                  "runs": [
                    {
                      "runId": "run-alpha",
                      "outcome": "PASSED",
                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_tags=@alpha",
                      "counts": { "total": 4, "passed": 4, "failed": 0 },
                      "lineage": { "runPurpose": "workbench-discover" }
                    }
                  ]
                }
                """;
        ByteArrayOutputStream firstOut = new ByteArrayOutputStream();
        int first = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@alpha", "--run-id=run-alpha"},
                new PrintStream(firstOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Files.createDirectories(catalogDir);
                        Files.writeString(catalogDir.resolve("run-catalog.json"), alphaCatalog);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(0, first);
        assertTrue(firstOut.toString(StandardCharsets.UTF_8).contains("pkb_tags=@alpha"));

        for (String name : List.of("shared-1", "shared-2", "shared-3")) {
            writeScenarioEvents(catalogDir.resolve("run-beta").resolve("scenarios").resolve(name));
        }
        ByteArrayOutputStream secondOut = new ByteArrayOutputStream();
        int second = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@beta", "--run-id=run-beta"},
                new PrintStream(secondOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), alphaCatalog);
                        Path index = AgentCoordination.runDirectory(project, "run-beta")
                                .resolve("diagnostic").resolve("run-beta").resolve("run-index.json");
                        Files.createDirectories(index.getParent());
                        Files.writeString(index, """
                                {
                                  "schemaVersion": 1,
                                  "runId": "run-beta",
                                  "outcome": "PASSED",
                                  "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_tags=@beta",
                                  "counts": { "total": 1, "passed": 1, "failed": 0 },
                                  "lineage": { "runPurpose": "workbench-discover" }
                                }
                                """);
                        writeScenarioEvents(index.getParent().resolve("scenarios").resolve("beta-only"));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(0, second);
        String text = secondOut.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("summary status=PASSED scenarios=1 steps=1"), text);
        int retained = text.indexOf("retained pkb_run_profile:");
        assertTrue(retained >= 0, text);
        String retainedLine = text.substring(retained);
        assertTrue(retainedLine.contains("pkb_tags=@beta"), retainedLine);
        assertFalse(retainedLine.contains("@alpha"), retainedLine);
        LastDiscoverSnapshot.Snapshot snapshot = LastDiscoverSnapshot.read(tempDir);
        assertEquals("run-beta", snapshot.runId());
        assertTrue(snapshot.runProfile().contains("pkb_tags=@beta"), snapshot.runProfile());
        assertFalse(snapshot.runProfile().contains("@alpha"), snapshot.runProfile());
    }

    private static void writeScenarioEvents(Path scenarioDir) throws Exception {
        Files.createDirectories(scenarioDir);
        Files.writeString(scenarioDir.resolve("events.jsonl"), """
                {"type":"scenario_start","identity":{"scenarioName":"Counted"}}
                {"type":"step","keyword":"Given","text":"one","status":"PASSED"}
                {"type":"scenario_end","outcome":"PASSED"}
                """);
    }

    @Test
    void planResolvesExampleRowsAndWarnsWhenEmptyWithoutMaven() throws Exception {
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("search.feature"), """
                Feature: Search
                  Scenario Outline: Find term
                    When search <term>
                    Examples:
                      | term |
                      | ada  |
                      | bob  |
                """);
        boolean[] mavenCalled = {false};
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--plan", "--name=Find term", "--example=2"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    mavenCalled[0] = true;
                    return 0;
                }
        );
        assertEquals(0, exit);
        assertFalse(mavenCalled[0]);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("PLAN 1 scenario(s)"));
        assertTrue(text.contains("term=bob"));
        assertTrue(text.contains("search.feature:"));

        ByteArrayOutputStream emptyErr = new ByteArrayOutputStream();
        int empty = WorkbenchAgentCommands.run(
                new String[]{"plan", tempDir.toString(), "--tags=@missing"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(emptyErr, true, StandardCharsets.UTF_8)
        );
        assertEquals(1, empty);
        assertTrue(emptyErr.toString(StandardCharsets.UTF_8).contains("WARNING: no scenarios matched"));
    }

    @Test
    void pathLinePlanDoesNotBuildOrOpenABrowser() throws Exception {
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("login.feature"), """
                Feature: Sign in
                  Scenario: Valid password
                    Given a user
                  Scenario: Locked account
                    Given a lock
                """);
        Output output = run(
                "plan",
                tempDir.toString(),
                "--select=src/test/resources/features/login.feature:4"
        );
        assertEquals(0, output.exitCode(), output.stderr());
        assertTrue(output.stdout().contains("Locked account"));
        assertFalse(output.stdout().contains("Valid password"));
        assertFalse(output.stdout().contains("mvnw"));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void discoverRunTimeoutRecordsTimeoutAndDoesNotReportPassed() throws Exception {
        writeProjectWrapper();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--run-timeout=1", "--run-id=slow-run"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("TIMEOUT"));
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "slow-run").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("TIMEOUT"));
        assertFalse(record.contains("PASSED"));
    }

    @Test
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    void discoverRunTimeoutKillsTheLauncherProcessTree() throws Exception {
        Path classes = tempDir.resolve("sleeper");
        Files.createDirectories(classes);
        Path source = classes.resolve("TreeSleeper.java");
        Files.writeString(source, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class TreeSleeper {
                    public static void main(String[] args) throws Exception {
                        if (args.length > 0 && "leaf".equals(args[0])) {
                            Thread.sleep(Long.parseLong(args[1]));
                            return;
                        }
                        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
                        Process leaf = new ProcessBuilder(
                                javaBin, "-cp", args[0], "TreeSleeper", "leaf", args[2]
                        ).start();
                        Files.writeString(Path.of(args[1]), ProcessHandle.current().pid() + "\\n" + leaf.pid() + "\\n");
                        Thread.sleep(Long.parseLong(args[2]));
                    }
                }
                """);
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process compile = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
                source.toString()
        ).inheritIO().start();
        assertEquals(0, compile.waitFor(), "sleeper did not compile");
        new ProcessBuilder(javaBin, "-cp", classes.toString(), "TreeSleeper", classes.toString(),
                tempDir.resolve("warmup.txt").toString(), "50").inheritIO().start().waitFor();

        Path pids = tempDir.resolve("pids.txt");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = tempDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        String body = windows
                ? "@echo off\r\n\"" + javaBin + "\" -cp \"" + classes + "\" TreeSleeper \"" + classes + "\" \"" + pids + "\" 120000\r\n"
                : "#!/bin/sh\nexec \"" + javaBin + "\" -cp \"" + classes + "\" TreeSleeper \"" + classes + "\" \"" + pids + "\" 120000\n";
        Files.writeString(script, body);
        script.toFile().setExecutable(true);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--run-timeout=1", "--run-id=tree-timeout"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                ConsumerMavenTestRunner::run
        );
        assertEquals(1, exit);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("TIMEOUT"), stderr.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(pids), "launcher did not publish the process tree");
        List<Long> tree = Files.readString(pids).lines().map(String::trim).filter(line -> !line.isEmpty()).map(Long::parseLong).toList();
        assertEquals(2, tree.size(), tree.toString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && tree.stream().anyMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))) {
            Thread.sleep(50);
        }
        assertTrue(tree.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)), tree.toString());
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "tree-timeout").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("TIMEOUT"), record);
        assertFalse(record.contains("PASSED"), record);
    }

    @Test
    void zeroSelectedDiscoverAndConfirmAreEmptyAndDoNotReportPassed() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("other.feature"), """
                @other
                Feature: Other
                  Scenario: Somewhere else
                    Given elsewhere
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{
                        "discover", tempDir.toString(),
                        "--run-id=run-empty",
                        "--tags=@missing",
                        "--name=Nowhere",
                        "--example=4"
                },
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Files.createDirectories(project.resolve("reports/diagnostic-runs"));
                        Files.writeString(project.resolve("reports/diagnostic-runs/run-catalog.json"), """
                                {
                                  "runs": [
                                    {
                                      "runId": "run-empty",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_tags=@missing",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(1, exit);
        String text = stdout.toString(StandardCharsets.UTF_8);
        String errors = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("status=EMPTY"), text);
        assertTrue(text.contains("tags=@missing"), text);
        assertTrue(text.contains("name=Nowhere"), text);
        assertTrue(text.contains("example=4"), text);
        assertTrue(text.contains("browser=CHROME_HEADLESS") || errors.contains("browser=CHROME_HEADLESS"), text + errors);
        assertTrue(text.contains("tags=@missing"));
        assertTrue(text.contains("name=Nowhere"));
        assertFalse(text.contains("status=PASSED"));
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-empty").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("EMPTY"), record);
        assertFalse(record.contains("PASSED"), record);

        Path source = Workspace.directory(tempDir, "run-empty");
        Map<String, String> seeded = new LinkedHashMap<>(Workspace.readRunVars(source));
        seeded.put(PKB_props.PKB_BROWSER, "FIREFOX");
        Workspace.writeRunVars(source, seeded);
        LastDiscoverSnapshot.write(tempDir, "run-empty", tempDir.resolve("reports/diagnostic-runs/run-catalog.json"),
                "pkb_browser=EDGE, pkb_tags=@missing");
        ByteArrayOutputStream confirmOut = new ByteArrayOutputStream();
        int confirm = WorkbenchAgentCommands.run(
                new String[]{"confirm", tempDir.toString(), "--run-id=run-empty-confirm", "--example=9"},
                new PrintStream(confirmOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> 0
        );
        assertEquals(1, confirm);
        String confirmed = confirmOut.toString(StandardCharsets.UTF_8);
        assertTrue(confirmed.contains("status=EMPTY"), confirmed);
        assertTrue(confirmed.contains("browser=FIREFOX"), confirmed);
        assertTrue(confirmed.contains("example=9"), confirmed);
        assertFalse(confirmed.contains("status=PASSED"));
        String confirmRecord = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-empty-confirm").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(confirmRecord.contains("EMPTY"), confirmRecord);
        assertFalse(confirmRecord.contains("PASSED"), confirmRecord);
    }

    @Test
    void discoverSnapshotStaysWithItsAgentAndRun() throws Exception {
        writeProjectWrapper();
        int first = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--agent=agent-a", "--run-id=run-a", "--tags=@alpha"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Path catalog = project.resolve("reports/diagnostic-runs");
                        Files.createDirectories(catalog);
                        Files.writeString(catalog.resolve("run-catalog.json"), """
                                {
                                  "runs": [
                                    {
                                      "runId": "run-b",
                                      "runProfile": "pkb_browser=FIREFOX, pkb_tags=@beta",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    },
                                    {
                                      "runId": "run-a",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_tags=@alpha",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(1, first);
        LastDiscoverSnapshot.Snapshot agentA = LastDiscoverSnapshot.readScoped(tempDir, "agent-a", "run-a");
        assertTrue(agentA.runProfile().contains("pkb_tags=@alpha"), agentA.runProfile());
        assertFalse(agentA.runProfile().contains("@beta"), agentA.runProfile());

        ByteArrayOutputStream secondOut = new ByteArrayOutputStream();
        WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--agent=agent-b", "--run-id=run-b", "--tags=@beta"},
                new PrintStream(secondOut, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Files.writeString(project.resolve("reports/diagnostic-runs/run-catalog.json"), """
                                {
                                  "runs": [
                                    {
                                      "runId": "run-newer",
                                      "runProfile": "pkb_browser=FIREFOX, pkb_tags=@newer",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    },
                                    {
                                      "runId": "run-b",
                                      "runProfile": "pkb_browser=FIREFOX, pkb_tags=@beta",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        String secondText = secondOut.toString(StandardCharsets.UTF_8);
        int retained = secondText.indexOf("retained pkb_run_profile:");
        assertTrue(retained >= 0, secondText);
        String retainedLine = secondText.substring(retained);
        assertTrue(retainedLine.contains("pkb_tags=@beta"), retainedLine);
        assertFalse(retainedLine.contains("@newer"), retainedLine);
        assertTrue(LastDiscoverSnapshot.readScoped(tempDir, "agent-a", null).runProfile().contains("@alpha"));
        assertTrue(LastDiscoverSnapshot.read(tempDir).runProfile().contains("@beta"));

        List<List<String>> captured = new ArrayList<>();
        WorkbenchAgentCommands.run(
                new String[]{"confirm", tempDir.toString(), "--agent=agent-a", "--run-id=run-a-confirm"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    captured.add(command);
                    return 0;
                }
        );
        String runVars = captured.getFirst().stream().filter(item -> item.startsWith("-Dpkb_tags=")).findFirst().orElse("");
        assertEquals("-Dpkb_tags=@alpha", runVars);
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.contains("@beta")), runVars);
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.contains("@newer")), runVars);
        assertFalse(runVars.contains("@newer"), runVars);
    }

    @Test
    void quotingErrorFailsBeforeMavenAndEchoesTheParsedExpression() throws Exception {
        boolean[] maven = {false};
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@a and \"oops"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    maven[0] = true;
                    return 0;
                }
        );
        assertEquals(2, exit);
        assertFalse(maven[0]);
        assertTrue(stderr.toString(StandardCharsets.UTF_8).contains("quoting"));

        writeProjectWrapper();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke", "--name=Find term"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> 1
        );
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("tags=@smoke"), text);
        assertTrue(text.contains("name=Find term"), text);
    }

    @Test
    void emptySelectionDoesNotChangeDiagnosticReporter() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/tools/dscode/common/reporting/diagnostic/DiagnosticReporter.java"
        ));
        assertTrue(source.contains("return \"NO_TESTS\""));
        assertFalse(source.contains("\"EMPTY\""));
        assertFalse(source.contains("\"TIMEOUT\""));
        assertFalse(source.contains("\"STOPPED\""));
    }

    @Test
    void discoverSavesScenarioConsoleUnderTheRunFolder() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("one.feature"), """
                Feature: One
                  Scenario: The one
                    Given one
                """);
        WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--run-id=run-console", "--name=The one"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    out.println("Scenario: The one");
                    out.println("console-from-the-worker");
                    try {
                        Files.createDirectories(project.resolve("reports/diagnostic-runs"));
                        Files.writeString(project.resolve("reports/diagnostic-runs/run-catalog.json"), """
                                {
                                  "runs": [
                                    {
                                      "runId": "run-console",
                                      "runProfile": "pkb_browser=CHROME_HEADLESS, pkb_name=The one",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        String console = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-console").resolve("console").resolve("The_one.txt")
        );
        assertTrue(console.contains("console-from-the-worker"), console);
        assertTrue(console.contains("Scenario: The one"), console);
    }

    @Test
    void wrappedIoExceptionIsVisibleAndEmptyRunsRecordWhy() throws Exception {
        writeProjectWrapper();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--run-id=run-io", "--tags=@missing"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    throw new IllegalStateException("wrapped", new java.io.IOException("disk full"));
                }
        );
        assertEquals(1, exit);
        String errors = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("IOException"), errors);
        assertTrue(errors.contains("disk full"), errors);

        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("other.feature"), """
                Feature: Other
                  Scenario: Elsewhere
                    Given elsewhere
                """);
        WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--run-id=run-why", "--tags=@missing"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> 0
        );
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-why").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("No scenarios matched"), record);
        assertTrue(record.contains("@missing"), record);
    }

    @Test
    void discoverRunVarSetsEnvironmentOnTheCommandRecordAndSummary() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("dev.feature"), """
                @dev
                Feature: Dev
                  Scenario: In dev
                    Given a
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        List<List<String>> captured = new ArrayList<>();
        int exit = WorkbenchAgentCommands.run(
                new String[]{
                        "discover", tempDir.toString(), "--tags=@dev", "--run-id=run-dev",
                        "--runvar", "pkb_environment=DEV", "--runvar=pkb_parallel=3"
                },
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    captured.add(command);
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {
                                  "schemaVersion": 1,
                                  "runs": [
                                    {
                                      "runId": "run-dev",
                                      "outcome": "PASSED",
                                      "runProfile": "pkb_environment=DEV, pkb_tags=@dev",
                                      "lineage": { "runPurpose": "workbench-discover" }
                                    }
                                  ]
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(0, exit);
        assertTrue(captured.getFirst().contains("-Dpkb_parallel=3"));
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-Dpkb_runvars=")));
        String workspaceArg = captured.getFirst().stream().filter(item -> item.startsWith("-Dpkb_workspace=")).findFirst().orElseThrow();
        Map<String, String> stored = Workspace.readRunVars(Path.of(workspaceArg.substring("-Dpkb_workspace=".length())));
        assertEquals("DEV", stored.get(PKB_props.PKB_ENVIRONMENT));
        assertEquals("auto", stored.get(PKB_props.PKB_PARALLEL));
        assertEquals("diagnostic", stored.get(PKB_props.PKB_REPORTING_MODE));
        String errors = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("WARNING: --runvar pkb_parallel replaces"), errors);
        String record = Files.readString(
                AgentCoordination.runDirectory(tempDir, "run-dev").resolve(AgentCoordination.RECORD_FILE)
        );
        assertTrue(record.contains("\"environment\" : \"DEV\""), record);
        assertTrue(record.contains("\"browser\""), record);
        String text = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("environment=DEV"), text);
        assertTrue(text.contains("browser="), text);
        assertTrue(text.contains("summary status="), text);
    }

    @Test
    void discoverRewritesXorForMavenAndWarnsWithTheEscapedForm() throws Exception {
        writeProjectWrapper();
        Path features = tempDir.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("tags.feature"), """
                @smoke
                Feature: Tags
                  Scenario: Smoke
                    Given a
                """);
        Path catalogDir = tempDir.resolve("reports/diagnostic-runs");
        Files.createDirectories(catalogDir);
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        List<List<String>> captured = new ArrayList<>();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke xor @wip", "--run-id=run-xor"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    captured.add(command);
                    try {
                        Files.writeString(catalogDir.resolve("run-catalog.json"), """
                                {"schemaVersion":1,"runs":[{"runId":"run-xor","outcome":"PASSED","runProfile":"pkb_tags=@smoke","lineage":{"runPurpose":"workbench-discover"}}]}
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 0;
                }
        );
        assertEquals(0, exit);
        String tags = captured.getFirst().stream().filter(item -> item.startsWith("-Dpkb_tags=")).findFirst().orElse("");
        tags = tags.substring("-Dpkb_tags=".length());
        io.cucumber.tagexpressions.TagExpressionParser.parse(tags);
        assertFalse(tags.toLowerCase().contains("xor"), tags);
        assertTrue(tags.contains("or"), tags);
        String errors = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(errors.contains("@smoke\\ xor\\ @wip"), errors);
        assertTrue(errors.contains("not xor"), errors);
        assertFalse(captured.getFirst().stream().anyMatch(item -> item.startsWith("-D" + DynamicSuiteEngine.TOP_LEVEL_FEATURES_PROPERTY + "=")));

        List<List<String>> escaped = new ArrayList<>();
        ByteArrayOutputStream escapedErr = new ByteArrayOutputStream();
        WorkbenchAgentCommands.run(
                new String[]{"discover", tempDir.toString(), "--tags=@smoke\\ xor\\ @wip", "--run-id=run-escaped"},
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(escapedErr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    escaped.add(command);
                    return 0;
                }
        );
        String escapedTags = escaped.getFirst().stream().filter(item -> item.startsWith("-Dpkb_tags=")).findFirst().orElse("");
        escapedTags = escapedTags.substring("-Dpkb_tags=".length());
        assertEquals("@smoke\\ xor\\ @wip", escapedTags);
        io.cucumber.tagexpressions.TagExpressionParser.parse(escapedTags);
    }

    @Test
    void workspaceShowEditAndSeedUseTheSameFiles() throws Exception {
        Output missing = run("workspace-show", tempDir.toString(), "--run-id=run-a");
        assertEquals(1, missing.exitCode());
        assertTrue(missing.stdout().contains("present=false"), missing.stdout());

        Output seeded = run("workspace-seed", tempDir.toString(), "--run-id=run-a");
        assertEquals(0, seeded.exitCode(), seeded.stderr());
        assertTrue(seeded.stdout().contains("present=true"), seeded.stdout());
        String fingerprint = seeded.stdout().lines()
                .filter(line -> line.startsWith("fingerprint="))
                .findFirst()
                .orElseThrow()
                .substring("fingerprint=".length());
        Path workspace = Workspace.directory(tempDir, "run-a");
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(workspace).get("pkb_browser"));
        assertEquals("auto", Workspace.readRunVars(workspace).get("pkb_parallel"));

        Output shown = run("workspace-show", tempDir.toString(), "--run-id=run-a");
        assertEquals(0, shown.exitCode(), shown.stderr());
        assertTrue(shown.stdout().contains("pkb_browser=CHROME_HEADLESS"), shown.stdout());
        assertTrue(shown.stdout().contains("fingerprint=" + fingerprint), shown.stdout());

        Output stale = run(
                "workspace-edit", tempDir.toString(), "--run-id=run-a",
                "--expect=not-the-fingerprint", "--runvar=pkb_browser=firefox"
        );
        assertEquals(3, stale.exitCode());
        assertTrue(stale.stderr().contains("changed on disk"), stale.stderr());
        assertEquals("CHROME_HEADLESS", Workspace.readRunVars(workspace).get("pkb_browser"));

        Output edited = run(
                "workspace-edit", tempDir.toString(), "--run-id=run-a",
                "--expect=" + fingerprint,
                "--runvar=pkb_browser=firefox",
                "--runvar=pkb_parallel=4",
                "--config=CHROME.yaml",
                "--text=args: --disable-gpu"
        );
        assertEquals(0, edited.exitCode(), edited.stderr());
        assertEquals("firefox", Workspace.readRunVars(workspace).get("pkb_browser"));
        assertEquals("4", Workspace.readRunVars(workspace).get("pkb_parallel"));
        assertTrue(Files.readString(workspace.resolve("configs").resolve("CHROME.yaml")).contains("--disable-gpu"));
        String projectConfigs = tempDir.resolve("src/test/resources/configs/CHROME.yaml").toString();
        assertFalse(Files.isRegularFile(Path.of(projectConfigs)));

        Output copied = run("workspace-seed", tempDir.toString(), "--run-id=run-b", "--from-run=run-a");
        assertEquals(0, copied.exitCode(), copied.stderr());
        assertEquals("firefox", Workspace.readRunVars(Workspace.directory(tempDir, "run-b")).get("pkb_browser"));
        assertEquals("firefox", Workspace.readRunVars(workspace).get("pkb_browser"));

        WorkspaceLaunch.testCapture = path -> {
            Map<String, String> values = new LinkedHashMap<>();
            for (String key : Workspace.CONTEXT_KEYS) values.put(key, key);
            values.put("pkb_browser", "edge");
            values.put("pkb_parallel", "auto");
            Workspace.writeRunVars(path, values);
        };
        Output reseeded = run("workspace-seed", tempDir.toString(), "--run-id=run-a", "--reseed");
        assertEquals(0, reseeded.exitCode(), reseeded.stderr());
        assertEquals("edge", Workspace.readRunVars(workspace).get("pkb_browser"));
        assertEquals("firefox", Workspace.readRunVars(Workspace.directory(tempDir, "run-b")).get("pkb_browser"));
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

    private void writeProjectWrapper() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = tempDir.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }

    private record Output(int exitCode, String stdout, String stderr) { }
}
