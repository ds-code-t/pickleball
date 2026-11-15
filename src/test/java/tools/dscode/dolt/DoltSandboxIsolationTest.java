package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltSandboxIsolationTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() {
        DoltScope.clear();
    }

    @Test
    void twoSandboxesEditTheSameFeatureAndEntityWithoutTouchingMain() throws Exception {
        Files.createDirectories(project.resolve("features"));
        String original = """
                Feature: Pay
                  Scenario: Ada
                    * INFO: amount is 1
                """;
        Files.writeString(project.resolve("features/pay.feature"), original);
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "left", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "right", "--project=" + project).code);
        DoltScope.clear();

        AtomicReference<String> failures = new AtomicReference<>();
        List<Thread> edits = List.of(
                editor("left", "8", "{\"side\":\"left\"}", failures),
                editor("right", "9", "{\"side\":\"right\"}", failures));
        for (Thread thread : edits) thread.join(120_000);
        if (failures.get() != null) throw new AssertionError(failures.get());

        String leftFeature = show("left");
        String rightFeature = show("right");
        assertTrue(leftFeature.contains("amount is 8"), leftFeature);
        assertTrue(rightFeature.contains("amount is 9"), rightFeature);
        assertTrue(entity("left").contains("left"), entity("left"));
        assertTrue(entity("right").contains("right"), entity("right"));

        Capture mainFeature = cli("dolt", "features", "show", "features/pay.feature", "--project=" + project);
        assertEquals(0, mainFeature.code, mainFeature.err);
        assertTrue(mainFeature.out.contains("amount is 1"), mainFeature.out);
        assertTrue(!mainFeature.out.contains("amount is 8"), mainFeature.out);
        Capture mainEntity = cli("dolt", "entity", "get", "Wallet", "ada", "--project=" + project);
        assertEquals(1, mainEntity.code, mainEntity.out);
        assertTrue(mainEntity.err.contains("not found"), mainEntity.err);
    }

    private Thread editor(String sandbox, String amount, String payload, AtomicReference<String> failures) {
        return Thread.ofVirtual().start(() -> {
            try {
                String feature = "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is " + amount + "\n";
                Capture featureResult = capture(List.of(
                        "dolt", "feature", "put", "features/pay.feature",
                        "--text=" + feature, "--sandbox=" + sandbox, "--project=" + project));
                Capture entityResult = capture(List.of(
                        "dolt", "entity", "put", "Wallet", "ada",
                        "--payload=" + payload, "--sandbox=" + sandbox, "--project=" + project));
                if (featureResult.code != 0 || entityResult.code != 0) {
                    failures.set(sandbox + "\n" + featureResult.out + featureResult.err
                            + "\n" + entityResult.out + entityResult.err);
                }
            } catch (Exception failure) {
                failures.set(sandbox + " " + failure);
            }
        });
    }

    private String show(String sandbox) throws Exception {
        return capture(List.of(
                "dolt", "features", "show", "features/pay.feature",
                "--sandbox=" + sandbox, "--project=" + project.toAbsolutePath())).out;
    }

    private String entity(String sandbox) throws Exception {
        return capture(List.of(
                "dolt", "entity", "get", "Wallet", "ada",
                "--sandbox=" + sandbox, "--project=" + project.toAbsolutePath())).out;
    }

    private int run(List<String> args) throws Exception {
        return capture(args).code;
    }

    private Capture capture(List<String> args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tools.dscode.dolt.DoltCli");
        command.addAll(args.stream().map(String::valueOf).toList());
        Process process = new ProcessBuilder(command).start();
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
        return new Capture(process.exitValue(), out.toString(), err.toString());
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
