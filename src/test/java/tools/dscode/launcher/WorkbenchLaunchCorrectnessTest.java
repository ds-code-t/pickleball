package tools.dscode.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.coordination.AgentCoordination;
import tools.dscode.control.protocol.AgentLog;
import tools.dscode.control.protocol.Workspace;
import tools.dscode.testengine.PKB_props;
import tools.dscode.testengine.PickleballRunner;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import io.cucumber.core.cli.Main;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
        assertTrue(err.contains("fix: Read .pickleball/runs/run-dead/console/ and fix the reported cause, then run again."), err);
        String saved = Files.readString(AgentCoordination.runDirectory(temp, "run-dead").resolve("console").resolve("The_one.txt"));
        assertTrue(saved.contains("fork died"), saved);
    }

    @Test
    void quietDiscoverKeepsTheSummaryAndStillWritesTheConsole() throws Exception {
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
        assertTrue(out.contains("summary status="), out);
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
    void launchCaptureKeepsTheCauseWhenTheConsoleIsHuge() throws Exception {
        ByteArrayOutputStream buffer = WorkbenchAgentCommands.captureBuffer();
        buffer.write("BeforeAllSite.open\n".getBytes(StandardCharsets.UTF_8));
        byte[] block = "line\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 200_000; i++) buffer.write(block, 0, block.length);
        String cause = "Caused by: java.net.BindException: Address already in use";
        buffer.write((cause + "\n").getBytes(StandardCharsets.UTF_8));
        String text = buffer.toString(StandardCharsets.UTF_8);
        assertTrue(text.length() < WorkbenchAgentCommands.CaptureBuffer.HEAD
                + WorkbenchAgentCommands.CaptureBuffer.TAIL
                + 200, "length=" + text.length());
        assertTrue(text.contains("BeforeAllSite.open"), "missing hook frame");
        assertTrue(text.contains("console truncated"), "missing truncation marker");
        assertEquals(cause, WorkbenchAgentCommands.launchCause(text));
    }

    @Test
    void launchCaptureBytesIncludeHeadMarkerAndTail() throws Exception {
        ByteArrayOutputStream small = WorkbenchAgentCommands.captureBuffer();
        small.write("small\n".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("small\n".getBytes(StandardCharsets.UTF_8), small.toByteArray());
        assertEquals(6, small.size());

        ByteArrayOutputStream buffer = WorkbenchAgentCommands.captureBuffer();
        buffer.write("BeforeAllSite.open\n".getBytes(StandardCharsets.UTF_8));
        byte[] block = "line\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 200_000; i++) buffer.write(block, 0, block.length);
        String cause = "Caused by: java.net.BindException: Address already in use\n";
        buffer.write(cause.getBytes(StandardCharsets.UTF_8));

        byte[] bytes = buffer.toByteArray();
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertEquals(bytes.length, buffer.size());
        assertTrue(text.contains("BeforeAllSite.open"), text.substring(0, Math.min(80, text.length())));
        assertTrue(text.contains("console truncated"), "missing truncation marker");
        assertTrue(text.contains("BindException"), "tail was dropped");
        assertTrue(bytes.length < WorkbenchAgentCommands.CaptureBuffer.HEAD
                + WorkbenchAgentCommands.CaptureBuffer.TAIL
                + 200, "length=" + bytes.length);
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        buffer.writeTo(copy);
        assertArrayEquals(bytes, copy.toByteArray());
        assertEquals(text, buffer.toString(StandardCharsets.UTF_8));
        assertTrue(buffer.toString(0).contains("BindException"));
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
        Properties previousProperties = new Properties();
        previousProperties.putAll(System.getProperties());
        // Discover and Cucumber Main both read cucumber.* and pkb_* from this JVM.
        // A previous suite test must not hand this fixture its feature list or browser.
        clearInheritedLaunchProperties();
        Map<String, String> hiddenRunnerValues = hideSuiteRunnerPaths();
        int exit;
        try {
            exit = WorkbenchAgentCommands.run(
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
        } finally {
            restoreSuiteRunnerPaths(hiddenRunnerValues);
            System.setProperties(previousProperties);
        }
        assertEquals(1, exit);
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("summary status=LAUNCH_ERROR scenarios=0 steps=0"), out);
        assertFalse(out.contains("summary status=FAILED"), out);
        assertTrue(err.contains("error: java.net.BindException: Address already in use")
                || err.contains("error: Caused by: java.net.BindException: Address already in use"), err);
        assertTrue(err.contains("fix: A before-all hook or launch step failed before any step ran. Read .pickleball/runs/run-hook/console/ and fix that hook, then run again."), err);
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

    /** Drops suite launch settings so this fixture does not rerun the caller's features. */
    private static void clearInheritedLaunchProperties() {
        for (String key : new ArrayList<>(System.getProperties().stringPropertyNames())) {
            if (key.startsWith("cucumber.") || key.startsWith("pkb_") || key.startsWith("webdriver.")) {
                System.clearProperty(key);
            }
        }
    }

    /** A suite run leaves the first Cucumber runner and glue in place. This fixture needs a fresh one. */
    private static final class GlobalGluePark {
        Object runner;
        Object glue;
        boolean prepared;
    }

    private static GlobalGluePark parkGlobalGlue() {
        GlobalGluePark park = new GlobalGluePark();
        try {
            Class<?> state = Class.forName("io.cucumber.core.runner.GlobalState");
            java.lang.reflect.Field runner = state.getDeclaredField("globalRunner");
            java.lang.reflect.Field glue = state.getDeclaredField("globalCachingGlue");
            java.lang.reflect.Field prepared = state.getDeclaredField("gluePrepared");
            runner.setAccessible(true);
            glue.setAccessible(true);
            prepared.setAccessible(true);
            park.runner = runner.get(null);
            park.glue = glue.get(null);
            park.prepared = prepared.getBoolean(null);
            runner.set(null, null);
            glue.set(null, null);
            prepared.setBoolean(null, false);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        return park;
    }

    private static void restoreGlobalGlue(GlobalGluePark park) {
        if (park == null) return;
        try {
            Class<?> state = Class.forName("io.cucumber.core.runner.GlobalState");
            java.lang.reflect.Field runner = state.getDeclaredField("globalRunner");
            java.lang.reflect.Field glue = state.getDeclaredField("globalCachingGlue");
            java.lang.reflect.Field prepared = state.getDeclaredField("gluePrepared");
            runner.setAccessible(true);
            glue.setAccessible(true);
            prepared.setAccessible(true);
            runner.set(null, park.runner);
            glue.set(null, park.glue);
            prepared.setBoolean(null, park.prepared);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The live suite runner must not replace this fixture's glue or feature list. */
    private static Map<String, String> hideSuiteRunnerPaths() {
        PickleballRunner runner = PickleballRunner.rawInstance();
        if (runner == null) return Map.of();
        Map<String, String> saved = new LinkedHashMap<>();
        for (String key : List.of(
                "pkb_glue", "pkb_features", "pkb_browser", "pkb_tags",
                "cucumber.glue", "cucumber.features", "cucumber.filter.tags"
        )) {
            if (runner.values().containsKey(key)) {
                saved.put(key, runner.values().get(key));
            }
        }
        try {
            java.lang.reflect.Field field = PickleballRunner.class.getDeclaredField("values");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> values = (Map<String, String>) field.get(runner);
            for (String key : saved.keySet()) values.remove(key);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        return saved;
    }

    private static void restoreSuiteRunnerPaths(Map<String, String> saved) {
        if (saved == null || saved.isEmpty()) return;
        PickleballRunner runner = PickleballRunner.rawInstance();
        if (runner == null) return;
        try {
            java.lang.reflect.Field field = PickleballRunner.class.getDeclaredField("values");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> values = (Map<String, String>) field.get(runner);
            values.putAll(saved);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
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
        Properties previousProperties = new Properties();
        previousProperties.putAll(System.getProperties());
        Throwable thrown = null;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, WorkbenchLaunchCorrectnessTest.class.getClassLoader())) {
            thread.setContextClassLoader(loader);
            System.setOut(err);
            System.setErr(err);
            // Cucumber Main merges cucumber.* and pkb_* from the JVM. A previous
            // suite test must not add its features or browser to this hook fixture.
            clearInheritedLaunchProperties();
            Map<String, String> hiddenRunnerValues = hideSuiteRunnerPaths();
            GlobalGluePark parkedGlue = parkGlobalGlue();
            try {
                Main.run(
                        new String[]{"--glue", "com.example.site", "--monochrome", feature.toString()},
                        loader
                );
            } catch (Throwable failure) {
                thrown = failure;
            } finally {
                restoreGlobalGlue(parkedGlue);
                restoreSuiteRunnerPaths(hiddenRunnerValues);
            }
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
            thread.setContextClassLoader(previousLoader);
            System.setProperties(previousProperties);
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
