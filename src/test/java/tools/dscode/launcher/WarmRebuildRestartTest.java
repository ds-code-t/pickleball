package tools.dscode.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.runtime.WarmProcessFile;
import tools.dscode.engine.runtime.WarmSpine;
import tools.dscode.store.StoreGlueCompiler;
import tools.dscode.store.StoreRun;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A missing-symbol rebuild stops the warm JVM from {@code spine start}, runs the wrapper, and starts that JVM again.
 */
class WarmRebuildRestartTest {
    @TempDir
    Path project;

    @BeforeEach
    void fresh() throws Exception {
        StoreGlueCompiler.resetWarmState();
        WarmSpine.shared().reset();
        SpineCli.resetForTests();
        System.setProperty("pkb_warm", "on");
        Files.writeString(project.resolve("pom.xml"), "<project></project>\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("mvnw.cmd"), """
                @echo off
                echo invoked>"%~dp0invoked.txt"
                exit /b 0
                """, StandardCharsets.UTF_8);
    }

    @AfterEach
    void release() {
        SpineCli.execute(new String[] {"spine", "stop", "--project=" + project.toAbsolutePath()}, sink(), sink());
        StoreGlueCompiler.resetWarmState();
        WarmSpine.shared().reset();
        SpineCli.resetForTests();
        System.clearProperty("pkb_warm");
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void rebuildStopsTheWarmJvmRunsTheWrapperAndStartsAgain() throws Exception {
        ByteArrayOutputStream startOut = new ByteArrayOutputStream();
        ByteArrayOutputStream startErr = new ByteArrayOutputStream();
        int started = SpineCli.execute(
                new String[] {"spine", "start", "--project=" + project.toAbsolutePath()},
                new PrintStream(startOut, true, StandardCharsets.UTF_8),
                new PrintStream(startErr, true, StandardCharsets.UTF_8));
        assertEquals(0, started, startErr.toString(StandardCharsets.UTF_8) + spineLog());
        long first = WarmProcessFile.read(project).orElseThrow().pid();
        assertNotEquals(ProcessHandle.current().pid(), first, startOut.toString(StandardCharsets.UTF_8));
        assertTrue(WarmProcessFile.processAlive(first), "warm JVM from spine start is not alive\n" + spineLog());

        write(project.resolve("src/test/java/glue/Bad.java"), """
                package glue;
                import does.not.exist.Missing;
                public class Bad {
                  public Missing value;
                }
                """);

        StoreRun.GlueCompile compiled = StoreRun.compileGlue(project);
        assertEquals(0, compiled.exit(), compiled.detail());
        assertTrue(compiled.rebuild(), compiled.detail());
        assertEquals("wrapper", compiled.tool());
        assertTrue(Files.isRegularFile(project.resolve("invoked.txt")));
        assertTrue(compiled.detail().contains("stopped the warm JVM"), compiled.detail());
        assertTrue(compiled.detail().contains("ran the wrapper compile"), compiled.detail());
        assertTrue(compiled.detail().contains("started the warm JVM again"), compiled.detail());
        long second = WarmProcessFile.read(project).orElseThrow().pid();
        assertNotEquals(first, second, compiled.detail());
        assertTrue(WarmProcessFile.processAlive(second), compiled.detail() + "\n" + spineLog());
        assertFalse(WarmProcessFile.processAlive(first), "previous warm JVM " + first + " is still alive");
    }

    private String spineLog() throws Exception {
        StringBuilder text = new StringBuilder();
        for (String name : new String[] {"warm.err.log", "warm.out.log", "java.args"}) {
            Path file = project.resolve(".pickleball/spine").resolve(name);
            text.append("--- ").append(name).append(" ---\n");
            if (Files.isRegularFile(file)) text.append(Files.readString(file, StandardCharsets.UTF_8));
        }
        return text.toString();
    }

    private static void write(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }

    private static PrintStream sink() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
