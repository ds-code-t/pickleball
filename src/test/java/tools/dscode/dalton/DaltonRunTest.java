package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.reporting.diagnostic.ConsumerMavenTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonRunTest {
    @TempDir
    Path project;

    @Test
    void fingerprintAndCompilePlanFollowTheGlue() throws Exception {
        Path java = project.resolve("src/test/java/Sample.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, "class Sample {}\n");
        Files.writeString(project.resolve("pom.xml"), "<project></project>\n");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\n");
        Files.writeString(project.resolve("mvnw.cmd"), "@echo off\n");

        String first = DaltonRun.glueFingerprint(project);
        DaltonRun.Plan missing = DaltonRun.plan(project);
        assertTrue(missing.compile(), missing.line());
        assertTrue(missing.restart());
        assertTrue(missing.compileCommand().get(0).endsWith("mvnw"), missing.compileCommand().toString());
        assertEquals("test-compile", missing.compileCommand().get(2));

        String os = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            assertTrue(DaltonRun.compileCommand(project).get(0).endsWith("mvnw.cmd"));
        } finally {
            System.setProperty("os.name", os);
        }

        Files.createDirectories(project.resolve(".pickleball/dalton"));
        Files.writeString(project.resolve(".pickleball/dalton/glue.fingerprint"), first);
        assertFalse(DaltonRun.plan(project).compile());

        Files.writeString(java, "class Sample { int x; }\n");
        assertNotEquals(first, DaltonRun.glueFingerprint(project));
        assertTrue(DaltonRun.plan(project).compile());

        Files.delete(project.resolve("pom.xml"));
        Files.delete(project.resolve("mvnw"));
        Files.writeString(project.resolve("build.gradle"), "plugins {}\n");
        Files.writeString(project.resolve("gradlew"), "#!/bin/sh\n");
        List<String> gradle = DaltonRun.compileCommand(project);
        assertTrue(gradle.get(0).endsWith("gradlew"), gradle.toString());
        assertEquals("testClasses", gradle.get(1));

        Files.delete(project.resolve("build.gradle"));
        Files.delete(project.resolve("gradlew"));
        assertTrue(DaltonRun.compileCommand(project).isEmpty());
    }

    @Test
    void dryRunPrintsThePlanAndCreatesNothing() {
        Capture capture = cli("dalton", "run", "--dry-run", "--tags=@none", "--name=One", "--glue=steps", "--project=" + project);
        assertEquals(0, capture.code, capture.err);
        assertTrue(capture.out.contains("dry-run fingerprint="), capture.out);
        assertTrue(capture.out.contains("compile=true"), capture.out);
        assertTrue(capture.out.contains("restart=true"), capture.out);
        assertFalse(Files.exists(project.resolve(".pickleball")));
        assertTrue(DaltonCli.help().contains("dalton run"));
        List<String> selected = DaltonRun.selection("@none", "One", "steps", List.of("-Dpkb_example=3", "skip"));
        assertEquals(List.of("-Dpkb_tags=@none", "-Dpkb_name=One", "-Dpkb_glue=steps", "-Dpkb_example=3"), selected);
        List<String> forced = DaltonRun.runProperties(List.of("-Dpkb_dalton=false", "-Dpkb_tags=@none"));
        assertEquals("-Dpkb_dalton=true", forced.get(forced.size() - 2));
        assertEquals(ConsumerMavenTestRunner.PLATFORM_CUCUMBER_ENGINE, forced.get(forced.size() - 1));
        assertFalse(forced.contains("-Dpkb_dalton=false"));
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void warmWorkerIsReusedAndLogsTheRun() throws Exception {
        Files.createDirectories(project.resolve("build/classes/java/test"));
        Files.createDirectories(project.resolve(".pickleball/dalton"));
        String fingerprint = DaltonRun.glueFingerprint(project);
        Files.writeString(project.resolve(".pickleball/dalton/glue.fingerprint"), fingerprint);
        String classpath = System.getProperty("pkb_dalton_run_classpath");
        String log = System.getProperty("pkb_dalton_log");
        try {
            System.setProperty("pkb_dalton_run_classpath", System.getProperty("java.class.path"));
            System.setProperty("pkb_dalton_log", "run");
            DaltonRun.Outcome first = DaltonRun.execute(project, List.of("-Dpkb_tags=@dalton-run-matches-nothing"), false);
            assertEquals(1, first.exit(), workerLog(first));
            assertFalse(first.reused(), workerLog(first));
            long pid = warmPid();
            DaltonRun.Outcome second = DaltonRun.execute(project, List.of("-Dpkb_tags=@dalton-run-matches-nothing"), false);
            assertEquals(1, second.exit(), workerLog(second));
            assertTrue(second.reused(), workerLog(second));
            assertEquals(first.exit(), second.exit());
            assertEquals(pid, warmPid());
            String lines = Files.readString(project.resolve(".pickleball/dalton/dalton.log"), StandardCharsets.UTF_8);
            assertTrue(lines.contains(" run worker started exit=1"), lines);
            assertTrue(lines.contains(" run worker reused exit=1"), lines);
        } finally {
            DaltonRun.stop(project);
            restore("pkb_dalton_run_classpath", classpath);
            restore("pkb_dalton_log", log);
        }
        assertFalse(Files.isRegularFile(project.resolve(".pickleball/dalton/warm.txt")));
    }

    @Test
    void timingScriptSelfCheckDoesNotLaunchASuite() throws Exception {
        Process process = new ProcessBuilder("python3", "scripts/time_dalton_run.py", "--self-check")
                .directory(Path.of("").toAbsolutePath().toFile())
                .redirectErrorStream(true)
                .start();
        String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), text);
        assertTrue(text.contains("pickleball-test-engine"), text);
        assertTrue(text.contains("does not launch a suite"), text);
    }

    private long warmPid() throws Exception {
        List<String> lines = Files.readAllLines(project.resolve(".pickleball/dalton/warm.txt"));
        return Long.parseLong(lines.get(0).trim());
    }

    private String workerLog(DaltonRun.Outcome outcome) throws Exception {
        Path err = project.resolve(".pickleball/dalton/warm.err.log");
        String logged = Files.isRegularFile(err) ? Files.readString(err) : "";
        return outcome.out() + outcome.err() + "\n" + logged;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DaltonCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
