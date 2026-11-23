package tools.dscode.control.protocol.speed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** {@link ProcessTree#kill} ends a Java process and the child it started. */
class ProcessTreeKillTest {
    @TempDir
    Path temp;

    @Test
    void killStopsAJavaProcessAndTheChildItStarted() throws Exception {
        String java = javaBin();
        Path source = temp.resolve("Hold.java");
        Files.writeString(source, holdSource(java, source));
        Process parent = new ProcessBuilder(java, source.toAbsolutePath().normalize().toString(), "parent")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            List<ProcessHandle> children = awaitChild(parent.pid());
            ProcessTree.kill(parent.pid());
            assertTrue(parent.waitFor(10, TimeUnit.SECONDS), "parent still alive");
            for (ProcessHandle child : children) {
                assertFalse(alive(child), "child " + child.pid() + " still alive");
            }
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }

    private static String holdSource(String java, Path source) {
        return "public class Hold {\n"
                + "  public static void main(String[] args) throws Exception {\n"
                + "    if (args.length > 0 && \"parent\".equals(args[0])) {\n"
                + "      new ProcessBuilder(" + javaString(java) + ", "
                + javaString(source.toAbsolutePath().normalize().toString()) + ", \"child\")\n"
                + "          .redirectOutput(ProcessBuilder.Redirect.DISCARD)\n"
                + "          .redirectError(ProcessBuilder.Redirect.DISCARD)\n"
                + "          .start();\n"
                + "    }\n"
                + "    Thread.sleep(Long.MAX_VALUE);\n"
                + "  }\n"
                + "}\n";
    }

    private static String javaString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String javaBin() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
    }

    private static List<ProcessHandle> awaitChild(long pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            List<ProcessHandle> children = ProcessHandle.of(pid)
                    .map(handle -> handle.descendants().toList())
                    .orElse(List.of());
            if (!children.isEmpty()) return children;
            Thread.sleep(50);
        }
        fail("pid " + pid + " did not start a child");
        return List.of();
    }

    private static boolean alive(ProcessHandle handle) {
        try {
            return handle != null && handle.isAlive();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
