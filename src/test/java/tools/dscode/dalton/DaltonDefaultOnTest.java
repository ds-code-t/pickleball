package tools.dscode.dalton;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaltonDefaultOnTest {
    @TempDir
    Path project;

    @Test
    void noFlagAndNoFileIsOnFromTheDefault() {
        String previous = System.getProperty("pkb_dalton");
        String previousProject = System.getProperty("pkb_dalton_project");
        try {
            System.clearProperty("pkb_dalton");
            System.setProperty("pkb_dalton_project", project.toString());
            assertTrue(DaltonConfig.enabled(project));
            assertEquals("default", DaltonConfig.source(project));
        } finally {
            restore("pkb_dalton", previous);
            restore("pkb_dalton_project", previousProject);
        }
    }

    @Test
    void fileFalseTurnsItOffAndPropertyBeatsTheFile() throws Exception {
        Files.writeString(project.resolve("dalton.properties"), "enabled=false\n");
        String previous = System.getProperty("pkb_dalton");
        try {
            System.clearProperty("pkb_dalton");
            assertFalse(DaltonConfig.enabled(project));
            assertEquals("file", DaltonConfig.source(project));
            Files.writeString(project.resolve("dalton.properties"), "enabled=true\n");
            System.setProperty("pkb_dalton", "false");
            assertFalse(DaltonConfig.enabled(project));
            assertEquals("property", DaltonConfig.source(project));
        } finally {
            restore("pkb_dalton", previous);
        }
    }

    @Test
    void envFalseTurnsItOffInAChildJvm() throws Exception {
        Result child = java(List.of(
                "-Dpkb_dalton_project=" + project,
                "tools.dscode.dalton.DaltonCli",
                "dalton", "status", "--project=" + project), "false");
        assertEquals(0, child.code, child.out + child.err);
        assertTrue(child.out.contains("enabled=false source=env"), child.out);
    }

    @Test
    void badValueNamesTheKey() {
        String previous = System.getProperty("pkb_dalton");
        try {
            System.setProperty("pkb_dalton", "maybe");
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> DaltonConfig.enabled(project));
            assertTrue(failure.getMessage().contains("pkb_dalton"), failure.getMessage());
            assertTrue(failure.getMessage().contains(DaltonConfig.ACCEPTED), failure.getMessage());
        } finally {
            restore("pkb_dalton", previous);
        }
    }

    @Test
    void missingDriverWithNoFlagFailsLoudlyAndCreatesNothing() throws Exception {
        Result child = java(withoutSqlite(), List.of(
                "-Dpkb_dalton_project=" + project,
                "tools.dscode.dalton.DaltonCli",
                "dalton", "status", "--project=" + project), null);
        assertEquals(1, child.code, child.out + child.err);
        assertTrue(child.out.contains(DaltonDatabase.MISSING_DRIVER), child.out);
        assertTrue(child.out.contains("Dalton is on by default"), child.out);
        assertFalse(Files.exists(DaltonPaths.database(project)));
        assertFalse(Files.exists(DaltonPaths.root(project)));
    }

    @Test
    void statusJsonReportsTheDefault() throws Exception {
        String previous = System.getProperty("pkb_dalton");
        try {
            System.clearProperty("pkb_dalton");
            ByteArrayCapture capture = capture("dalton", "status", "--json", "--project=" + project);
            assertEquals(0, capture.code, capture.out + capture.err);
            assertTrue(capture.out.contains("\"enabled\":true"), capture.out);
            assertTrue(capture.out.contains("\"enabledSource\":\"default\""), capture.out);
        } finally {
            restore("pkb_dalton", previous);
        }
    }

    private Result java(List<String> args, String env) throws Exception {
        return java(null, args, env);
    }

    private Result java(String classpath, List<String> args, String env) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(classpath == null ? System.getProperty("java.class.path") : classpath);
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command);
        if (env != null) builder.environment().put("PKB_DALTON", env);
        else builder.environment().remove("PKB_DALTON");
        Process process = builder.start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timed out");
        }
        return new Result(process.exitValue(), out, err);
    }

    private static String withoutSqlite() {
        String separator = java.io.File.pathSeparator;
        StringBuilder filtered = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(separator)) {
            if (entry.toLowerCase(Locale.ROOT).contains("sqlite")) continue;
            if (filtered.length() > 0) filtered.append(separator);
            filtered.append(entry);
        }
        return filtered.toString();
    }

    private ByteArrayCapture capture(String... args) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        int code = DaltonCli.execute(args, new java.io.PrintStream(out), new java.io.PrintStream(err));
        return new ByteArrayCapture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private record Result(int code, String out, String err) {
    }

    private record ByteArrayCapture(int code, String out, String err) {
    }
}
