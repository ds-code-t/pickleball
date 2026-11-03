package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeLocationTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void missingBinaryIsEmptyAndFallsBackOnce() {
        Optional<Path> found = CompositeBinaryLocator.locate(temp.resolve("project"), Map.of(), ClassLoader.getSystemClassLoader());
        assertTrue(found.isEmpty());
        assertFalse(CompositeRuntime.primary());
        String line = CompositeLog.lastFallbackForTests();
        assertTrue(line != null && line.contains("error:"), line);
        assertTrue(line.contains("fix:"), line);
        CompositeRuntime.resetForTests();
        System.setProperty(CompositeMode.KEY, "off");
        assertFalse(CompositeRuntime.primary());
        assertTrue(CompositeLog.lastFallbackForTests() == null);
    }

    @Test
    void propertyAndSiblingCheckoutResolve() throws Exception {
        Path binary = executable(temp.resolve("bin").resolve("composite"));
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        Optional<Path> named = CompositeBinaryLocator.locate(temp, Map.of(), ClassLoader.getSystemClassLoader());
        assertEquals(binary.toAbsolutePath().normalize(), named.orElseThrow());

        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        Path project = temp.resolve("repo").resolve("pickleball");
        Path sibling = temp.resolve("repo").resolve("dom-composite").resolve("target").resolve("release").resolve("composite");
        executable(sibling);
        Optional<Path> home = CompositeBinaryLocator.locate(project, Map.of(), ClassLoader.getSystemClassLoader());
        assertEquals(sibling.toAbsolutePath().normalize(), home.orElseThrow());
    }

    @Test
    void classifierJarExtractsABinary() throws Exception {
        Path jar = temp.resolve("composite-native-2.4.0-linux-x64.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("composite"));
            out.write("#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Optional<Path> extracted = CompositeBinaryLocator.extractJarForTests(jar, "linux-x64");
        assertTrue(extracted.isPresent(), "extracted");
        assertTrue(Files.isRegularFile(extracted.get()));
        assertTrue(Files.isExecutable(extracted.get()));
    }

    @Test
    void clientOpensWhenTheBinarySpeaksStdio() throws Exception {
        Path binary = stub(temp.resolve("composite"));
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertEquals("2.4.0", CompositeRuntime.openProbe(binary));
        assertTrue(CompositeRuntime.primary());
        assertEquals("2.4.0", CompositeRuntime.current().version());
        assertTrue(CompositeRuntime.client() != null);
        assertFalse(CompositeRuntime.client().isClosed());
    }

    private static Path executable(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }

    private static Path stub(Path path) throws Exception {
        String script = """
                #!/usr/bin/env python3
                import json, sys
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    if "version" in argv:
                        body = {"version": "2.4.0"}
                    elif "create" in argv:
                        body = {"ok": True, "id": "sess-1", "launched": False}
                    else:
                        body = {"ok": True}
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": 0,
                        "stdout": json.dumps(body) + "\\n",
                        "stderr": ""
                    }) + "\\n")
                    sys.stdout.flush()
                """;
        Files.createDirectories(path.getParent());
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }
}
