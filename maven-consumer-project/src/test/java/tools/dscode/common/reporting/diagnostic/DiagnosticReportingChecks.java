package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import tools.dscode.common.variables.PlatformLogFormatter;
import tools.dscode.coredefinitions.ServiceCallSteps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiagnosticReportingChecks {
    @Test
    void diagnosticReporterWritesLayeredRunArtifacts() throws Exception {
        Path root = Files.createTempDirectory("pickleball-diagnostic-run");
        try {
            ReportRetentionPolicy.configure("all");
            DiagnosticReporter reporter = new DiagnosticReporter(Map.of(
                    "pkb_diagnostic_output", root.toString(),
                    "pkb_browser", "chrome",
                    "pkb_environment", "test"
            ));
            reporter.recordFilteredLog(tools.dscode.common.reporting.logging.Level.TRACE, "trace evidence");
            reporter.finishRun();

            Path runRoot = reporter.runRoot();
            assertTrue(Files.isRegularFile(root.resolve("run-catalog.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("manifest.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("run-index.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("run-events.jsonl")));
            assertTrue(Files.isRegularFile(runRoot.resolve("configuration.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("environment.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("source-provenance.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("clusters.json")));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void reportRetentionDefaultsAndFailedPolicyAreDeterministic() {
        String original = ReportRetentionPolicy.configuredValue();
        try {
            ReportRetentionPolicy.configure(null);
            assertEquals(ReportRetentionPolicy.Mode.ALL, ReportRetentionPolicy.mode());
            assertTrue(ReportRetentionPolicy.keepScenarioDetails(false, false));

            ReportRetentionPolicy.configure("failed");
            assertFalse(ReportRetentionPolicy.keepScenarioDetails(false, false));
            assertTrue(ReportRetentionPolicy.keepScenarioDetails(true, false));
            assertTrue(ReportRetentionPolicy.keepScenarioDetails(false, true));
        } finally {
            ReportRetentionPolicy.configure(original);
        }
    }

    @Test
    void configurationProvenanceTracksWinningSourceAndRedactsSecrets() {
        Map<String, String> values = new LinkedHashMap<>();
        ConfigurationProvenance.begin();
        values.put("pkb_browser", "chrome");
        ConfigurationProvenance.capture("globalTestDefaults", values);
        values.put("pkb_browser", "firefox");
        values.put("pkb_api_token", "top-secret");
        ConfigurationProvenance.capture("system-properties", values);

        Map<String, ConfigurationProvenance.Value> effective = ConfigurationProvenance.effective(values);
        assertEquals("firefox", effective.get("pkb_browser").value());
        assertEquals("system-properties", effective.get("pkb_browser").source());
        assertEquals("<redacted>", effective.get("pkb_api_token").value());
        assertTrue(effective.get("pkb_api_token").redacted());
        assertFalse(effective.get("pkb_api_token").valueHash().isBlank());
    }


    @Test
    void platformLogFormattingPreservesDefaultAndSupportsSelection() {
        String key = tools.dscode.testengine.PKB_props.PKB_PLATFORM_LOG;
        String original = System.getProperty(key);
        try {
            System.clearProperty(key);
            assertEquals("default-platform-text", PlatformLogFormatter.format("default-platform-text"));

            System.setProperty(key, "keys:os.name");
            String selected = PlatformLogFormatter.format("default-platform-text");
            assertTrue(selected.startsWith("os.name="));
            assertFalse(selected.contains("default-platform-text"));

            System.setProperty(key, "template:OS=${os.name}");
            assertTrue(PlatformLogFormatter.format("default-platform-text").startsWith("OS="));

            System.setProperty(key, "none");
            assertTrue(PlatformLogFormatter.isDisabled());
            assertEquals(PlatformLogFormatter.DISABLED_MARKER, PlatformLogFormatter.format("default-platform-text"));
        } finally {
            if (original == null) System.clearProperty(key);
            else System.setProperty(key, original);
        }
    }

    @Test
    void sourceProvenanceDistinguishesPickleballAndNonPickleballDefinitions() throws Exception {
        SourceProvenance provenance = SourceProvenance.capture(Map.of("pkb_gitsnapshot", "none"));
        Map<String, Object> pickleball = provenance.definitionSource(
                ServiceCallSteps.class.getMethod("executeServiceCall"),
                "tools.dscode.coredefinitions.ServiceCallSteps.executeServiceCall(ServiceCallSteps.java:1)"
        );
        Map<String, Object> nonPickleball = provenance.definitionSource(
                DiagnosticReportingChecks.class.getDeclaredMethod("testImage", boolean.class),
                "tools.dscode.common.reporting.diagnostic.DiagnosticReportingChecks.testImage(DiagnosticReportingChecks.java:1)"
        );
        assertEquals("PICKLEBALL", pickleball.get("origin"));
        assertEquals("NON_PICKLEBALL", nonPickleball.get("origin"));
    }

    @Test
    void indexRebuilderReadsCompressedTraceAndStructuredStepMetadata() throws Exception {
        Path root = Files.createTempDirectory("pickleball-diagnostic-gzip-rebuild");
        try {
            ObjectMapper json = new ObjectMapper();
            Path run = root.resolve("run-1");
            Path scenario = run.resolve("scenarios/scenario-1");
            Files.createDirectories(scenario);
            json.writeValue(run.resolve("manifest.json").toFile(), Map.of(
                    "runId", "run-1",
                    "outcome", "PASSED",
                    "completion", "COMPLETE",
                    "startedAt", "2026-08-08T00:00:00Z",
                    "reportRetention", "all"
            ));
            json.writeValue(run.resolve("configuration.json").toFile(), Map.of("effective", Map.of()));
            json.writeValue(run.resolve("environment.json").toFile(), Map.of("javaVersion", "21"));
            json.writeValue(run.resolve("source-provenance.json").toFile(), Map.of("repositories", List.of()));

            List<Map<String, Object>> plain = List.of(
                    event("scenario_start", 1, 1, Map.of("identity", Map.of(
                            "featureUri", "file:example.feature",
                            "scenarioName", "Example",
                            "exactSourceKey", "exact",
                            "semanticKey", "semantic",
                            "nameKey", "name",
                            "sourceOrderHint", 10
                    ))),
                    event("step", 3, 3, Map.of(
                            "status", "PASSED",
                            "definition", Map.of("origin", "PICKLEBALL"),
                            "nativeCapabilitiesObserved", List.of("service.http")
                    )),
                    event("scenario_end", 4, 4, Map.of("outcome", "PASSED", "completion", "COMPLETE"))
            );
            StringBuilder plainText = new StringBuilder();
            for (Map<String, Object> event : plain) plainText.append(json.writeValueAsString(event)).append('\n');
            Files.writeString(scenario.resolve("events.jsonl"), plainText, StandardCharsets.UTF_8);

            Map<String, Object> trace = event("log", 2, 2, Map.of("level", "TRACE", "text", "deep trace"));
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(scenario.resolve("trace.jsonl.gz")))) {
                out.write((json.writeValueAsString(trace) + "\n").getBytes(StandardCharsets.UTF_8));
            }

            Map<String, Object> rebuilt = DiagnosticIndexRebuilder.rebuildRunIndex(run);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) rebuilt.get("scenarios");
            Map<String, Object> rebuiltScenario = scenarios.getFirst();
            assertEquals(4L, ((Number) rebuiltScenario.get("eventCount")).longValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> traceEvidence = (Map<String, Object>) rebuiltScenario.get("traceEvidence");
            assertEquals("gzip", traceEvidence.get("contentEncoding"));
            assertEquals(1, ((Number) traceEvidence.get("eventCount")).intValue());
            @SuppressWarnings("unchecked")
            Map<String, Object> steps = (Map<String, Object>) rebuiltScenario.get("steps");
            assertEquals(1L, ((Number) steps.get("executed")).longValue());
            assertTrue(((List<?>) rebuiltScenario.get("nativeCapabilitiesObserved")).contains("service.http"));
            assertTrue(Files.isRegularFile(scenario.resolve("trace.jsonl.gz")));
            assertFalse(Files.exists(scenario.resolve("trace.jsonl")));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void visualFingerprintIsCompactDeterministicAndComparable() throws Exception {
        BufferedImage first = testImage(false);
        BufferedImage second = testImage(false);
        BufferedImage different = testImage(true);

        VisualFingerprint a = VisualFingerprint.fromImage(first);
        VisualFingerprint b = VisualFingerprint.fromImage(second);
        VisualFingerprint c = VisualFingerprint.fromImage(different);

        byte[] encoded = a.toBytes();
        assertTrue(encoded.length >= 7_000 && encoded.length <= 8_500, "fingerprint bytes=" + encoded.length);
        assertEquals(a, VisualFingerprint.fromBytes(encoded));
        assertEquals(VisualFingerprintComparator.Category.IDENTICAL,
                VisualFingerprintComparator.compare(a, b).category());
        assertEquals(VisualFingerprintComparator.Category.VERY_DIFFERENT,
                VisualFingerprintComparator.compare(a, c).category());
    }

    @Test
    void visualFingerprintAcceptsPngAndJpegImageIoInputs() throws Exception {
        BufferedImage image = testImage(false);
        assertTrue(VisualFingerprint.fromImageBytes(imageBytes(image, "png")).toBytes().length > 0);
        assertTrue(VisualFingerprint.fromImageBytes(imageBytes(image, "jpg")).toBytes().length > 0);
    }

    @Test
    void runComparatorMatchesMovedScenariosWithoutReadingDenseEvidence() throws Exception {
        Path root = Files.createTempDirectory("pickleball-diagnostic-compare");
        try {
            ObjectMapper json = new ObjectMapper();
            Path leftRoot = root.resolve("left");
            Path rightRoot = root.resolve("right");
            Path leftFingerprint = leftRoot.resolve("scenarios/left-scenario/fingerprints/final.pkbf");
            Path rightFingerprint = rightRoot.resolve("scenarios/right-scenario/fingerprints/final.pkbf");
            Files.createDirectories(leftFingerprint.getParent());
            Files.createDirectories(rightFingerprint.getParent());
            byte[] fingerprint = VisualFingerprint.fromImage(testImage(false)).toBytes();
            Files.write(leftFingerprint, fingerprint);
            Files.write(rightFingerprint, fingerprint);

            Path left = leftRoot.resolve("run-index.json");
            Path right = rightRoot.resolve("run-index.json");
            json.writeValue(left.toFile(), runIndex("left", "PASSED", scenario(
                    "left-scenario", "PASSED", "uri-a", 10,
                    "scenarios/left-scenario/fingerprints/final.pkbf"
            )));
            json.writeValue(right.toFile(), runIndex("right", "FAILED", scenario(
                    "right-scenario", "FAILED", "uri-b", 40,
                    "scenarios/right-scenario/fingerprints/final.pkbf"
            )));

            Map<String, Object> result = DiagnosticRunComparator.compare(left, right);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transitions = (List<Map<String, Object>>) result.get("scenarioTransitions");
            assertEquals(1, transitions.size());
            assertEquals("NAME", transitions.getFirst().get("matchBasis"));
            assertEquals("NEW_FAILURE", transitions.getFirst().get("transition"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> visuals = (List<Map<String, Object>>) result.get("representativeVisualTransitions");
            assertEquals(1, visuals.size());
            @SuppressWarnings("unchecked")
            Map<String, Object> comparison = (Map<String, Object>) visuals.getFirst().get("comparison");
            assertEquals("IDENTICAL", comparison.get("category"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void indexRebuilderRecoversInterruptedScenarioAndMissingFingerprint() throws Exception {
        Path root = Files.createTempDirectory("pickleball-diagnostic-rebuild");
        try {
            ObjectMapper json = new ObjectMapper();
            Path run = root.resolve("run-1");
            Path scenario = run.resolve("scenarios").resolve("scenario-1");
            Files.createDirectories(scenario.resolve("screenshots"));
            json.writeValue(run.resolve("manifest.json").toFile(), Map.of(
                    "runId", "run-1",
                    "outcome", "UNKNOWN",
                    "completion", "INTERRUPTED",
                    "startedAt", "2026-08-08T00:00:00Z",
                    "reportRetention", "all"
            ));
            json.writeValue(run.resolve("configuration.json").toFile(), Map.of("effective", Map.of()));
            json.writeValue(run.resolve("environment.json").toFile(), Map.of("javaVersion", "21"));
            Files.writeString(scenario.resolve("events.jsonl"),
                    json.writeValueAsString(Map.of(
                            "type", "scenario_start",
                            "timestamp", "2026-08-08T00:00:01Z",
                            "scenarioExecutionId", "scenario-1",
                            "scenarioSeq", 1,
                            "identity", Map.of(
                                    "featureUri", "file:example.feature",
                                    "scenarioName", "Example",
                                    "exactSourceKey", "exact",
                                    "semanticKey", "semantic",
                                    "nameKey", "name",
                                    "sourceOrderHint", 10
                            )
                    )) + "\n{incomplete", StandardCharsets.UTF_8);
            Files.write(scenario.resolve("screenshots").resolve("0001-state.png"), imageBytes(testImage(false), "png"));

            Map<String, Object> rebuilt = DiagnosticIndexRebuilder.rebuildRunIndex(run);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) rebuilt.get("scenarios");
            assertEquals(1, scenarios.size());
            assertEquals("UNKNOWN", scenarios.getFirst().get("outcome"));
            assertEquals("INTERRUPTED", scenarios.getFirst().get("completion"));
            assertTrue(Files.isRegularFile(scenario.resolve("fingerprints").resolve("0001-state.pkbf")));
        } finally {
            deleteTree(root);
        }
    }


    private static Map<String, Object> event(
            String type,
            long eventSeq,
            long scenarioSeq,
            Map<String, Object> values
    ) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("eventSeq", eventSeq);
        event.put("scenarioSeq", scenarioSeq);
        event.put("timestamp", "2026-08-08T00:00:0" + Math.min(9, scenarioSeq) + "Z");
        event.putAll(values);
        return event;
    }

    private static Map<String, Object> runIndex(String runId, String outcome, Map<String, Object> scenario) {
        return Map.of(
                "runId", runId,
                "outcome", outcome,
                "comparisonMetadata", Map.of(),
                "scenarios", List.of(scenario)
        );
    }

    private static Map<String, Object> scenario(
            String executionId, String outcome, String featureUri, int sourceOrder, String fingerprint
    ) {
        return Map.of(
                "scenarioExecutionId", executionId,
                "outcome", outcome,
                "representativeScreenshots", List.of(Map.of(
                        "screenshotId", "final",
                        "reason", "FINAL_VISUAL_STATE",
                        "fingerprint", fingerprint
                )),
                "identity", Map.of(
                        "featureUri", featureUri,
                        "scenarioName", "Moved scenario",
                        "exactSourceKey", "exact-" + featureUri,
                        "semanticKey", "semantic-" + featureUri,
                        "nameKey", "stable-name",
                        "tagKey", "same-tags",
                        "exampleValuesHash", "",
                        "sourceOrderHint", sourceOrder
                )
        );
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static BufferedImage testImage(boolean inverse) {
        BufferedImage image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int r = inverse ? 255 - x * 255 / image.getWidth() : x * 255 / image.getWidth();
                int g = inverse ? 255 - y * 255 / image.getHeight() : y * 255 / image.getHeight();
                int b = inverse ? 20 : 220;
                image.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static byte[] imageBytes(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}
