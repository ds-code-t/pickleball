package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalGlueCompileTest {
    @TempDir
    Path project;

    @BeforeEach
    void fresh() throws Exception {
        StoreGlueCompiler.resetWarmState();
        StoreGlueCompiler.compilerOverride = null;
        Files.writeString(project.resolve("pom.xml"), "<project></project>\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("mvnw.cmd"), """
                @echo off
                echo invoked>"%~dp0invoked.txt"
                exit /b 0
                """, StandardCharsets.UTF_8);
    }

    @AfterEach
    void release() {
        StoreGlueCompiler.resetWarmState();
        StoreGlueCompiler.compilerOverride = null;
    }

    @Test
    void theFirstCommandNamesTheWrapperAndTheNextFileUsesJavaCompiler() throws Exception {
        Path java = project.resolve("src/test/java/glue/One.java");
        write(java, """
                package glue;
                public class One {
                  public static int n = 1;
                }
                """);
        StoreGlueCompiler.IncrementalDecision first = StoreGlueCompiler.warmCompile(project, java);
        assertTrue(first.commandLine().toLowerCase().contains("mvnw"), first.commandLine());
        assertFalse(first.commandLine().contains("JavaCompiler"));
        assertFalse(first.rebuild());
        assertFalse(Files.exists(project.resolve("invoked.txt")));

        write(java, """
                package glue;
                public class One {
                  public static int n = 2;
                }
                """);
        StoreGlueCompiler.IncrementalDecision second = StoreGlueCompiler.warmCompile(project, java);
        assertEquals("JavaCompiler", second.tool());
        assertEquals("JavaCompiler", second.commandLine());
        assertFalse(second.rebuild());
        assertTrue(Files.isRegularFile(project.resolve(".pickleball/spine/classes/glue/One.class")));
        assertFalse(Files.exists(project.resolve("invoked.txt")));
    }

    @Test
    void aMissingPackageAsksForAWrapperRebuildAndDoesNotRunIt() throws Exception {
        Path java = project.resolve("src/test/java/glue/Bad.java");
        write(java, """
                package glue;
                import does.not.exist.Missing;
                public class Bad {
                  public Missing value;
                }
                """);
        StoreGlueCompiler.IncrementalDecision decision = StoreGlueCompiler.compileIncremental(project, java);
        assertTrue(decision.rebuild());
        assertEquals("wrapper", decision.tool());
        assertEquals(StoreRun.compileCommand(project), decision.command());
        assertTrue(decision.detail().contains("rebuild"), decision.detail());
        assertTrue(decision.detail().contains("does not exist") || decision.detail().toLowerCase().contains("cannot find symbol"),
                decision.detail());
        assertFalse(Files.exists(project.resolve("invoked.txt")));
        assertFalse(Files.exists(project.resolve(".pickleball/spine/wrapper-compiled")));
    }

    private static void write(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
    }
}
