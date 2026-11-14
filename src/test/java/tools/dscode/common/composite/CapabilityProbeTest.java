package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed capabilities read is not cached. The next call probes again. */
class CapabilityProbeTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.DIR_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void secondReadSeesTheRealActionList() throws Exception {
        Path log = temp.resolve("calls.log");
        Path binary = stub(temp.resolve("composite"), log, "2.4.0", "", "once");
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        assertTrue(ElementOperations.primary());
        assertFalse(DomCompositeAdapter.get().actionListed("hover"));
        assertTrue(DomCompositeAdapter.get().actionListed("hover"));
    }

    @Test
    void javaEngineFailureUsesTheJavaDesktopVerbs() throws Exception {
        Path log = temp.resolve("java.log");
        Path binary = stub(temp.resolve("java-composite"), log, "3.0.0", "domengine", "always");
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        ElementOperations.JsonDesktop first = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        ElementOperations.JsonDesktop second = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        ElementOperations.JsonDesktop third = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(1, third.size());
        int probes = 0;
        for (String line : Files.readString(log).split("\n")) {
            if (line.equals("capabilities") || line.endsWith(" capabilities") || line.contains(" capabilities")) probes++;
        }
        assertEquals(2, probes, Files.readString(log));
    }

    @Test
    void rustFailureKeepsTheTwoFourDesktopSet() throws Exception {
        Path log = temp.resolve("rust.log");
        Path binary = stub(temp.resolve("rust-composite"), log, "2.4.0", "", "always");
        System.setProperty(CompositeBinaryLocator.BIN_KEY, binary.toString());
        System.setProperty(CompositeMode.KEY, "on");
        System.setProperty(CompositeLog.DIR_KEY, temp.resolve("logs").toString());
        ElementOperations.JsonDesktop hit = ElementOperations.desktop("click", ElementPhrase.of("Button", "Save"));
        assertEquals(1, hit.size());
        String error = CompositeLog.lastErrorForTests();
        assertTrue(error == null || !error.contains("composite-native"), String.valueOf(error));
        assertTrue(Files.readString(log).contains("desktop"), Files.readString(log));
    }

    private static Path stub(Path path, Path log, String version, String name, String fail) throws Exception {
        String script = """
                #!/usr/bin/env python3
                import json, sys
                log = %s
                version = %s
                name = %s
                fail = %s
                caps = 0
                def note(argv):
                    with open(log, "a", encoding="utf-8") as handle:
                        handle.write(" ".join(argv) + "\\n")
                hit = {"ok": True, "composite": [{
                    "id": "main:1", "text": "Save", "role": "button",
                    "bounds": {"x": 1, "y": 2, "width": 10, "height": 8},
                    "attributes": {"id": "save"}, "states": ["enabled"], "source": "Desktop"
                }]}
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    note(argv)
                    joined = " ".join(argv)
                    if "version" in argv and "capabilities" not in argv:
                        body = {"name": name, "version": version}
                        exit_code = 0
                        err = ""
                    elif "capabilities" in argv:
                        caps += 1
                        if fail == "always" or caps == 1:
                            body = None
                            exit_code = 1
                            err = json.dumps({"ok": False, "error": "boom", "code": "failed"})
                        else:
                            body = {"ok": True, "name": name, "version": version,
                                    "desktop": ["list", "attach", "walk", "query", "resolve", "close", "click", "type"],
                                    "actions": ["click", "type", "scroll", "hover"],
                                    "query_flags": ["limit", "nth"]}
                            exit_code = 0
                            err = ""
                    else:
                        body = hit if "desktop" in argv else {"ok": True, "id": "sess-1", "launched": False}
                        exit_code = 0
                        err = ""
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": exit_code,
                        "stdout": "" if body is None else json.dumps(body) + "\\n",
                        "stderr": err
                    }) + "\\n")
                    sys.stdout.flush()
                """.formatted(quote(log.toString()), quote(version), quote(name), quote(fail));
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        Files.writeString(path, script);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
