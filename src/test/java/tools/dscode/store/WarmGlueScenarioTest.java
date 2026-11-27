package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.EngineOptions;
import tools.dscode.engine.PickleballEngine;
import tools.dscode.engine.model.RunResult;
import tools.dscode.engine.model.Status;
import tools.dscode.engine.runtime.WarmProcessFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edited glue must run in a JVM other than this test. The second launch compiles nothing.
 */
class WarmGlueScenarioTest {
    @TempDir
    Path project;

    @AfterEach
    void release() {
        StoreGlueCompiler.resetWarmState();
        WarmProcessFile.delete(project);
    }

    @Test
    void editedGlueRunsInTheScenarioJvmAndASecondRunCompilesNothing() throws Exception {
        Path source = project.resolve("src/test/java/glue/Mark.java");
        write(source, "1");
        Path out = project.resolve("mark.txt");
        String first = launch(out);
        assertTrue(first.contains("detail=incremental"), first);
        assertEquals("1", Files.readString(out).trim());

        write(source, "2");
        String second = launch(out);
        assertTrue(second.contains("detail=incremental"), second);
        assertEquals("2", Files.readString(out).trim());

        Path classFile = project.resolve(".pickleball/spine/classes/glue/Mark.class");
        long modified = Files.getLastModifiedTime(classFile).toMillis();
        String third = launch(out);
        assertTrue(third.contains("detail=unchanged"), third);
        assertEquals("2", Files.readString(out).trim());
        assertEquals(modified, Files.getLastModifiedTime(classFile).toMillis());
    }

    /** Child entry. Compiles through {@link StoreRun#compileGlue} and runs the engine. */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Path out = Path.of(args[2]);
        System.setProperty("pkb_warm", "on");
        System.setProperty("pkb_store", "false");
        System.setProperty("pkb_warm_packages", "glue");
        System.setProperty("pkb_heap_subprocess_percent", "101");
        WarmProcessFile.write(root, ProcessHandle.current().pid(), Instant.now());
        StoreRun.GlueCompile compiled = StoreRun.compileGlue(root);
        System.out.println("detail=" + compiled.detail());
        System.out.println("tool=" + compiled.tool());
        String path = out.toAbsolutePath().toString().replace('\\', '/');
        RunResult result = PickleballEngine.run(
                List.of("the warm scenario loads glue into \"" + path + "\""),
                EngineOptions.of(glue.ScenarioProbe.class).project(root));
        System.out.println("status=" + result.status());
        System.out.println("message=" + result.message());
        if (result.status() != Status.PASSED) System.exit(1);
    }

    private String launch(Path out) throws Exception {
        Path args = Files.createTempFile("pkb-warm-scenario-", ".args");
        args.toFile().deleteOnExit();
        List<String> lines = new ArrayList<>();
        lines.add("-Dfile.encoding=UTF-8");
        lines.add("-Dpkb_warm=on");
        lines.add("-Dpkb_store=false");
        lines.add("-Dpkb_warm_packages=glue");
        lines.add("-Dpkb_heap_subprocess_percent=101");
        lines.add("-cp");
        lines.add(quote(System.getProperty("java.class.path")));
        lines.add(WarmGlueScenarioTest.class.getName());
        lines.add("run");
        lines.add(quote(project.toAbsolutePath().normalize().toString()));
        lines.add(quote(out.toAbsolutePath().normalize().toString()));
        Files.write(args, lines, StandardCharsets.UTF_8);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "@" + args.toAbsolutePath().normalize())
                .directory(project.toFile())
                .redirectErrorStream(true)
                .start();
        String printed = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, printed);
        return printed;
    }

    private static void write(Path file, String version) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package glue;
                public class Mark {
                  public static void write(String path) throws Exception {
                    java.nio.file.Files.writeString(java.nio.file.Path.of(path), "%s");
                  }
                }
                """.formatted(version), StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' || ch == '"') quoted.append('\\');
            quoted.append(ch);
        }
        return quoted.append('"').toString();
    }
}
