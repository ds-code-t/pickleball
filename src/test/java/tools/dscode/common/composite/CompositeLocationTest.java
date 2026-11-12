package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.dscode.control.protocol.DirectControlProvider;

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
        Path project = temp.resolve("project");
        Path home = temp.resolve("home");
        CompositeRuntime.use(project, Map.of(), new ClassLoader(null) {
        }, home);
        assertTrue(CompositeBinaryLocator.locate().isEmpty());
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
    void primaryFollowsTheInjectedBinaryNotTheHost() throws Exception {
        Path project = temp.resolve("project");
        Path home = temp.resolve("home");
        ClassLoader loader = new ClassLoader(null) {
        };
        CompositeRuntime.use(project, Map.of(), loader, home);
        assertFalse(CompositeRuntime.primary());
        String missing = CompositeLog.lastFallbackForTests();
        assertTrue(missing != null && missing.contains("error:") && missing.contains("fix:"), missing);

        CompositeRuntime.resetForTests();
        Path binary = stub(temp.resolve("present").resolve("composite"));
        CompositeRuntime.use(
                project,
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                loader,
                home
        );
        assertTrue(CompositeRuntime.primary());
        assertEquals("2.4.0", CompositeRuntime.current().version());
        assertEquals(binary.toAbsolutePath().normalize(), CompositeRuntime.current().binary());
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
        Optional<Path> home = CompositeBinaryLocator.locate(project, Map.of(), new ClassLoader(null) {
        });
        assertEquals(sibling.toAbsolutePath().normalize(), home.orElseThrow());
    }

    @Test
    void javaEnginePrecedesTheRustSibling() throws Exception {
        Path project = temp.resolve("repo").resolve("pickleball");
        Path sibling = temp.resolve("repo").resolve("dom-composite").resolve("target").resolve("release").resolve("composite");
        executable(sibling);
        ClassLoader loader = CompositeLocationTest.class.getClassLoader();
        Optional<Path> javaEngine = CompositeBinaryLocator.locate(project, Map.of(), loader);
        assertTrue(javaEngine.isPresent(), "java engine");
        String script = Files.readString(javaEngine.orElseThrow());
        assertTrue(script.contains("dev.domengine.cli.DomEngineMain"), script);
        assertFalse(javaEngine.orElseThrow().equals(sibling.toAbsolutePath().normalize()));
        assertEquals("3.0.0", CompositeRuntime.openProbe(javaEngine.orElseThrow()));
    }

    @Test
    void explicitBinAndRustFlagBeatTheJavaEngine() throws Exception {
        Path binary = stub(temp.resolve("explicit").resolve("composite"));
        ClassLoader loader = CompositeLocationTest.class.getClassLoader();
        Optional<Path> named = CompositeBinaryLocator.locate(
                temp,
                Map.of(DirectControlProvider.BIN_ENV, binary.toString()),
                loader
        );
        assertEquals(binary.toAbsolutePath().normalize(), named.orElseThrow());

        Path project = temp.resolve("rust-repo").resolve("pickleball");
        Path sibling = temp.resolve("rust-repo").resolve("dom-composite").resolve("target").resolve("release").resolve("composite");
        executable(sibling);
        Optional<Path> rust = CompositeBinaryLocator.locate(
                project,
                Map.of("PKB_COMPOSITE_ENGINE", "rust"),
                loader
        );
        assertEquals(sibling.toAbsolutePath().normalize(), rust.orElseThrow());
    }

    @Test
    void daltonRecordsTheJavaEngineWhenEnabled() throws Exception {
        Path project = temp.resolve("dalton-project");
        Files.createDirectories(project);
        CompositeRuntime.use(
                project,
                Map.of("PKB_DALTON", "true"),
                CompositeLocationTest.class.getClassLoader(),
                temp.resolve("home")
        );
        assertTrue(CompositeRuntime.primary(), CompositeLog.lastFallbackForTests());
        assertEquals("3.0.0", CompositeRuntime.current().version());
        String note = Files.readString(project.resolve(".pickleball/dalton/engine-sessions.log"));
        assertTrue(note.contains("engine=3.0.0"), note);
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
