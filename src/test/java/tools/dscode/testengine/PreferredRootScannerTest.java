package tools.dscode.testengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferredRootScannerTest {
    @TempDir
    Path root;

    @Test
    void headerNamesTheConcreteRunnerAndSkipsSiblings() throws Exception {
        write(root.resolve("demo/Other.class"), klass("demo/Other", "java/lang/Object", 0x0021));
        write(root.resolve("demo/AbstractRun.class"), klass(
                "demo/AbstractRun", "tools/dscode/testengine/PickleballRunner", 0x0401
        ));
        write(root.resolve("demo/App.class"), klass(
                "demo/App", "tools/dscode/testengine/PickleballRunner", 0x0021
        ));
        write(root.resolve("demo/steps/One.class"), klass("demo/steps/One", "java/lang/Object", 0x0021));
        write(root.resolve("note.txt"), new byte[] {1});

        PreferredRootScanner.Header header = PreferredRootScanner.readHeader(root.resolve("demo/App.class"));
        assertEquals("demo/App", header.name());
        assertEquals("tools/dscode/testengine/PickleballRunner", header.superName());
        assertTrue(header.concrete());
        assertEquals(java.util.List.of("demo/App"), PreferredRootScanner.concreteRunnerNames(root));
        assertEquals(java.util.Set.of("demo.steps"), PreferredRootScanner.stepPackages(root));
        assertNull(PreferredRootScanner.parse(new byte[] {1, 2, 3}));
        Path self = Path.of(PreferredRootScanner.class.getResource("PreferredRootScanner.class").toURI());
        PreferredRootScanner.Header real = PreferredRootScanner.readHeader(self);
        assertEquals("tools/dscode/testengine/PreferredRootScanner", real.name());
        assertEquals("java/lang/Object", real.superName());
        assertTrue(real.concrete());
    }

    @Test
    void twoConcreteRunnersAreAnError() throws Exception {
        write(root.resolve("a/One.class"), klass("a/One", "tools/dscode/testengine/PickleballRunner", 0x0021));
        write(root.resolve("b/Two.class"), klass("b/Two", "tools/dscode/testengine/PickleballRunner", 0x0021));
        assertEquals(java.util.List.of("a/One", "b/Two"), PreferredRootScanner.concreteRunnerNames(root));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> PreferredRootScanner.loadConcreteRunner(root)
        );
        assertTrue(failure.getMessage().contains("a.One"), failure.getMessage());
        assertTrue(failure.getMessage().contains("b.Two"), failure.getMessage());
    }

    @Test
    void headerScanOfCompiledClassesStaysADirectoryWalk() throws Exception {
        Path classes = Path.of("build/classes/java/main");
        if (!Files.isDirectory(classes)) return;
        long files;
        try (var paths = Files.walk(classes)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".class")).count();
        }
        long started = System.nanoTime();
        java.util.List<String> runners = PreferredRootScanner.concreteRunnerNames(classes);
        java.util.Set<String> packages = PreferredRootScanner.stepPackages(classes);
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        assertTrue(elapsed < 60_000L, "header scan " + elapsed + " ms");
        System.out.println("windows-shaped-scan files=" + files
                + " runners=" + runners.size()
                + " stepPackages=" + packages.size()
                + " ms=" + elapsed);
    }

    private static void write(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** Constant pool plus this/super. The scanner stops before methods. */
    private static byte[] klass(String name, String superName, int access) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(65);
        out.writeShort(5);
        utf(out, name);
        out.writeByte(7);
        out.writeShort(1);
        utf(out, superName);
        out.writeByte(7);
        out.writeShort(3);
        out.writeShort(access);
        out.writeShort(2);
        out.writeShort(4);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(0);
        out.flush();
        return raw.toByteArray();
    }

    private static void utf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1);
        out.writeShort(bytes.length);
        out.write(bytes);
    }
}