package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.AgentLog;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.PKB_props;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.cucumber.core.cli.Main;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for the 2.1.18 discover/launch report (B2, B3, B5, B7, S4). */
class WorkbenchLaunchCorrectnessTest {
    @TempDir
    Path temp;

    @Test
    void forkDeathBeforeAScenarioIsLaunchErrorAndNamesTheCause() throws Exception {
        writeFeature();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", temp.toString(), "--run-id=run-dead", "--tags=@smoke"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    out.println("# pkb-console smoke [ERROR] Please refer to the dump");
                    err.println("java.lang.IllegalStateException: wrapper");
                    err.println("Caused by: java.lang.IllegalStateException: fork died");
                    try {
                        Path index = AgentCoordination.runDirectory(project, "run-dead")
                                .resolve("diagnostic").resolve("run-index.json");
                        Files.createDirectories(index.getParent());
                        Files.writeString(index, """
                                {"runId":"run-dead","counts":{"total":4,"passed":0,"failed":0}}
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 1;
                }
        );
        assertEquals(1, exit);
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("summary status=LAUNCH_ERROR scenarios=0 steps=0"), out);
        assertFalse(out.contains("# pkb-console"), out);
        assertTrue(out.contains("console=.pickleball/runs/run-dead/console/"), out);
        assertTrue(err.contains("error: Caused by: java.lang.IllegalStateException: fork died"), err);
        assertTrue(err.contains("fix: Full output is in .pickleball/runs/run-dead/console/"), err);
        String saved = Files.readString(AgentCoordination.runDirectory(temp, "run-dead").resolve("console").resolve("The_one.txt"));
        assertTrue(saved.contains("fork died"), saved);
    }

    @Test
    void quietDiscoverOmitsTheSummaryAndStillWritesTheConsole() throws Exception {
        writeFeature();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", temp.toString(), "--quiet", "--run-id=run-quiet", "--tags=@smoke"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    out.println("# pkb-console smoke worker-line");
                    return 0;
                }
        );
        assertEquals(0, exit);
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains("# pkb-console"), out);
        assertFalse(out.contains("summary status="), out);
        assertFalse(out.contains("console="), out);
        String saved = Files.readString(AgentCoordination.runDirectory(temp, "run-quiet").resolve("console").resolve("The_one.txt"));
        assertTrue(saved.contains("worker-line"), saved);
    }

    @Test
    void confirmUsesAnExistingWorkspaceWhenNoDiscoverSnapshotExists() throws Exception {
        writeFeature();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(PKB_props.PKB_GLUE, "com.example");
        values.put(PKB_props.PKB_FEATURES, "features");
        values.put(PKB_props.PKB_DATA_PATH, "data");
        values.put(PKB_props.PKB_CALL_PATH, "calls");
        values.put(PKB_props.PKB_COMPONENT_PATH, "component");
        values.put(PKB_props.PKB_CONFIG_PATH, "configs");
        values.put(PKB_props.PKB_BROWSER, "firefox");
        Workspace.writeRunVars(Workspace.directory(temp, "run-seeded"), values);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"confirm", temp.toString(), "--run-id=run-seeded", "--tags=@smoke"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> 0
        );
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertFalse(err.contains("No prior Discover snapshot"), err);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("workspace="), stdout.toString(StandardCharsets.UTF_8));
        assertEquals(0, exit, err);
    }

    @Test
    void queryRecordsTheAgentFromTheRunWhenTheFlagIsOmitted() throws Exception {
        AgentCoordination.begin(temp, AgentCoordination.Request.of("run-q", "agent-known", null, null, null, null, "discover"));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"query", "runs", temp.toString(), "--run-id=run-q"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)
        );
        assertEquals(0, exit, stdout.toString(StandardCharsets.UTF_8));
        String log = Files.readString(AgentLog.file(temp));
        assertTrue(log.contains("\tagent-known\trun-q\tquery\truns\t"), log);
    }

    @Test
    void launchCausePrefersTheLastCausedByLine() {
        String text = """
                [ERROR] Please refer to the dump
                java.lang.RuntimeException: outer
                Caused by: java.lang.IllegalStateException: first
                Caused by: java.nio.file.NoSuchFileException: missing.jar
                \tat java.base/java.nio.file.Files.read
                [ERROR] -> [Help 1]
                [ERROR] Re-run Maven using -X
                """;
        assertEquals("Caused by: java.nio.file.NoSuchFileException: missing.jar", WorkbenchAgentCommands.launchCause(text));
        assertEquals("[ERROR] Failed to execute goal example:compile", WorkbenchAgentCommands.launchCause("[ERROR] Failed to execute goal example:compile\n"));
    }

    @Test
    void beforeAllHookThatThrowsIsLaunchErrorWithTheConsoleCause() throws Exception {
        writeFeature();
        Path hook = temp.resolve("src/test/java/com/example/site/BeforeAllSite.java");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, """
                package com.example.site;
                import io.cucumber.java.BeforeAll;
                import java.net.BindException;
                public final class BeforeAllSite {
                    @BeforeAll
                    public static void open() throws Exception {
                        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                            if (frame.getClassName().startsWith("io.cucumber")) {
                                System.err.println(frame);
                                break;
                            }
                        }
                        throw new BindException("Address already in use");
                    }
                }
                """);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", temp.toString(), "--run-id=run-hook", "--tags=@smoke"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                try {
                    runCompiledBeforeAll(hook, err);
                    Path run = AgentCoordination.runDirectory(project, "run-hook").resolve("diagnostic").resolve("run-hook");
                    Files.createDirectories(run.resolve("scenarios").resolve("s1"));
                    Files.writeString(run.resolve("run-index.json"), """
                            {
                              "runId": "run-hook",
                              "outcome": "FAILED",
                              "counts": { "total": 1, "passed": 0, "failed": 1 },
                              "steps": { "executed": 0, "passed": 0, "failed": 0, "skipped": 0 }
                            }
                            """);
                    Files.writeString(run.resolve("scenarios").resolve("s1").resolve("summary.json"), """
                            {"outcome":"FAILED","steps":{"executed":0,"passed":0,"failed":0,"skipped":0,"other":0}}
                            """);
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
                return 1;
            }
        );
        assertEquals(1, exit);
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("summary status=LAUNCH_ERROR scenarios=0 steps=0"), out);
        assertFalse(out.contains("summary status=FAILED"), out);
        assertTrue(err.contains("error: java.net.BindException: Address already in use")
                || err.contains("error: Caused by: java.net.BindException: Address already in use"), err);
        assertTrue(err.contains("fix: A before-all hook or launch step failed before any step ran; see .pickleball/runs/run-hook/console/"), err);
        String record = Files.readString(AgentCoordination.runDirectory(temp, "run-hook").resolve("record.json"));
        assertTrue(record.contains("LAUNCH_ERROR"), record);
        assertTrue(Files.readString(hook).contains("@BeforeAll"));
        String saved = Files.readString(AgentCoordination.runDirectory(temp, "run-hook").resolve("console").resolve("The_one.txt"));
        assertTrue(saved.contains("BeforeAllSite.open"), saved);
        assertTrue(saved.contains("io.cucumber"), saved);
    }

    @Test
    void failedScenarioThatRanAStepStaysFailed() throws Exception {
        writeFeature();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = WorkbenchAgentCommands.run(
                new String[]{"discover", temp.toString(), "--run-id=run-step", "--tags=@smoke"},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                (project, command, out, err) -> {
                    try {
                        Path index = AgentCoordination.runDirectory(project, "run-step")
                                .resolve("diagnostic").resolve("run-index.json");
                        Files.createDirectories(index.getParent());
                        Files.writeString(index, """
                                {
                                  "runId": "run-step",
                                  "outcome": "FAILED",
                                  "counts": { "total": 1, "passed": 0, "failed": 1 },
                                  "steps": { "executed": 1, "passed": 0, "failed": 1 }
                                }
                                """);
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return 1;
                }
        );
        assertEquals(1, exit);
        String out = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("summary status=FAILED"), out);
        assertFalse(out.contains("summary status=LAUNCH_ERROR"), out);
    }

    /** Compiles the fixture and runs it as a Cucumber @BeforeAll hook, not Method.invoke. */
    private static void runCompiledBeforeAll(Path source, PrintStream err) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required to run the before-all fixture");
        }
        Path classes = Files.createTempDirectory("before-all-classes");
        Path feature = classes.resolveSibling("before-all.feature");
        Files.writeString(feature, """
                Feature: Hook
                  Scenario: The one
                    Given one
                """);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            Boolean compiled = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                    null,
                    files.getJavaFileObjects(source)
            ).call();
            if (!Boolean.TRUE.equals(compiled)) {
                throw new IllegalStateException("before-all fixture did not compile: " + diagnostics.getDiagnostics());
            }
        }
        Thread thread = Thread.currentThread();
        ClassLoader previousLoader = thread.getContextClassLoader();
        PrintStream previousOut = System.out;
        PrintStream previousErr = System.err;
        Throwable thrown = null;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, WorkbenchLaunchCorrectnessTest.class.getClassLoader())) {
            thread.setContextClassLoader(loader);
            System.setOut(err);
            System.setErr(err);
            try {
                Main.run(
                        new String[]{"--glue", "com.example.site", "--monochrome", feature.toString()},
                        loader
                );
            } catch (Throwable failure) {
                thrown = failure;
            }
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
            thread.setContextClassLoader(previousLoader);
        }
        if (thrown != null) thrown.printStackTrace(err);
    }

    private void writeFeature() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path script = temp.resolve(windows ? "mvnw.cmd" : "mvnw");
        Files.writeString(script, windows ? "@echo off\r\n" : "#!/bin/sh\n");
        Path features = temp.resolve("src/test/resources/features");
        Files.createDirectories(features);
        Files.writeString(features.resolve("one.feature"), """
                @smoke
                Feature: One
                  Scenario: The one
                    Given one
                """);
    }
}
