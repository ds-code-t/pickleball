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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a real JVM whose classpath has no sqlite-jdbc. The in-process tests
 * cannot hide that jar.
 */
class DaltonDriverMissingTest {
    @TempDir
    Path project;

    @Test
    void enabledHydrateAndAutoHookFailLoudlyAndDoNotCreateADatabase() throws Exception {
        Path feature = project.resolve("features/one.feature");
        Files.createDirectories(feature.getParent());
        Files.writeString(feature, "Feature: One\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("dalton.properties"), "enabled=true\n", StandardCharsets.UTF_8);

        Result hydrate = java(List.of(
                "-Dpkb_dalton=true",
                "-Dpkb_dalton_project=" + project,
                "tools.dscode.dalton.DaltonCli",
                "dalton",
                "hydrate",
                "--project=" + project));
        assertEquals(1, hydrate.code, hydrate.out + hydrate.err);
        assertTrue(hydrate.err.contains(DaltonDatabase.MISSING_DRIVER), hydrate.err);
        assertFalse(hydrate.err.contains("Dalton auto-hydrate skipped"), hydrate.err);
        assertFalse(Files.exists(DaltonPaths.database(project)));
        assertFalse(Files.exists(DaltonPaths.root(project)));

        Result status = java(List.of(
                "-Dpkb_dalton=true",
                "-Dpkb_dalton_project=" + project,
                "tools.dscode.dalton.DaltonCli",
                "dalton",
                "status",
                "--project=" + project));
        assertEquals(1, status.code, status.out + status.err);
        assertTrue(status.out.contains(DaltonDatabase.MISSING_DRIVER), status.out);
        assertFalse(Files.exists(DaltonPaths.database(project)));

        Result hook = probe("true");
        assertEquals(1, hook.code, hook.out + hook.err);
        assertTrue(hook.err.contains(DaltonDatabase.MISSING_DRIVER), hook.err);
        assertFalse(hook.out.contains("NO_THROW"), hook.out);
        assertFalse(hook.err.contains("Dalton auto-hydrate skipped"), hook.err);
        assertFalse(Files.exists(DaltonPaths.database(project)));
    }

    @Test
    void disabledDoesNotLookForTheDriver() throws Exception {
        Result hook = probe("false");
        assertEquals(0, hook.code, hook.out + hook.err);
        assertTrue(hook.out.contains("NO_THROW"), hook.out);
        assertFalse(Files.exists(DaltonPaths.database(project)));

        Result status = java(List.of(
                "-Dpkb_dalton=false",
                "-Dpkb_dalton_project=" + project,
                "tools.dscode.dalton.DaltonCli",
                "dalton",
                "status",
                "--project=" + project));
        assertEquals(0, status.code, status.out + status.err);
        assertTrue(status.out.contains("Dalton database missing"), status.out);
        assertFalse(status.out.contains(DaltonDatabase.MISSING_DRIVER), status.out);
        assertFalse(Files.exists(DaltonPaths.root(project)));
    }

    private Result probe(String enabled) throws Exception {
        Path source = project.resolve("DaltonDriverProbe.java");
        Files.writeString(source, """
                public class DaltonDriverProbe {
                    public static void main(String[] args) {
                        System.setProperty("pkb_dalton", args[0]);
                        System.setProperty("pkb_dalton_project", args[1]);
                        tools.dscode.dalton.DaltonRuntime.reset();
                        try {
                            tools.dscode.dalton.DaltonRuntime.beforeFeatureLoad();
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
                "DaltonDriverProbe",
                enabled,
                project.toString()));
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
