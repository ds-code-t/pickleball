package tools.dscode.engine.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.engine.context.ExecutionContext;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WarmClassLoaderTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        System.clearProperty("pkb_warm");
        WarmSpine.shared().reset();
    }

    @Test
    void reloadResetsStaticStateAndSpineTypesStayParentFirst() throws Exception {
        Path classes = dir.resolve("classes");
        Files.createDirectories(classes);
        compile(classes, "warmfix/Counter.java", """
                package warmfix;
                public class Counter {
                  public static int n;
                  static { n = 1; }
                }
                """);
        compile(classes, "tools/dscode/engine/runtime/WarmClassLoader.java", """
                package tools.dscode.engine.runtime;
                public class WarmClassLoader {
                  public static final int STUB = 7;
                }
                """);
        URL url = classes.toUri().toURL();
        List<String> packages = List.of("warmfix");
        WarmClassLoader first = WarmSpine.shared().loader("snap", new URL[] {url}, packages);
        Class<?> type = Class.forName("warmfix.Counter", true, first);
        var field = type.getField("n");
        assertEquals(1, field.getInt(null));
        field.setInt(null, 5);

        WarmClassLoader second = WarmSpine.shared().replace("snap",
                new WarmClassLoader(new URL[] {url}, WarmClassLoader.spineLoader(), packages));
        assertTrue(first.isClosed());
        IllegalStateException closed = assertThrows(IllegalStateException.class, () -> first.loadClass("warmfix.Counter"));
        assertEquals("warm class loader is closed", closed.getMessage());

        Class<?> reloaded = Class.forName("warmfix.Counter", true, second);
        assertEquals(1, reloaded.getField("n").getInt(null));
        assertSame(WarmClassLoader.class, second.loadClass(WarmClassLoader.class.getName()));
    }

    @Test
    void closeDetachesWithoutClosingTheLoader() {
        System.setProperty("pkb_warm", "off");
        try (ExecutionContext cold = ExecutionContext.open(null)) {
            assertNull(cold.warmLoader());
        }
        System.setProperty("pkb_warm", "on");
        ExecutionContext warm = ExecutionContext.open(null);
        WarmClassLoader loader = warm.warmLoader();
        assertNotNull(loader);
        String id = warm.id();
        warm.close();
        assertFalse(loader.isClosed());
        assertNull(WarmSpine.shared().get(id));
    }

    private static void compile(Path classes, String relative, String source) throws Exception {
        Path file = dirSource(classes, relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required");
        Boolean ok = compiler.getTask(
                null,
                null,
                null,
                List.of("-d", classes.toString(), "-classpath", classes.toString()),
                null,
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)
                        .getJavaFileObjectsFromPaths(List.of(file))).call();
        assertEquals(Boolean.TRUE, ok, relative);
    }

    private static Path dirSource(Path classes, String relative) {
        return classes.resolveSibling("src").resolve(relative);
    }
}
