package tools.dscode.engine.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.launcher.SpineCli;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceGateTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        ResourceGate.clearInjected();
        System.clearProperty("pkb_store_run_classpath");
        System.clearProperty("pkb_heap_subprocess_percent");
        System.clearProperty("pkb_heap_refuse_percent");
        System.clearProperty("pkb_worker_pool");
        WarmSpine.shared().stopChildren();
    }

    @Test
    void injectedReadingsChooseAllowSubprocessOrRefuse() throws Exception {
        Path classes = dir.resolve("classes");
        compile(classes, "up/Main.java", """
                package up;
                public class Main {
                  public static void main(String[] args) {
                    System.out.println("up");
                  }
                }
                """);
        compile(classes, "up/Hold.java", """
                package up;
                public class Hold {
                  public static void main(String[] args) throws Exception {
                    Thread.sleep(Long.MAX_VALUE);
                  }
                }
                """);
        System.setProperty("pkb_store_run_classpath", classes.toAbsolutePath().normalize().toString());

        ResourceGate.inject(() -> new ResourceGate.Reading(50, 100, 1, 2, 3));
        assertEquals(ResourceGate.Decision.ALLOW, ResourceGate.decide(ResourceGate.sample(), 0));
        assertEquals("allow", sampleJson(dir));
        assertNull(ResourceGate.admit(dir, "snap", "up.Main"));
        assertEquals(0, WarmSpine.shared().liveChildren());

        ResourceGate.inject(() -> new ResourceGate.Reading(75, 100, 4, 5, 6));
        assertEquals(ResourceGate.Decision.SUBPROCESS, ResourceGate.decide(ResourceGate.sample(), 0));
        assertEquals("subprocess", sampleJson(dir));
        Process up = ResourceGate.admit(dir, "snap", "up.Main");
        String printed = new String(up.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, up.waitFor());
        assertTrue(printed.contains("up"), printed);
        assertEquals(0, WarmSpine.shared().liveChildren());

        Process hold = ResourceGate.admit(dir, "snap", "up.Hold");
        assertTrue(hold.isAlive());
        assertEquals(1, WarmSpine.shared().liveChildren());

        ResourceGate.inject(() -> new ResourceGate.Reading(90, 100, 7, 8, 9));
        assertEquals(ResourceGate.Decision.REFUSE, ResourceGate.decide(ResourceGate.sample(), WarmSpine.shared().liveChildren()));
        assertEquals("refuse", sampleJson(dir));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> ResourceGate.admit(dir, "snap", "up.Main"));
        assertEquals(ResourceGate.REFUSED, refused.getMessage());
        assertTrue(refused.getMessage().contains("error: heap gate refused a new context"));
        assertTrue(refused.getMessage().contains("fix: spine sample"));
        assertEquals(1, WarmSpine.shared().liveChildren());

        ByteArrayOutputStream stopped = new ByteArrayOutputStream();
        assertEquals(0, SpineCli.execute(
                new String[] {"spine", "stop", "--project=" + dir},
                new PrintStream(stopped),
                new PrintStream(new ByteArrayOutputStream())));
        assertEquals(0, WarmSpine.shared().liveChildren());
        assertTrue(stopped.toString(StandardCharsets.UTF_8).contains("warm=off"));
    }

    private static String sampleJson(Path project) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SpineCli.execute(
                new String[] {"spine", "sample", "--json", "--project=" + project},
                new PrintStream(out),
                new PrintStream(err));
        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        String json = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(json.contains("\"heapUsed\":"), json);
        assertTrue(json.contains("\"heapMax\":"), json);
        assertTrue(json.contains("\"nonHeapUsed\":"), json);
        assertTrue(json.contains("\"gcTimeMs\":"), json);
        assertTrue(json.contains("\"threadCount\":"), json);
        int mark = json.indexOf("\"decision\":\"") + "\"decision\":\"".length();
        return json.substring(mark, json.indexOf('"', mark));
    }

    private static void compile(Path classes, String relative, String source) throws Exception {
        Path file = classes.resolveSibling("src").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.createDirectories(classes);
        Files.writeString(file, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Boolean ok = compiler.getTask(
                null,
                null,
                null,
                List.of("-d", classes.toString()),
                null,
                compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)
                        .getJavaFileObjectsFromPaths(List.of(file))).call();
        assertEquals(Boolean.TRUE, ok, relative);
    }
}
