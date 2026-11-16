package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.store.sql.Binding;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreSandboxWorkerTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() throws Exception {
        StoreScope.clear();
        StoreScope.bind(Binding.sandbox("alpha"));
        StoreRun.stop(project);
        StoreScope.clear();
        StoreRun.stop(project);
        System.clearProperty("pkb_store_run_classpath");
        StoreTestProcesses.release(project);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void eachSandboxHasItsOwnWorkerAndReusesIt() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), """
                Feature: Pay
                  @store-worker
                  Scenario: Ada
                    * INFO: amount is 1
                    * ASSERT: "1" equals "1"
                """);
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        assertEquals(0, cli("store", "sandbox", "create", "alpha", "--project=" + project).code);
        StoreScope.clear();
        String fingerprint = StoreRun.glueFingerprint(project);
        Files.createDirectories(StoreRun.workerDirectory(project));
        Files.writeString(StoreRun.workerDirectory(project).resolve(StoreRun.FINGERPRINT), fingerprint);
        Path alphaWorker = project.resolve(".pickleball/store/sandboxes/alpha/worker");
        Files.createDirectories(alphaWorker);
        Files.writeString(alphaWorker.resolve(StoreRun.FINGERPRINT), fingerprint);
        System.setProperty("pkb_store_run_classpath", System.getProperty("java.class.path"));
        List<String> selection = workerSelection();

        StoreRun.Outcome main = StoreRun.execute(project, selection, false);
        assertEquals(0, main.exit(), main.out() + main.err());
        assertFalse(main.reused());
        long mainPid = pid(project.resolve(".pickleball/store/sandboxes/main/worker/warm.txt"));

        Capture first = child();
        assertEquals(0, first.code, first.out + first.err);
        assertTrue(first.out.contains("reused=false"), first.out + first.err);
        assertFalse(first.out.contains("reused=true"), first.out + first.err);
        assertTrue(first.out.contains("scenario PASSED Ada"), first.out);
        long alphaPid = pid(alphaWorker.resolve("warm.txt"));
        assertNotEquals(mainPid, alphaPid);

        StoreRun.Outcome again = StoreRun.execute(project, selection, false);
        assertEquals(0, again.exit(), again.out() + again.err());
        assertTrue(again.reused(), again.out() + again.err());
        assertEquals(mainPid, pid(project.resolve(".pickleball/store/sandboxes/main/worker/warm.txt")));

        Capture second = child();
        assertEquals(0, second.code, second.out + second.err);
        assertTrue(second.out.contains("reused=true"), second.out + second.err);
        assertFalse(second.out.contains("reused=false"), second.out + second.err);
        assertEquals(alphaPid, pid(alphaWorker.resolve("warm.txt")));
    }

    private List<String> workerSelection() {
        return List.of(
                "-Dpkb_tags=@store-worker",
                "-Dpkb_glue=tools.dscode.coredefinitions",
                "-Dpkb_features=wd-file:" + project.resolve("features").toAbsolutePath().normalize(),
                "-Dpkb_loglevel=error",
                "-Dpkb_parallel=1",
                "-Dcucumber.execution.parallel.enabled=false");
    }

    private Capture child() throws Exception {
        List<String> command = new ArrayList<>();
        command.add("-Dpkb_store_run_classpath=" + System.getProperty("java.class.path"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("tools.dscode.store.StoreCli");
        command.add("store");
        command.add("run");
        command.add("--sandbox=alpha");
        command.add("--tags=@store-worker");
        command.add("--glue=tools.dscode.coredefinitions");
        command.add("-Dpkb_features=wd-file:" + project.resolve("features").toAbsolutePath().normalize());
        command.add("-Dpkb_loglevel=error");
        command.add("-Dpkb_parallel=1");
        command.add("-Dcucumber.execution.parallel.enabled=false");
        command.add("--project=" + project.toAbsolutePath());
        Process process = new ProcessBuilder(StoreTestProcesses.java(command)).start();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread stdout = Thread.ofVirtual().start(() -> drain(process.getInputStream(), out));
        Thread stderr = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), err));
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        stdout.join(5_000);
        stderr.join(5_000);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("timed out " + out + err);
        }
        return new Capture(process.exitValue(), out.toString(), err.toString());
    }

    private static long pid(Path warm) throws Exception {
        return Long.parseLong(Files.readAllLines(warm).get(0).trim());
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
