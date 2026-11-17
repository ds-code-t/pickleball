package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;

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

class StoreSandboxBindingTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() throws Exception {
        StoreScope.clear();
        System.clearProperty("pkb_store_sandbox");
        StoreTestProcesses.release(project);
    }

    @Test
    void flagBeatsPropertyAndASecondSandboxFails() {
        System.setProperty("pkb_store_sandbox", "fromprop");
        assertEquals("fromprop", StoreScope.bindCommand(List.of()).name());
        StoreScope.clear();
        assertEquals("fromflag", StoreScope.bindCommand(List.of("--sandbox=fromflag")).name());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> StoreScope.bind(Binding.sandbox("other")));
        assertTrue(failure.getMessage().contains("already bound to sandbox fromflag"), failure.getMessage());
        assertTrue(failure.getMessage().contains("one sandbox per process"), failure.getMessage());
    }

    @Test
    void theCliRefusesASecondSandboxInThisJvm() {
        Capture first = cli("store", "status", "--sandbox=alpha", "--project=" + project);
        assertEquals(0, first.code, first.err);
        Capture second = cli("store", "status", "--sandbox=beta", "--project=" + project);
        assertEquals(1, second.code, second.out + second.err);
        assertTrue(second.err.contains("already bound to sandbox alpha"), second.err);
        assertTrue(second.err.contains("one sandbox per process"), second.err);
    }

    @Test
    void anUnknownSandboxFailsWhenTheStoreIsOpened() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        Capture hydrated = cli("store", "hydrate", "--project=" + project);
        assertEquals(0, hydrated.code, hydrated.err);
        StoreScope.clear();
        Capture missing = cli("store", "features", "list", "--sandbox=missing", "--project=" + project);
        assertEquals(1, missing.code, missing.out + missing.err);
        assertTrue(missing.err.contains("unknown sandbox missing"), missing.err);
    }

    @Test
    void environmentLosesToThePropertyAndTheFlag() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        for (String name : List.of("fromenv", "fromprop", "fromflag")) {
            assertEquals(0, cli("store", "sandbox", "create", name, "--project=" + project).code, name);
            StoreScope.clear();
        }
        String env = child(null, "fromenv", null);
        assertTrue(env.contains("sandbox=fromenv"), env);
        String property = child("fromprop", "fromenv", null);
        assertTrue(property.contains("sandbox=fromprop"), property);
        String flag = child("fromprop", "fromenv", "fromflag");
        assertTrue(flag.contains("sandbox=fromflag"), flag);
    }

    private String child(String property, String env, String flag) throws Exception {
        Files.deleteIfExists(project.resolve(".pickleball/store/store.log"));
        List<String> command = new java.util.ArrayList<>();
        command.add("-Dpkb_store_log=query");
        if (property != null) command.add("-Dpkb_store_sandbox=" + property);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tools.dscode.store.StoreCli");
        command.add("store");
        command.add("features");
        command.add("show");
        command.add("features/pay.feature");
        command.add("--project=" + project.toAbsolutePath());
        if (flag != null) command.add("--sandbox=" + flag);
        ProcessBuilder builder = new ProcessBuilder(StoreTestProcesses.java(command));
        if (env != null) builder.environment().put("PKB_STORE_SANDBOX", env);
        else builder.environment().remove("PKB_STORE_SANDBOX");
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
        return Files.readString(project.resolve(".pickleball/store/store.log"));
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
        int code = StoreCli.execute(args, new PrintStream(out), new PrintStream(err));
        return new Capture(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
