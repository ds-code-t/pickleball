package tools.dscode.common.reporting.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.dscode.common.reporting.logging.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSecretRedactionTest {
    @TempDir
    Path tempDir;

    @Test
    void serviceCallBasicAuthIsRedactedInEvents() throws Exception {
        String password = "s3cret-pickle-value";
        String token = "live-token-value";
        String basic = Base64.getEncoder().encodeToString(("user:" + password).getBytes(StandardCharsets.UTF_8));
        String dump = """
                REST Assured service call request: {"method":"POST","endpoint":"http://localhost/login","auth":{"basic":{"username":"user","password":"%s"}}}
                Headers:\t\tAuthorization=Basic %s
                Body:
                {
                    "password": "%s",
                    "authorization": "Bearer %s"
                }
                """.formatted(password, basic, password, token);
        Path root = tempDir.resolve("diagnostic-runs");
        DiagnosticReporter reporter = new DiagnosticReporter(Map.of(
                "pkb_diagnostic_output", root.toString(),
                "pkb_run_id", "run-secret"
        ));
        reporter.startScenario(null);
        reporter.recordFilteredLog(Level.INFO, dump);

        Path events;
        try (Stream<Path> scenarios = Files.list(root.resolve("run-secret").resolve("scenarios"))) {
            events = scenarios.filter(Files::isDirectory).findFirst().orElseThrow().resolve("events.jsonl");
        }
        String text = Files.readString(events);
        assertTrue(text.contains("<redacted>"), text);
        assertFalse(text.contains(password), text);
        assertFalse(text.contains(basic), text);
        assertFalse(text.contains(token), text);
        assertTrue(text.contains("username"), text);
    }
}
