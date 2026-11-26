package tools.dscode.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueEditTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path project;

    private String previousStore;

    @BeforeEach
    void filePathIsTheOptOutTheseAssertionsCover() {
        previousStore = System.getProperty("pkb_store");
        System.setProperty("pkb_store", "false");
    }

    @AfterEach
    void restoreStoreFlag() {
        if (previousStore == null) System.clearProperty("pkb_store");
        else System.setProperty("pkb_store", previousStore);
    }

    @Test
    void javaAnnotationsMustBeSingleLiterals() throws Exception {
        String constant = """
                package sample;
                import io.cucumber.java.en.Given;
                public class Steps {
                  static final String STEP = "ready";
                  @Given(STEP)
                  public void ready() {}
                }
                """;
        Capture bad = run("store", "glue", "put", "--path=src/test/java/sample/Steps.java",
                "--text=" + constant, "--json", "--project=" + project);
        assertEquals(1, bad.code, bad.err);
        assertTrue(bad.err.contains("string literal"), bad.err);
        assertFalse(Files.exists(project.resolve("src/test/java/sample/Steps.java")));

        String plus = constant.replace("@Given(STEP)", "@Given(\"rea\" + \"dy\")");
        Capture joined = run("store", "glue", "put", "--path=src/test/java/sample/Steps.java",
                "--text=" + plus, "--json", "--project=" + project);
        assertEquals(1, joined.code, joined.err);
        assertFalse(Files.exists(project.resolve("src/test/java/sample/Steps.java")));

        String literal = constant.replace("@Given(STEP)", "@Given(\"ready\")");
        Capture ok = run("store", "glue", "put", "--path=src/test/java/sample/Steps.java",
                "--text=" + literal, "--json", "--project=" + project);
        assertEquals(0, ok.code, ok.err);
        assertTrue(Files.exists(project.resolve("src/test/java/sample/Steps.java")));
        assertTrue(StoreJson.read(ok.out).get("message").asText().contains("no project wrapper"), ok.out);
    }

    @Test
    void compileFailureKeepsTheWorkerAndSuccessRestartsIt() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows) {
            Files.writeString(project.resolve("mvnw.cmd"), "@echo off\r\nif exist fail.marker exit /b 1\r\nexit /b 0\r\n");
        }
        Path wrapper = project.resolve("mvnw");
        Files.writeString(wrapper, "#!/bin/sh\nif [ -f fail.marker ]; then exit 1; fi\nexit 0\n");
        wrapper.toFile().setExecutable(true);
        Files.writeString(project.resolve("fail.marker"), "1\n");
        Path state = project.resolve(".pickleball/store/sandboxes/main/worker");
        Files.createDirectories(state);
        Files.writeString(state.resolve("glue.fingerprint"), "old\n");
        Files.writeString(state.resolve("warm.txt"), "999999\n1\nold\n");
        String source = """
                package sample;
                import io.cucumber.java.en.Given;
                public class Steps {
                  @Given("ready")
                  public void ready() {}
                }
                """;
        Capture failed = run("store", "glue", "put", "--path=src/test/java/sample/Steps.java",
                "--text=" + source, "--json", "--project=" + project);
        assertEquals(0, failed.code, failed.err);
        assertTrue(StoreJson.read(failed.out).get("message").asText().contains("prior worker kept"), failed.out);
        assertEquals("old", Files.readString(state.resolve("glue.fingerprint")).trim());
        assertTrue(Files.exists(state.resolve("warm.txt")));

        Files.delete(project.resolve("fail.marker"));
        Capture ok = run("store", "glue", "put", "--path=src/test/java/sample/Steps.java",
                "--text=" + source.replace("ready", "set"), "--json", "--project=" + project);
        assertEquals(0, ok.code, ok.err);
        assertTrue(StoreJson.read(ok.out).get("message").asText().contains("worker restarted"), ok.out);
        assertFalse(Files.exists(state.resolve("warm.txt")));
        String fingerprint = Files.readString(state.resolve("glue.fingerprint")).trim();
        assertFalse(fingerprint.isBlank());
        assertFalse("old".equals(fingerprint));
    }

    @Test
    void javaScriptAndTypeScriptAreSourceOnly() throws Exception {
        Capture js = run("store", "glue", "put", "--path=glue/js/steps.js",
                "--text=export function ready() {}\n", "--json", "--project=" + project);
        assertEquals(0, js.code, js.err);
        assertTrue(StoreJson.read(js.out).get("message").asText().contains("js/ts step definitions refreshed"), js.out);
        Capture ts = run("store", "glue", "put", "--path=glue/js/steps.ts",
                "--text=export function ready(): void {}\n", "--json", "--project=" + project);
        assertEquals(0, ts.code, ts.err);
        Capture listed = run("store", "glue", "list", "--json", "--project=" + project);
        String items = StoreJson.read(listed.out).get("items").toString();
        assertTrue(items.contains("glue/js/steps.js"), items);
        assertTrue(items.contains("glue/js/steps.ts"), items);
        assertFalse(Files.exists(project.resolve(".pickleball/store/sandboxes/main/worker/glue.fingerprint")));
    }

    @Test
    void javaGlueRemoveDeletesTheFile() throws Exception {
        String source = """
                package sample;
                import io.cucumber.java.en.Given;
                public class Steps {
                  @Given("ready")
                  public void ready() {}
                }
                """;
        String path = "src/test/java/sample/Steps.java";
        Capture put = run("store", "glue", "put", "--path=" + path,
                "--text=" + source, "--json", "--project=" + project);
        assertEquals(0, put.code, put.err);
        assertTrue(Files.isRegularFile(project.resolve(path)), put.out);

        Capture removed = run("store", "glue", "remove", "--path=" + path,
                "--confirm", "--json", "--project=" + project);
        assertEquals(0, removed.code, removed.err);
        var body = StoreJson.read(removed.out);
        assertTrue(body.get("ok").asBoolean(), removed.out);
        assertEquals("glue", body.get("family").asText());
        assertEquals("remove", body.get("action").asText());
        assertEquals(path, body.get("path").asText());
        assertEquals("deleted", body.get("message").asText());
        assertTrue(body.get("applied").asBoolean(), removed.out);
        assertFalse(Files.exists(project.resolve(path)));

        Capture listed = run("store", "glue", "list", "--json", "--project=" + project);
        assertEquals(0, listed.code, listed.err);
        assertFalse(listed.out.contains(path), listed.out);

        Capture again = run("store", "glue", "remove", "--path=" + path,
                "--confirm", "--json", "--project=" + project);
        assertEquals(1, again.code, again.out + again.err);
        assertTrue(again.err.contains("not found"), again.err);
        assertFalse(StoreJson.read(again.out).get("ok").asBoolean(), again.out);
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = StoreCli.execute(args,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
