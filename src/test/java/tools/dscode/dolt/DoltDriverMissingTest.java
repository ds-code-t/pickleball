package tools.dscode.dolt;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real JVM whose classpath has no sqlite-jdbc. The in-process tests
 * cannot hide that jar.
 */
class DoltDriverMissingTest {
    @TempDir
    Path project;

    @Test
    void enabledHydrateAndAutoHookFailLoudlyAndDoNotCreateADatabase() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("dolt.properties"), "enabled=true\n", StandardCharsets.UTF_8);

        Result hydrate = java(List.of(
                "-Dpkb_dolt=true",
                "-Dpkb_dolt_project=" + project,
                "tools.dscode.dolt.DoltCli",
                "dolt",
                "hydrate",
                "--project=" + project));
        assertEquals(1, hydrate.code, hydrate.out + hydrate.err);
        assertTrue(hydrate.err.contains(DoltDatabase.MISSING_DRIVER), hydrate.err);
        assertFalse(hydrate.err.contains("Dolt auto-hydrate skipped"), hydrate.err);
        assertFalse(Files.exists(DoltPaths.database(project)));
        assertFalse(Files.exists(DoltPaths.root(project)));

        Result status = java(List.of(
                "-Dpkb_dolt=true",
                "-Dpkb_dolt_project=" + project,
                "tools.dscode.dolt.DoltCli",
                "dolt",
                "status",
                "--project=" + project));
        assertEquals(1, status.code, status.out + status.err);
        assertTrue(status.out.contains(DoltDatabase.MISSING_DRIVER), status.out);
        assertFalse(Files.exists(DoltPaths.database(project)));

        Result hook = probe("true");
        assertEquals(1, hook.code, hook.out + hook.err);
        assertTrue(hook.err.contains(DoltDatabase.MISSING_DRIVER), hook.err);
        assertFalse(hook.out.contains("NO_THROW"), hook.out);
        assertFalse(hook.err.contains("Dolt auto-hydrate skipped"), hook.err);
        assertFalse(Files.exists(DoltPaths.database(project)));
    }

    @Test
    void disabledDoesNotLookForTheDriver() throws Exception {
        Result hook = probe("false");
        assertEquals(0, hook.code, hook.out + hook.err);
        assertTrue(hook.out.contains("NO_THROW"), hook.out);
        assertFalse(Files.exists(DoltPaths.database(project)));

        Result status = java(List.of(
                "-Dpkb_dolt=false",
                "-Dpkb_dolt_project=" + project,
                "tools.dscode.dolt.DoltCli",
                "dolt",
                "status",
                "--project=" + project));
        assertEquals(0, status.code, status.out + status.err);
        assertTrue(status.out.contains("Dolt database missing"), status.out);
        assertFalse(status.out.contains(DoltDatabase.MISSING_DRIVER), status.out);
        assertFalse(Files.exists(DoltPaths.root(project)));
    }

    @Test
    void missingBinaryDoesNotBlockOpeningSqlite() throws Exception {
        String bin = System.getProperty("pkb_dolt_bin");
        String auto = System.getProperty("pkb_dolt_auto_install");
        try {
            System.setProperty("pkb_dolt_bin", project.resolve("missing-dolt").toString());
            System.setProperty("pkb_dolt_auto_install", "false");
            Result hydrate = javaFull(List.of(
                    "-Dpkb_dolt_bin=" + project.resolve("missing-dolt"),
                    "-Dpkb_dolt_auto_install=false",
                    "-Dpkb_dolt_project=" + project,
                    "tools.dscode.dolt.DoltCli",
                    "dolt",
                    "hydrate",
                    "--project=" + project));
            assertEquals(0, hydrate.code, hydrate.out + hydrate.err);
            assertFalse(hydrate.err.contains("is not installed"), hydrate.err);
            assertTrue(DoltPaths.ready(project));
        } finally {
            restore("pkb_dolt_bin", bin);
            restore("pkb_dolt_auto_install", auto);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private Result probe(String enabled) throws Exception {
        Path source = project.resolve("DoltDriverProbe.java");
        Files.writeString(source, """
                public class DoltDriverProbe {
                    public static void main(String[] args) {
                        System.setProperty("pkb_dolt", args[0]);
                        System.setProperty("pkb_dolt_project", args[1]);
                        tools.dscode.dolt.DoltRuntime.reset();
                        try {
                            tools.dscode.dolt.DoltRuntime.beforeFeatureLoad();
                            System.out.println("NO_THROW");
                        } catch (Throwable failure) {
                            System.err.println(failure.getMessage());
                            System.exit(1);
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
        Path classes = project.resolve("probe-classes");
        Files.createDirectories(classes);
        Result compiled = process(List.of(
                javac(),
                "-d", classes.toString(),
                "-cp", classpath(),
                source.toString()));
        assertEquals(0, compiled.code, compiled.err);
        return java(classes, List.of(
                "DoltDriverProbe",
                enabled,
                project.toString()));
    }

    private Result javaFull(List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(java());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.addAll(args);
        return process(command);
    }

    private Result java(List<String> args) throws Exception {
        return java(null, args);
    }

    private Result java(Path extra, List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(java());
        command.add("-cp");
        command.add(extra == null ? classpath() : extra + java.io.File.pathSeparator + classpath());
        command.addAll(args);
        return process(command);
    }

    private static Result process(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timed out: " + command);
        }
        return new Result(process.exitValue(), out, err);
    }

    private static String classpath() {
        String separator = java.io.File.pathSeparator;
        StringBuilder filtered = new StringBuilder();
        for (String entry : System.getProperty("java.class.path").split(separator)) {
            if (entry.toLowerCase(Locale.ROOT).contains("sqlite")) continue;
            if (entry.toLowerCase(Locale.ROOT).contains("mariadb")) continue;
            if (filtered.length() > 0) filtered.append(separator);
            filtered.append(entry);
        }
        return filtered.toString();
    }

    private static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String javac() {
        return Path.of(System.getProperty("java.home"), "bin", "javac").toString();
    }

    private record Result(int code, String out, String err) {
    }
}
