package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreGlueGapTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() {
        StoreScope.clear();
        System.clearProperty("pkb_store_run_classpath");
        StoreRun.stop(project);
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void aDatabaseJavaEditCompilesOnTheNextRunWithoutExtract() throws Exception {
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("store", "hydrate", "--project=" + project).code);
        StoreScope.clear();
        Path worker = StoreRun.workerDirectory(project);
        Files.createDirectories(worker);
        Files.writeString(worker.resolve(StoreRun.FINGERPRINT), StoreRun.glueFingerprint(project));

        String source = """
                package sample;
                public class Steps {
                  public void ready() { String label = "compiledFromDb"; }
                }
                """;
        Capture put = cli("store", "glue", "put", "--path=src/test/java/sample/Steps.java",
                "--text=" + source, "--json", "--project=" + project);
        assertEquals(0, put.code, put.err);
        assertTrue(put.out.contains("glue compile pending"), put.out);
        assertFalse(Files.exists(project.resolve("src/test/java/sample/Steps.java")));

        System.setProperty("pkb_store_run_classpath", System.getProperty("java.class.path"));
        StoreRun.Outcome run = StoreRun.execute(project, java.util.List.of("-Dpkb_tags=@store-gap-matches-nothing"), false);
        Path classFile = project.resolve(".pickleball/store/sandboxes/main/classes/sample/Steps.class");
        assertTrue(Files.isRegularFile(classFile), run.out() + run.err());
        String bytes = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
        assertTrue(bytes.contains("compiledFromDb"), bytes);
        assertFalse(Files.exists(project.resolve("src/test/java/sample/Steps.java")));
        assertEquals(1, run.exit(), run.out() + run.err());
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
