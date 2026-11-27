package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.match.JavaStepHost;
import tools.dscode.engine.runtime.WarmClassLoader;
import tools.dscode.engine.runtime.WarmProcessFile;
import tools.dscode.engine.runtime.WarmSpine;
import tools.dscode.launcher.SpineCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compile method {@code StoreRun.execute} calls when glue changes.
 */
class WarmGlueCompilePathTest {
    @TempDir
    Path project;

    @BeforeEach
    void fresh() throws Exception {
        StoreGlueCompiler.resetWarmState();
        WarmSpine.shared().reset();
        System.clearProperty("pkb_warm");
        Files.writeString(project.resolve("pom.xml"), "<project></project>\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("mvnw.cmd"), """
                @echo off
                echo invoked>"%~dp0invoked.txt"
                exit /b 0
                """, StandardCharsets.UTF_8);
    }

    @AfterEach
    void release() {
        SpineCli.execute(
                new String[] {"spine", "stop", "--project=" + project.toAbsolutePath()},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));
        StoreGlueCompiler.resetWarmState();
        WarmSpine.shared().reset();
        WarmProcessFile.delete(project);
        System.clearProperty("pkb_warm");
    }

    @Test
    void warmJvmCompilesChangedSourcesAndReloadsTheLoader() throws Exception {
        System.setProperty("pkb_warm", "on");
        WarmProcessFile.write(project, ProcessHandle.current().pid(), Instant.now());
        Path java = project.resolve("src/test/java/glue/One.java");
        write(java, source(1));

        StoreRun.GlueCompile first = StoreRun.compileGlue(project);
        assertEquals("JavaCompiler", first.tool());
        assertFalse(first.rebuild());
        assertEquals(0, first.exit());
        Path classFile = project.resolve(".pickleball/spine/classes/glue/One.class");
        assertTrue(Files.isRegularFile(classFile));
        assertFalse(Files.exists(project.resolve("invoked.txt")));
        ClassLoader loaded = JavaStepHost.selectLoader("glue.One", project);
        assertInstanceOf(WarmClassLoader.class, loaded);

        long size = Files.size(classFile);
        StoreRun.GlueCompile unchanged = StoreRun.compileGlue(project);
        assertEquals("JavaCompiler", unchanged.tool());
        assertFalse(unchanged.rebuild());
        assertEquals(size, Files.size(classFile));
        assertSame(loaded, JavaStepHost.selectLoader("glue.One", project));

        write(java, source(2));
        StoreRun.GlueCompile changed = StoreRun.compileGlue(project);
        assertEquals("JavaCompiler", changed.tool());
        assertFalse(changed.rebuild());
        assertFalse(Files.exists(project.resolve("invoked.txt")));
        ClassLoader reloaded = JavaStepHost.selectLoader("glue.One", project);
        assertInstanceOf(WarmClassLoader.class, reloaded);
        assertNotSame(loaded, reloaded);
        Class<?> type = Class.forName("glue.One", true, reloaded);
        assertEquals(2, type.getField("n").get(null));
    }

    @Test
    void warmOffRunsTheWrapper() throws Exception {
        write(project.resolve("src/test/java/glue/One.java"), source(1));
        StoreRun.GlueCompile compiled = StoreRun.compileGlue(project);
        assertEquals("wrapper", compiled.tool());
        assertEquals(0, compiled.exit());
        assertTrue(compiled.commandLine().toLowerCase().contains("mvnw"), compiled.commandLine());
        assertTrue(Files.isRegularFile(project.resolve("invoked.txt")));
        assertFalse(Files.exists(project.resolve(".pickleball/spine/classes/glue/One.class")));
    }

    @Test
    void missingTypeReportsRebuildAndRunsTheWrapper() throws Exception {
        System.setProperty("pkb_warm", "on");
        WarmProcessFile.write(project, ProcessHandle.current().pid(), Instant.now());
        write(project.resolve("src/test/java/glue/Bad.java"), """
                package glue;
                import does.not.exist.Missing;
                public class Bad {
                  public Missing value;
                }
                """);
        StoreRun.GlueCompile compiled = StoreRun.compileGlue(project);
        assertTrue(compiled.rebuild());
        assertEquals("wrapper", compiled.tool());
        assertTrue(compiled.detail().contains("rebuild"), compiled.detail());
        assertTrue(compiled.detail().contains("does not exist") || compiled.detail().toLowerCase().contains("cannot find symbol"),
                compiled.detail());
        assertTrue(Files.isRegularFile(project.resolve("invoked.txt")));
    }

    private static String source(int n) {
        return """
                package glue;
                public class One {
                  public static int n = %d;
                }
                """.formatted(n);
    }

    private static void write(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
