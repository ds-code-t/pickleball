package tools.dscode.dolt;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.dolt.store.Binding;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoltGlueCompileTest {
    @TempDir
    Path project;

    @AfterEach
    void releaseSandbox() {
        DoltScope.clear();
        DoltGlueCompiler.compilerOverride = null;
        System.clearProperty("pkb_dolt_run_classpath");
    }

    @Test
    void aSandboxClassIsFirstOnThatClasspathAndDeleteIsRefused() throws Exception {
        String mainSource = """
                package sample;
                public class Steps {
                  public void ready() { String label = "mainOnly"; }
                }
                """;
        Path java = project.resolve("src/test/java/sample/Steps.java");
        Files.createDirectories(java.getParent());
        Files.writeString(java, mainSource);
        Files.createDirectories(project.resolve("features"));
        Files.writeString(project.resolve("features/pay.feature"), "Feature: Pay\n  Scenario: Ada\n    * INFO: amount is 1\n");
        assertEquals(0, cli("dolt", "hydrate", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "box", "--project=" + project).code);
        DoltScope.clear();
        assertEquals(0, cli("dolt", "sandbox", "create", "other", "--project=" + project).code);
        DoltScope.clear();

        String sandboxSource = """
                package sample;
                public class Steps {
                  public void ready() { String label = "sandboxOnly"; }
                }
                """;
        Capture put = cli("dolt", "glue", "put", "--path=src/test/java/sample/Steps.java",
                "--text=" + sandboxSource, "--sandbox=box", "--project=" + project);
        assertEquals(0, put.code, put.err);
        assertTrue(put.out.contains("glue compile pending"), put.out);
        DoltScope.clear();

        Path stale = project.resolve(".pickleball/dolt/sandboxes/box/classes/sample/Stale.class");
        Files.createDirectories(stale.getParent());
        Files.write(stale, new byte[] {1, 2, 3, 4});
        Capture compiled = cli("dolt", "glue", "compile", "--sandbox=box", "--project=" + project);
        assertEquals(0, compiled.code, compiled.err);
        Path boxClass = project.resolve(".pickleball/dolt/sandboxes/box/classes/sample/Steps.class");
        Path otherClass = project.resolve(".pickleball/dolt/sandboxes/other/classes/sample/Steps.class");
        assertTrue(Files.isRegularFile(boxClass), compiled.out);
        assertFalse(Files.exists(stale), "stale class was not cleared before compile");
        String bytes = new String(Files.readAllBytes(boxClass), StandardCharsets.ISO_8859_1);
        assertTrue(bytes.contains("sandboxOnly"), "compiled class does not contain the sandbox edit");
        assertFalse(Files.exists(otherClass));
        assertTrue(Files.readString(java).contains("mainOnly"));

        System.setProperty("pkb_dolt_run_classpath", System.getProperty("java.class.path"));
        DoltScope.bind(Binding.sandbox("box"));
        List<String> entries = DoltRun.classpathEntries(project, true);
        assertEquals(DoltPaths.classes(project, "box").toAbsolutePath().normalize().toString(), entries.get(0));
        DoltScope.clear();

        Capture removed = cli("dolt", "glue", "remove", "--path=src/test/java/sample/Steps.java",
                "--confirm", "--sandbox=box", "--json", "--project=" + project);
        assertEquals(1, removed.code, removed.out);
        assertTrue(removed.err.contains("Git worktree"), removed.err);

        DoltGlueCompiler.compilerOverride = () -> null;
        DoltScope.clear();
        Capture noJdk = cli("dolt", "glue", "compile", "--sandbox=box", "--project=" + project);
        assertEquals(1, noJdk.code, noJdk.out);
        assertTrue(noJdk.err.contains("JDK"), noJdk.err);
        assertTrue(noJdk.err.contains("fix:"), noJdk.err);
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
