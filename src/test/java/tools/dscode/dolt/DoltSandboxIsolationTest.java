package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.Binding;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltSandboxIsolationTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() throws Exception {
        for (String name : new String[] {"left", "right", "main"}) {
            DoltScope.clear();
            if (!"main".equals(name)) DoltScope.bind(Binding.sandbox(name));
            DoltRun.stop(project);
        }
        DoltScope.clear();
        System.clearProperty("pkb_dolt_run_classpath");
        DoltTestProcesses.release(project);
    }

    @Test
    @Timeout(value = 10, unit = java.util.concurrent.TimeUnit.MINUTES)
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

        Path leftJava = project.resolve("left-steps.java");
        Path rightJava = project.resolve("right-steps.java");
        Files.writeString(leftJava, sideSource("left-body"));
        Files.writeString(rightJava, sideSource("right-body"));
        Capture leftGlue = capture(List.of(
                "dolt", "glue", "put", "--path=src/test/java/tools/dscode/dolt/fixback/SideSteps.java",
                "--file=" + leftJava.toAbsolutePath(), "--sandbox=left", "--project=" + project));
        Capture rightGlue = capture(List.of(
                "dolt", "glue", "put", "--path=src/test/java/tools/dscode/dolt/fixback/SideSteps.java",
                "--file=" + rightJava.toAbsolutePath(), "--sandbox=right", "--project=" + project));
        assertEquals(0, leftGlue.code, leftGlue.out + leftGlue.err);
        assertEquals(0, rightGlue.code, rightGlue.out + rightGlue.err);
        Capture leftPut = capture(List.of(
                "dolt", "feature", "put", "features/pay.feature",
                "--file=" + writeFeature("left", "8", "left-note-zz"),
                "--sandbox=left", "--project=" + project));
        Capture rightFeatureRun = capture(List.of(
                "dolt", "feature", "put", "features/pay.feature",
                "--file=" + writeFeature("right", "9", "right-note-zz"),
                "--sandbox=right", "--project=" + project));
        assertEquals(0, leftPut.code, leftPut.err);
        assertEquals(0, rightFeatureRun.code, rightFeatureRun.err);
        Capture leftEntity = capture(List.of(
                "dolt", "entity", "put", "Wallet", "ada",
                "--payload={\"note\":\"left-note-zz\"}", "--sandbox=left", "--project=" + project));
        Capture rightEntity = capture(List.of(
                "dolt", "entity", "put", "Wallet", "ada",
                "--payload={\"note\":\"right-note-zz\"}", "--sandbox=right", "--project=" + project));
        assertEquals(0, leftEntity.code, leftEntity.err);
        assertEquals(0, rightEntity.code, rightEntity.err);

        Path mainFile = project.resolve("main-feature.feature");
        Files.writeString(mainFile, """
                Feature: Pay
                  @dolt-side
                  Scenario: Main pay
                    * INFO: amount is 1
                    * ASSERT: "1" equals "1"
                """);
        Capture mainPut = cli("dolt", "feature", "put", "features/pay.feature",
                "--file=" + mainFile.toAbsolutePath(), "--project=" + project);
        assertEquals(0, mainPut.code, mainPut.err);
        DoltScope.clear();
        String fingerprint = DoltRun.glueFingerprint(project);
        for (String name : java.util.List.of("main", "left", "right")) {
            Path worker = DoltPaths.worker(project, name);
            Files.createDirectories(worker);
            Files.writeString(worker.resolve(DoltRun.FINGERPRINT), fingerprint);
        }

        Capture leftRun = runSandbox("left");
        Capture rightRun = runSandbox("right");
        Capture mainRun = runSandbox(null);
        assertEquals(0, leftRun.code, leftRun.out + leftRun.err);
        assertEquals(0, rightRun.code, rightRun.out + rightRun.err);
        assertEquals(0, mainRun.code, mainRun.out + mainRun.err);
        assertTrue(leftRun.out.contains("scenario PASSED Left pay"), leftRun.out);
        assertTrue(leftRun.out.contains("left-body left-note-zz"), leftRun.out);
        assertTrue(leftRun.out.contains("amount is 8"), leftRun.out);
        assertFalse(leftRun.out.contains("right-body"), leftRun.out);
        assertFalse(leftRun.out.contains("right-note-zz"), leftRun.out);
        assertFalse(leftRun.out.contains("amount is 9"), leftRun.out);
        assertFalse(leftRun.out.contains("amount is 1"), leftRun.out);
        assertTrue(rightRun.out.contains("scenario PASSED Right pay"), rightRun.out);
        assertTrue(rightRun.out.contains("right-body right-note-zz"), rightRun.out);
        assertTrue(rightRun.out.contains("amount is 9"), rightRun.out);
        assertFalse(rightRun.out.contains("left-body"), rightRun.out);
        assertFalse(rightRun.out.contains("left-note-zz"), rightRun.out);
        assertFalse(rightRun.out.contains("amount is 8"), rightRun.out);
        assertFalse(rightRun.out.contains("amount is 1"), rightRun.out);
        assertTrue(mainRun.out.contains("scenario PASSED Main pay"), mainRun.out);
        assertTrue(mainRun.out.contains("amount is 1"), mainRun.out);
        assertFalse(mainRun.out.contains("left-body"), mainRun.out);
        assertFalse(mainRun.out.contains("right-body"), mainRun.out);
        assertFalse(mainRun.out.contains("left-note-zz"), mainRun.out);
        assertFalse(mainRun.out.contains("right-note-zz"), mainRun.out);
        assertFalse(mainRun.out.contains("amount is 8"), mainRun.out);
        assertFalse(mainRun.out.contains("amount is 9"), mainRun.out);
    }

    private static String sideSource(String token) {
        return """
                package tools.dscode.dolt.fixback;
                import io.cucumber.java.en.Given;
                import tools.dscode.engine.adapter.cucumber.DoltRunTracePlugin;
                public class SideSteps {
                  @Given("^dolt side records (.*)$")
                  public void records(String value) {
                    DoltRunTracePlugin.append("%s " + value);
                  }
                }
                """.formatted(token);
    }

    private Path writeFeature(String side, String amount, String note) throws Exception {
        Path file = project.resolve(side + "-feature.feature");
        String title = side.substring(0, 1).toUpperCase() + side.substring(1);
        Files.writeString(file, """
                Feature: Pay
                  @dolt-side
                  Scenario: %s pay
                    * INFO: amount is %s
                    * dolt side records <entity:Wallet#ada.note>
                    * ASSERT: "%s" equals "%s"
                """.formatted(title, amount, amount, amount));
        return file.toAbsolutePath();
    }

    private Capture runSandbox(String sandbox) throws Exception {
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        args.add("dolt");
        args.add("run");
        if (sandbox != null) args.add("--sandbox=" + sandbox);
        args.add("--tags=@dolt-side");
        args.add("--glue=tools.dscode.coredefinitions,tools.dscode.dolt.fixback");
        args.add("-Dpkb_dolt_run_classpath=" + System.getProperty("java.class.path"));
        args.add("-Dpkb_features=wd-file:" + project.resolve("features").toAbsolutePath().normalize());
        args.add("-Dpkb_loglevel=error");
        args.add("-Dpkb_parallel=1");
        args.add("-Dcucumber.execution.parallel.enabled=false");
        args.add("--project=" + project.toAbsolutePath());
        return capture(args);
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
        command.add("-Dpkb_dolt_run_classpath=" + System.getProperty("java.class.path"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tools.dscode.dolt.DoltCli");
        command.addAll(args.stream().map(String::valueOf).toList());
        Process process = new ProcessBuilder(DoltTestProcesses.java(command)).start();
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
