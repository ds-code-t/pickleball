package tools.dscode.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlueStaticScanTest {
    private static final String MUTABLE =
            "warn: static mutable field sample.Mutable.n will survive a warm reload; move it to scenario state";

    @TempDir
    Path project;

    @AfterEach
    void clearScope() {
        StoreScope.clear();
        System.clearProperty("pkb_store_log");
    }

    @Test
    void mutableStaticWarnsAndHydrateStillSucceeds() throws Exception {
        Path sourceRoot = project.resolve("src/test/java/sample");
        Files.createDirectories(sourceRoot);
        Files.writeString(sourceRoot.resolve("Mutable.java"), """
                package sample;
                import io.cucumber.java.en.Given;
                public class Mutable {
                    static int n;
                    @Given("mutable field")
                    public void step() {}
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("Constant.java"), """
                package sample;
                import io.cucumber.java.en.Given;
                public class Constant {
                    static final String NAME = "a";
                    @Given("constant field")
                    public void step() {}
                }
                """, StandardCharsets.UTF_8);
        compile(sourceRoot.resolve("Mutable.java"), sourceRoot.resolve("Constant.java"));
        System.setProperty("pkb_store_log", "hydrate");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        StoreHydrator.Result result;
        try {
            result = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
        } finally {
            System.setErr(previous);
        }
        assertEquals(0, result.conflicts());
        assertTrue(result.line().startsWith("hydrated"), result.line());
        String err = captured.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains(MUTABLE), err);
        assertFalse(err.contains("sample.Constant.NAME"), err);
        String log = Files.readString(project.resolve(".pickleball/store/store.log"), StandardCharsets.UTF_8);
        assertTrue(log.contains(MUTABLE), log);
        assertTrue(log.contains("glue"), log);
    }

    private void compile(Path... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "the test JDK has no compiler");
        Path output = project.resolve("build/classes/java/test");
        Files.createDirectories(output);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            files.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(output.toFile()));
            java.io.File[] units = new java.io.File[sources.length];
            for (int index = 0; index < sources.length; index++) units[index] = sources[index].toFile();
            Boolean ok = compiler.getTask(
                    null,
                    files,
                    null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-proc:none"),
                    null,
                    files.getJavaFileObjects(units)).call();
            assertEquals(Boolean.TRUE, ok);
        }
    }
}
