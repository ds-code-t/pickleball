package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.Binding;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltSandboxBindingTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() throws Exception {
        DoltScope.clear();
        System.clearProperty("pkb_dolt_sandbox");
        DoltTestProcesses.release(project);
    }

    @Test
    void flagBeatsPropertyAndASecondSandboxFails() {
        System.setProperty("pkb_dolt_sandbox", "fromprop");
        assertEquals("fromprop", DoltScope.bindCommand(List.of()).name());
        DoltScope.clear();
        assertEquals("fromflag", DoltScope.bindCommand(List.of("--sandbox=fromflag")).name());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DoltScope.bind(Binding.sandbox("other")));
        assertTrue(failure.getMessage().contains("already bound to sandbox fromflag"), failure.getMessage());
        assertTrue(failure.getMessage().contains("one sandbox per process"), failure.getMessage());
    }

    @Test
    void theCliRefusesASecondSandboxInThisJvm() {
        Capture first = cli("dolt", "status", "--sandbox=alpha", "--project=" + project);
        assertEquals(0, first.code, first.err);
        Capture second = cli("dolt", "status", "--sandbox=beta", "--project=" + project);
        assertEquals(1, second.code, second.out + second.err);
        assertTrue(second.err.contains("already bound to sandbox alpha"), second.err);
        assertTrue(second.err.contains("one sandbox per process"), second.err);
    }

    @Test
    void anUnknownSandboxFailsWhenTheStoreIsOpened() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        Capture hydrated = cli("dolt", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.err);
        DoltScope.clear();
        Capture missing = cli("dolt", "features", "list", "--sandbox=missing", "--project=" + project);
        assertEquals(1, missing.code, missing.out + missing.err);
        assertTrue(missing.err.contains("unknown sandbox missing"), missing.err);
    }

    @Test
    void environmentLosesToThePropertyAndTheFlag() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        for (String name : List.of("fromenv", "fromprop", "fromflag")) {
            assertEquals(0, cli("dolt", "sandbox", "create", name, "--project=" + project).code, name);
            DoltScope.clear();
        }
        String env = child(null, "fromenv", null);
        assertTrue(env.contains("sandbox=fromenv"), env);
        String property = child("fromprop", "fromenv", null);
        assertTrue(property.contains("sandbox=fromprop"), property);
        String flag = child("fromprop", "fromenv", "fromflag");
        assertTrue(flag.contains("sandbox=fromflag"), flag);
    }

    private String child(String property, String env, String flag) throws Exception {
        Files.deleteIfExists(project.resolve(".pickleball/dolt/dolt.log"));
        List<String> command = new java.util.ArrayList<>();
        command.add("-Dpkb_dolt_log=query");
        if (property != null) command.add("-Dpkb_dolt_sandbox=" + property);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tools.dscode.dolt.DoltCli");
        command.add("dolt");
        command.add("features");
        command.add("show");
        command.add("features/pay.feature");
        command.add("--project=" + project.toAbsolutePath());
        if (flag != null) command.add("--sandbox=" + flag);
        ProcessBuilder builder = new ProcessBuilder(DoltTestProcesses.java(command));
        if (env != null) builder.environment().put("PKB_DOLT_SANDBOX", env);
        else builder.environment().remove("PKB_DOLT_SANDBOX");
        Process process = builder.start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread stdout = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
        Thread stderr = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err));
        boolean finished = process.waitFor(90, TimeUnit.SECONDS);
        stdout.join(5_000);
        stderr.join(5_000);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timed out " + out + err);
        }
        assertEquals(0, process.exitValue(), out + " " + err);
        return Files.readString(project.resolve(".pickleball/dolt/dolt.log"));
    }

    private static void drain(InputStream input, StringBuilder into) {
        try {
            into.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) {
            into.append(failure.getMessage());
        }
    }

    private Capture cli(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = DoltCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
