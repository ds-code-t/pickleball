package tools.dscode.common.composite;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.Response;
import tools.dscode.common.reporting.diagnostic.DiagnosticRuntime;
import tools.dscode.control.protocol.DirectControlProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeFallbackLogTest {
    @TempDir
    Path temp;

    @AfterEach
    void clean() {
        DiagnosticRuntime.DIAGNOSTIC_MODE = false;
        DiagnosticRuntime.finishRun();
        System.clearProperty(CompositeBinaryLocator.BIN_KEY);
        System.clearProperty(CompositeMode.KEY);
        System.clearProperty(CompositeLog.FALLBACK_KEY);
        CompositeRuntime.resetForTests();
    }

    @Test
    void missingEngineWarnsOnceByDefaultAndStaysQuietWhenOff() {
        Path project = temp.resolve("project");
        ClassLoader empty = new ClassLoader(null) {
        };
        CompositeRuntime.use(project, Map.of(), empty, temp.resolve("home"));
        assertFalse(CompositeRuntime.primary());
        assertTrue(fallbackLines().isEmpty(), fallbackLines().toString());

        CompositeRuntime.resetForTests();
        CompositeRuntime.use(project, Map.of("PKB_COMPOSITE", "on"), empty, temp.resolve("home"));
        assertFalse(CompositeRuntime.primary());
        assertFalse(CompositeRuntime.primary());
        List<String> lines = fallbackLines();
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("error:"), lines.get(0));
        assertTrue(lines.get(1).startsWith("fix:"), lines.get(1));
        assertTrue(lines.get(0).contains("DOM engine") || lines.get(1).contains("pkb_composite=on"), lines.toString());
        String kept = CompositeLog.lastFallbackForTests();
        assertTrue(kept != null && kept.contains("error:") && kept.contains("fix:") && kept.contains("reason: missing"), kept);

        CompositeRuntime.resetForTests();
        CompositeRuntime.use(project, Map.of("PKB_COMPOSITE", "on", "PKB_COMPOSITE_FALLBACK_LOG", "off"), empty, temp.resolve("home"));
        assertFalse(CompositeRuntime.primary());
        List<String> required = fallbackLines();
        assertEquals(2, required.size(), required.toString());
        assertTrue(required.get(0).startsWith("error:"), required.toString());
        assertTrue(required.get(1).startsWith("fix:"), required.toString());

        CompositeRuntime.resetForTests();
        CompositeRuntime.use(project, Map.of("PKB_COMPOSITE", "on", "PKB_COMPOSITE_FALLBACK_LOG", "info"), empty, temp.resolve("home"));
        assertFalse(CompositeRuntime.primary());
        List<String> info = fallbackLines();
        assertEquals(2, info.size(), info.toString());
        assertTrue(info.stream().noneMatch(line -> line.contains("composite.fallback")), info.toString());

        CompositeRuntime.resetForTests();
        CompositeRuntime.use(project, Map.of("PKB_COMPOSITE", "off"), empty, temp.resolve("home"));
        assertFalse(CompositeRuntime.primary());
        assertTrue(fallbackLines().isEmpty(), fallbackLines().toString());
        assertTrue(CompositeLog.lastFallbackForTests() == null);
    }

    @Test
    void clientFailureAndJoinFailureEachLogOnce() throws Exception {
        Path dead = executable(temp.resolve("dead").resolve("composite"), "#!/bin/sh\nexit 0\n");
        ClassLoader empty = new ClassLoader(null) {
        };
        CompositeRuntime.use(
                temp.resolve("client-project"),
                Map.of(DirectControlProvider.BIN_ENV, dead.toString(), "pkb_composite", "on"),
                empty,
                temp.resolve("home")
        );
        assertFalse(CompositeRuntime.primary());
        assertFalse(CompositeRuntime.primary());
        List<String> client = fallbackLines();
        assertEquals(2, client.size(), client.toString());
        assertTrue(client.get(0).startsWith("error:"), client.toString());
        assertTrue(client.get(1).startsWith("fix:"), client.toString());
        assertTrue(CompositeLog.lastFallbackForTests().contains("reason: client"), CompositeLog.lastFallbackForTests());
        assertFalse(client.get(0).contains("engine=none"), client.toString());

        CompositeRuntime.resetForTests();
        Path binary = executable(temp.resolve("join").resolve("composite"), """
                #!/usr/bin/env python3
                import json, sys
                for line in sys.stdin:
                    line = line.strip()
                    if not line:
                        continue
                    req = json.loads(line)
                    argv = req.get("argv") or []
                    if "version" in argv:
                        exit_code = 0
                        stdout = json.dumps({"version": "2.4.0"})
                    else:
                        exit_code = 1
                        stdout = ""
                    sys.stdout.write(json.dumps({
                        "id": req.get("id"),
                        "exit": exit_code,
                        "stdout": stdout,
                        "stderr": "join refused"
                    }) + "\\n")
                    sys.stdout.flush()
                """);
        CompositeRuntime.use(
                temp.resolve("join-project"),
                Map.of(DirectControlProvider.BIN_ENV, binary.toString(), "pkb_composite", "on"),
                empty,
                temp.resolve("home")
        );
        assertTrue(CompositeRuntime.primary(), CompositeLog.lastFallbackForTests());
        MutableCapabilities caps = new MutableCapabilities();
        caps.setCapability("browserName", "chrome");
        caps.setCapability("se:cdp", "http://127.0.0.1:9222");
        RemoteWebDriver driver = new RemoteWebDriver(command -> {
            Response response = new Response();
            response.setState("success");
            response.setSessionId("join-1");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("browserName", "chrome");
            value.put("se:cdp", "http://127.0.0.1:9222");
            response.setValue(value);
            return response;
        }, caps);
        try {
            CompositeRuntime.attach(driver);
            CompositeRuntime.attach(driver);
        } finally {
            try {
                driver.quit();
            } catch (RuntimeException ignored) {
                // The join already failed.
            }
        }
        List<String> join = fallbackLines();
        assertEquals(1, join.stream().filter(line -> line.startsWith("error:")).count(), join.toString());
        assertEquals(1, join.stream().filter(line -> line.startsWith("fix:")).count(), join.toString());
        assertTrue(join.get(0).contains("join"), join.toString());
        assertFalse(CompositeRuntime.primary());
    }

    @Test
    void explicitCompositeBinLogsTheOverrideSource() throws Exception {
        Path binary = executable(temp.resolve("explicit").resolve("composite"), "#!/bin/sh\nexit 0\n");
        Path cwd = Path.of("").toAbsolutePath();
        String relative = cwd.relativize(binary.toAbsolutePath()).toString();
        CompositeBinaryLocator.locate(
                temp.resolve("other-project"),
                Map.of(DirectControlProvider.BIN_ENV, relative),
                new ClassLoader(null) {
                }
        );
        List<String> lines = CompositeLog.linesForTests();
        assertTrue(lines.stream().anyMatch(line ->
                line.startsWith("INFO composite.engine override source=COMPOSITE_BIN")
                        && line.contains(binary.toAbsolutePath().normalize().toString())), lines.toString());
    }

    @Test
    void fallbackIsARunDiagnosticEvent() throws Exception {
        Path root = temp.resolve("diagnostic-runs");
        Files.createDirectories(root);
        DiagnosticRuntime.configure(Map.of(
                "pkb_reportingmode", "diagnostic",
                "pkb_diagnostic_output", root.toString(),
                "pkb_run_id", "fallback-run"
        ));
        assertTrue(DiagnosticRuntime.isDiagnostic());
        CompositeRuntime.use(temp.resolve("diag"), Map.of("PKB_COMPOSITE", "on"), new ClassLoader(null) {
        }, temp.resolve("home"));
        assertFalse(CompositeRuntime.primary());
        Path events = DiagnosticRuntime.runRoot().resolve("run-events.jsonl");
        String text = Files.readString(events);
        assertTrue(text.contains("\"type\":\"composite.fallback\""), text);
        assertTrue(text.contains("\"reason\":\"missing\""), text);
        String index = Files.readString(DiagnosticRuntime.runRoot().resolve("run-index.json"));
        assertTrue(index.contains("compositeFallbacks"), index);
        assertTrue(index.contains("missing"), index);
    }

    private static List<String> fallbackLines() {
        return CompositeLog.linesForTests().stream()
                .filter(line -> line.contains("composite.fallback") || line.startsWith("error:") || line.startsWith("fix:"))
                .toList();
    }

    private static Path executable(Path path, String body) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, body);
        Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return path;
    }
}
