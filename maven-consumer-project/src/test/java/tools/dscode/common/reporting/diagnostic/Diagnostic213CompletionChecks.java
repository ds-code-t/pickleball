package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Diagnostic213CompletionChecks {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void equalValueStrongerSourceStillWins() {
        Map<String, String> values = new LinkedHashMap<>();
        ConfigurationProvenance.begin();
        values.put("pkb_reportingmode", "diagnostic");
        ConfigurationProvenance.capture("resource:pickleball_local2.properties", values);
        ConfigurationProvenance.captureSupplied("system-properties", "pkb_reportingmode", "diagnostic");

        assertEquals("system-properties",
                ConfigurationProvenance.effective(values).get("pkb_reportingmode").source());
    }

    @Test
    void nestedConsumerModuleSourceResolvesFromCodeSource() throws Exception {
        SourceProvenance provenance = SourceProvenance.capture(Map.of("pkb_gitsnapshot", "metadata"));
        Map<String, Object> definition = provenance.definitionSource(
                Diagnostic213CompletionChecks.class.getDeclaredMethod("nestedConsumerModuleSourceResolvesFromCodeSource"),
                "tools.dscode.common.reporting.diagnostic.Diagnostic213CompletionChecks.nestedConsumerModuleSourceResolvesFromCodeSource(Diagnostic213CompletionChecks.java:1)"
        );

        assertEquals("NON_PICKLEBALL", definition.get("origin"));
        assertEquals("consumer", definition.get("repository"));
        String sourcePath = String.valueOf(definition.get("sourcePath"));
        assertTrue(
                sourcePath.endsWith(
                        "src/test/java/tools/dscode/common/reporting/diagnostic/Diagnostic213CompletionChecks.java"
                ),
                () -> "Unexpected consumer source provenance: " + definition
        );
        assertEquals(
                64,
                String.valueOf(definition.get("sourceSha256")).length(),
                () -> "Missing consumer source hash: " + definition
        );
    }

    @Test
    void classpathFeatureSourceResolvesFromNestedConsumerModule() {
        SourceProvenance provenance = SourceProvenance.capture(Map.of("pkb_gitsnapshot", "metadata"));
        Map<String, Object> source = provenance.featureSource(
                "classpath:features/diagnostic-reporting-validation.feature",
                1
        );

        String sourcePath = String.valueOf(source.get("path"));
        assertTrue(
                sourcePath.endsWith(
                        "src/test/resources/features/diagnostic-reporting-validation.feature"
                ),
                () -> "Unexpected feature source provenance: " + source
        );
        assertEquals(
                64,
                String.valueOf(source.get("sha256")).length(),
                () -> "Missing feature source hash: " + source
        );
    }

    @Test
    void scenarioIdentityCanonicalizesClasspathAndFileResourceUris() {
        String classpath = ScenarioIdentity.canonicalSourceUri(
                "classpath:features/diagnostic-reporting-validation.feature"
        );
        String file = ScenarioIdentity.canonicalSourceUri(
                "file:/C:/work/pickleball/maven-consumer-project/src/test/resources/features/diagnostic-reporting-validation.feature"
        );

        assertEquals("features/diagnostic-reporting-validation.feature", classpath);
        assertEquals(classpath, file);
    }

    @Test
    void rebuilderPreservesScenarioLevelCapabilities() throws Exception {
        Path root = Files.createTempDirectory("pickleball-capability-rebuild");
        try {
            Path scenarioRoot = root.resolve("scenarios").resolve("scenario-1");
            Files.createDirectories(scenarioRoot);

            JSON.writeValue(root.resolve("manifest.json").toFile(), Map.of(
                    "runId", "run-1",
                    "outcome", "PASSED",
                    "completion", "COMPLETE",
                    "startedAt", "2026-08-08T00:00:00Z",
                    "reportRetention", "all"
            ));
            JSON.writeValue(root.resolve("configuration.json").toFile(), Map.of("effective", Map.of()));
            JSON.writeValue(root.resolve("environment.json").toFile(), Map.of("javaVersion", "21"));
            JSON.writeValue(root.resolve("source-provenance.json").toFile(), Map.of("repositories", List.of()));
            JSON.writeValue(scenarioRoot.resolve("summary.json").toFile(), Map.of(
                    "scenarioExecutionId", "scenario-1",
                    "outcome", "PASSED",
                    "completion", "COMPLETE",
                    "startedAt", "2026-08-08T00:00:00Z",
                    "identity", Map.of(
                            "semanticKey", "semantic",
                            "exactSourceKey", "exact"
                    ),
                    "nativeCapabilitiesObserved", List.of()
            ));

            List<Map<String, Object>> events = List.of(
                    Map.of(
                            "type", "scenario_start",
                            "scenarioSeq", 1,
                            "eventSeq", 1,
                            "timestamp", "2026-08-08T00:00:00Z",
                            "identity", Map.of("semanticKey", "semantic", "exactSourceKey", "exact")
                    ),
                    Map.of(
                            "type", "nested_scenario_start",
                            "scenarioSeq", 2,
                            "eventSeq", 2,
                            "timestamp", "2026-08-08T00:00:01Z"
                    ),
                    Map.of(
                            "type", "step",
                            "scenarioSeq", 3,
                            "eventSeq", 3,
                            "timestamp", "2026-08-08T00:00:02Z",
                            "status", "PASSED",
                            "definition", Map.of("origin", "PICKLEBALL"),
                            "nativeCapabilitiesObserved", List.of("service.scenario")
                    ),
                    Map.of(
                            "type", "scenario_end",
                            "scenarioSeq", 4,
                            "eventSeq", 4,
                            "timestamp", "2026-08-08T00:00:03Z",
                            "outcome", "PASSED",
                            "completion", "COMPLETE"
                    )
            );
            StringBuilder jsonl = new StringBuilder();
            for (Map<String, Object> event : events) {
                jsonl.append(JSON.writeValueAsString(event)).append('\n');
            }
            Files.writeString(scenarioRoot.resolve("events.jsonl"), jsonl, StandardCharsets.UTF_8);

            DiagnosticIndexRebuilder.rebuildRunIndex(root);

            @SuppressWarnings("unchecked")
            Map<String, Object> summary = JSON.readValue(
                    scenarioRoot.resolve("summary.json").toFile(),
                    LinkedHashMap.class
            );
            @SuppressWarnings("unchecked")
            List<String> capabilities = (List<String>) summary.get("nativeCapabilitiesObserved");
            assertTrue(capabilities.contains("scenario.nested"));
            assertTrue(capabilities.contains("service.scenario"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void runTraceIsCompressedLosslesslyAndRunEndIsTerminal() throws Exception {
        Path root = Files.createTempDirectory("pickleball-run-trace");
        String retention = ReportRetentionPolicy.configuredValue();
        try {
            ReportRetentionPolicy.configure("all");
            DiagnosticReporter reporter = new DiagnosticReporter(Map.of("pkb_diagnostic_output", root.toString()));
            reporter.recordFilteredLog(tools.dscode.common.reporting.logging.Level.TRACE, "deep trace");
            reporter.recordFilteredLog(tools.dscode.common.reporting.logging.Level.INFO, "navigation event");
            reporter.finishRun();

            Path runRoot = reporter.runRoot();
            assertTrue(Files.isRegularFile(runRoot.resolve("run-events.jsonl")));
            assertTrue(Files.isRegularFile(runRoot.resolve("run-trace.jsonl.gz")));
            assertFalse(Files.exists(runRoot.resolve("run-trace.jsonl")));

            List<Map<String, Object>> plain = readJsonLines(runRoot.resolve("run-events.jsonl"));
            List<Map<String, Object>> trace = readJsonLinesGzip(runRoot.resolve("run-trace.jsonl.gz"));
            assertEquals("run_end", plain.getLast().get("type"));
            assertEquals(1, trace.size());
            assertEquals("TRACE", trace.getFirst().get("level"));
            assertTrue(plain.stream().noneMatch(Diagnostic213CompletionChecks::isTraceOrDebug));

            List<Long> sequences = new ArrayList<>();
            plain.forEach(event -> sequences.add(((Number) event.get("eventSeq")).longValue()));
            trace.forEach(event -> sequences.add(((Number) event.get("eventSeq")).longValue()));
            sequences.sort(Long::compareTo);
            for (int i = 0; i < sequences.size(); i++) assertEquals(i + 1L, sequences.get(i));

            @SuppressWarnings("unchecked")
            Map<String, Object> index = JSON.readValue(runRoot.resolve("run-index.json").toFile(), LinkedHashMap.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> traceEvidence = (Map<String, Object>) index.get("runTraceEvidence");
            assertEquals("run-trace.jsonl.gz", traceEvidence.get("path"));
            assertEquals("gzip", traceEvidence.get("contentEncoding"));
            assertEquals(1, ((Number) traceEvidence.get("eventCount")).intValue());
        } finally {
            ReportRetentionPolicy.configure(retention);
            deleteTree(root);
        }
    }

    @Test
    void rebuilderRecognizesInterruptedRawRunTrace() throws Exception {
        Path root = Files.createTempDirectory("pickleball-raw-run-trace");
        try {
            Files.createDirectories(root.resolve("scenarios"));
            JSON.writeValue(root.resolve("manifest.json").toFile(), Map.of(
                    "runId", "run-1",
                    "outcome", "UNKNOWN",
                    "completion", "INTERRUPTED",
                    "startedAt", "2026-08-08T00:00:00Z",
                    "reportRetention", "all"
            ));
            JSON.writeValue(root.resolve("configuration.json").toFile(), Map.of("effective", Map.of()));
            JSON.writeValue(root.resolve("environment.json").toFile(), Map.of("javaVersion", "21"));
            JSON.writeValue(root.resolve("source-provenance.json").toFile(), Map.of("repositories", List.of()));
            Files.writeString(root.resolve("run-trace.jsonl"), JSON.writeValueAsString(Map.of(
                    "type", "log", "level", "TRACE", "eventSeq", 7, "timestamp", "2026-08-08T00:00:01Z"
            )) + "\n", StandardCharsets.UTF_8);

            Map<String, Object> rebuilt = DiagnosticIndexRebuilder.rebuildRunIndex(root);
            @SuppressWarnings("unchecked")
            Map<String, Object> traceEvidence = (Map<String, Object>) rebuilt.get("runTraceEvidence");
            assertEquals("run-trace.jsonl", traceEvidence.get("path"));
            assertEquals("identity", traceEvidence.get("contentEncoding"));
            assertEquals(7L, ((Number) traceEvidence.get("eventSeqFirst")).longValue());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void diagnosticCliComparesRunsWithoutJshell() throws Exception {
        Path root = Files.createTempDirectory("pickleball-cli-runs");
        try {
            Path left = root.resolve("left/run-index.json");
            Path right = root.resolve("right/run-index.json");
            Path output = root.resolve("comparison.json");
            Files.createDirectories(left.getParent());
            Files.createDirectories(right.getParent());

            Map<String, Object> identity = Map.of(
                    "featureUri", "classpath:features/example.feature",
                    "scenarioName", "CLI comparison",
                    "exactSourceKey", "same-source",
                    "semanticKey", "same-semantic",
                    "nameKey", "same-name",
                    "tagKey", "same-tags",
                    "sourceOrderHint", 10
            );
            Map<String, Object> scenario = Map.of(
                    "scenarioExecutionId", "scenario-1",
                    "identity", identity,
                    "outcome", "PASSED",
                    "representativeScreenshots", List.of()
            );
            Map<String, Object> index = Map.of(
                    "runId", "run-1",
                    "outcome", "PASSED",
                    "comparisonMetadata", Map.of(),
                    "scenarios", List.of(scenario)
            );
            JSON.writeValue(left.toFile(), index);
            JSON.writeValue(right.toFile(), index);

            assertEquals(0, DiagnosticCli.run(new String[]{
                    "compare-runs", left.toString(), right.toString(), output.toString()
            }, System.out, System.err));

            @SuppressWarnings("unchecked")
            Map<String, Object> comparison = JSON.readValue(output.toFile(), LinkedHashMap.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transitions = (List<Map<String, Object>>) comparison.get("scenarioTransitions");
            assertEquals("EXACT_SOURCE", transitions.getFirst().get("matchBasis"));
            assertEquals("PERSISTENT_PASS", transitions.getFirst().get("transition"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void diagnosticCliComparesFingerprintSidecarsWithoutOpeningImages() throws Exception {
        Path root = Files.createTempDirectory("pickleball-cli-fingerprints");
        try {
            BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
            Path left = root.resolve("left.pkbf");
            Path right = root.resolve("right.pkbf");
            Path output = root.resolve("comparison.json");
            byte[] fingerprint = VisualFingerprint.fromImage(image).toBytes();
            Files.write(left, fingerprint);
            Files.write(right, fingerprint);

            assertEquals(0, DiagnosticCli.run(new String[]{
                    "compare-fingerprints", left.toString(), right.toString(), output.toString()
            }, System.out, System.err));

            @SuppressWarnings("unchecked")
            Map<String, Object> result = JSON.readValue(output.toFile(), LinkedHashMap.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> comparison = (Map<String, Object>) result.get("comparison");
            assertEquals("IDENTICAL", comparison.get("category"));
            assertEquals(Boolean.TRUE, comparison.get("decodedPixelsExactlyEqual"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void diagnosticCliRebuildRestoresSparseNavigation() throws Exception {
        Path runsRoot = Files.createTempDirectory("pickleball-cli-rebuild");
        try {
            Path runRoot = runsRoot.resolve("run-1");
            Path scenarioRoot = runRoot.resolve("scenarios/scenario-1");
            Files.createDirectories(scenarioRoot);
            JSON.writeValue(runRoot.resolve("manifest.json").toFile(), Map.of(
                    "runId", "run-1",
                    "outcome", "FAILED",
                    "completion", "COMPLETE",
                    "startedAt", "2026-08-08T00:00:00Z",
                    "reportRetention", "all"
            ));
            JSON.writeValue(runRoot.resolve("configuration.json").toFile(), Map.of("effective", Map.of()));
            JSON.writeValue(runRoot.resolve("environment.json").toFile(), Map.of("javaVersion", "21"));
            JSON.writeValue(runRoot.resolve("source-provenance.json").toFile(), Map.of("repositories", List.of()));

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("scenarioExecutionId", "scenario-1");
            summary.put("outcome", "FAILED");
            summary.put("completion", "COMPLETE");
            summary.put("startedAt", "2026-08-08T00:00:00Z");
            summary.put("identity", Map.of(
                    "featureUri", "classpath:features/example.feature",
                    "scenarioName", "CLI rebuild",
                    "exactSourceKey", "exact",
                    "semanticKey", "semantic",
                    "nameKey", "name",
                    "sourceOrderHint", 1
            ));
            summary.put("failureSignature", "failure-1");
            JSON.writeValue(scenarioRoot.resolve("summary.json").toFile(), summary);

            assertEquals(0, DiagnosticCli.run(new String[]{
                    "rebuild", runsRoot.toString()
            }, System.out, System.err));

            assertTrue(Files.isRegularFile(runsRoot.resolve("run-catalog.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("run-index.json")));
            assertTrue(Files.isRegularFile(runRoot.resolve("clusters.json")));
        } finally {
            deleteTree(runsRoot);
        }
    }

    @Test
    void failureSignaturesSeparateDifferentStepSites() throws Exception {
        Method method = Diagnostic213CompletionChecks.class.getDeclaredMethod(
                "failureSignaturesSeparateDifferentStepSites"
        );
        RuntimeException failure = new RuntimeException("FAILED Assertion evaluates to: false");

        String firstSite = Diagnostic213CompletionAspect.failureSiteKeyForTesting(
                "classpath:features/diagnostic-reporting-validation.feature",
                54,
                "ensure first assertion",
                method
        );
        String secondSite = Diagnostic213CompletionAspect.failureSiteKeyForTesting(
                "classpath:features/diagnostic-reporting-validation.feature",
                61,
                "ensure second assertion",
                method
        );
        String intellijEquivalentSite = Diagnostic213CompletionAspect.failureSiteKeyForTesting(
                "file:/C:/work/pickleball/maven-consumer-project/src/test/resources/features/diagnostic-reporting-validation.feature",
                54,
                "ensure first assertion",
                method
        );

        assertEquals(firstSite, intellijEquivalentSite);

        String first = Diagnostic213CompletionAspect.failureSignatureForTesting(failure, firstSite);
        String repeated = Diagnostic213CompletionAspect.failureSignatureForTesting(
                new RuntimeException("FAILED Assertion evaluates to: false"),
                firstSite
        );
        String second = Diagnostic213CompletionAspect.failureSignatureForTesting(failure, secondSite);

        assertEquals(first, repeated);
        assertNotEquals(firstSite, secondSite);
        assertNotEquals(first, second);
    }

    @Test
    void failureMetadataExposesSparseSiteDetails() throws Exception {
        Method method = Diagnostic213CompletionChecks.class.getDeclaredMethod(
                "failureMetadataExposesSparseSiteDetails"
        );
        Map<String, Object> metadata = Diagnostic213CompletionAspect.failureMetadataForTesting(
                new RuntimeException("FAILED Assertion evaluates to: false"),
                "classpath:features/diagnostic-reporting-validation.feature",
                60,
                "ensure browser assertion",
                method
        );

        assertEquals(2, ((Number) metadata.get("failureSignatureVersion")).intValue());
        assertEquals(24, String.valueOf(metadata.get("failureSiteKey")).length());
        @SuppressWarnings("unchecked")
        Map<String, Object> site = (Map<String, Object>) metadata.get("failureSite");
        assertEquals("features/diagnostic-reporting-validation.feature", site.get("feature"));
        assertEquals(60, ((Number) site.get("stepLine")).intValue());
        assertEquals(
                Diagnostic213CompletionChecks.class.getName() + "#failureMetadataExposesSparseSiteDetails",
                site.get("definition")
        );

        Map<String, Object> legacy = Diagnostic213CompletionAspect.failureMetadataForTesting(
                new RuntimeException("runner failure"), "", 0, "", null
        );
        assertEquals(1, ((Number) legacy.get("failureSignatureVersion")).intValue());
        assertFalse(legacy.containsKey("failureSiteKey"));
        assertFalse(legacy.containsKey("failureSite"));
    }

    @Test
    void rebuiltClustersAndRunComparisonPreserveFailureMetadata() throws Exception {
        Path root = Files.createTempDirectory("pickleball-failure-metadata-rebuild");
        try {
            Path scenarioRoot = root.resolve("scenarios/scenario-1");
            Files.createDirectories(scenarioRoot);
            Map<String, Object> failureSite = Map.of(
                    "feature", "features/diagnostic-reporting-validation.feature",
                    "stepLine", 60,
                    "definition", "tools.dscode.coredefinitions.DynamicSteps#executeDynamicStep"
            );
            Map<String, Object> identity = Map.of(
                    "featureUri", "classpath:features/diagnostic-reporting-validation.feature",
                    "scenarioName", "Diagnostic browser failure evidence",
                    "exactSourceKey", "exact",
                    "semanticKey", "semantic",
                    "nameKey", "name",
                    "sourceOrderHint", 60
            );
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("scenarioExecutionId", "scenario-1");
            summary.put("identity", identity);
            summary.put("outcome", "FAILED");
            summary.put("completion", "COMPLETE");
            summary.put("startedAt", "2026-08-08T00:00:00Z");
            summary.put("failureSignature", "signature-v2");
            summary.put("failureSignatureVersion", 2);
            summary.put("failureSiteKey", "site-key");
            summary.put("failureSite", failureSite);
            JSON.writeValue(scenarioRoot.resolve("summary.json").toFile(), summary);

            JSON.writeValue(root.resolve("manifest.json").toFile(), Map.of(
                    "runId", "run-1",
                    "outcome", "FAILED",
                    "completion", "COMPLETE",
                    "startedAt", "2026-08-08T00:00:00Z",
                    "reportRetention", "all"
            ));
            JSON.writeValue(root.resolve("configuration.json").toFile(), Map.of("effective", Map.of()));
            JSON.writeValue(root.resolve("environment.json").toFile(), Map.of("javaVersion", "21"));
            JSON.writeValue(root.resolve("source-provenance.json").toFile(), Map.of("repositories", List.of()));

            Map<String, Object> rebuilt = DiagnosticIndexRebuilder.rebuildRunIndex(root);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> scenarios = (List<Map<String, Object>>) rebuilt.get("scenarios");
            assertEquals(2, ((Number) scenarios.getFirst().get("failureSignatureVersion")).intValue());
            assertEquals("site-key", scenarios.getFirst().get("failureSiteKey"));
            assertEquals(failureSite, scenarios.getFirst().get("failureSite"));

            @SuppressWarnings("unchecked")
            Map<String, Object> clusters = JSON.readValue(root.resolve("clusters.json").toFile(), LinkedHashMap.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> clusterList = (List<Map<String, Object>>) clusters.get("clusters");
            Map<String, Object> cluster = clusterList.getFirst();
            assertEquals(2, ((Number) cluster.get("failureSignatureVersion")).intValue());
            assertEquals("site-key", cluster.get("failureSiteKey"));
            assertEquals(failureSite, cluster.get("failureSite"));

            Path other = root.resolveSibling(root.getFileName() + "-other");
            Files.createDirectories(other);
            try {
                JSON.writeValue(other.resolve("run-index.json").toFile(), rebuilt);
                Map<String, Object> comparison = DiagnosticRunComparator.compare(
                        root.resolve("run-index.json"),
                        other.resolve("run-index.json")
                );
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> transitions =
                        (List<Map<String, Object>>) comparison.get("scenarioTransitions");
                @SuppressWarnings("unchecked")
                Map<String, Object> left = (Map<String, Object>) transitions.getFirst().get("left");
                assertEquals(2, ((Number) left.get("failureSignatureVersion")).intValue());
                assertEquals("site-key", left.get("failureSiteKey"));
                assertEquals(failureSite, left.get("failureSite"));
            } finally {
                deleteTree(other);
            }
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void runProfileMetadataIsSparseAndRebuildable() throws Exception {
        Path root = Files.createTempDirectory("pickleball-run-profile-diagnostic");
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("pkb_diagnostic_output", root.toString());
            values.put("pkb_browser", "CHROME_HEADLESS");
            values.put("pkb_tags", "@profile-direct-validation");
            values.put("pkb_reportingmode", "diagnostic");
            values.put("pkb_service_token", "runtime-secret");
            values.put("pkb_run_profile",
                    "pkb_browser=CHROME_HEADLESS, pkb_reportingmode=diagnostic, "
                            + "pkb_service_token=${protected:pkb_service_token}, "
                            + "pkb_tags=@profile-direct-validation");
            values.put("pkb_investigation_id", "diag-214-run-profile");

            DiagnosticReporter reporter = new DiagnosticReporter(values, true);
            reporter.finishRun();
            Path runRoot = reporter.runRoot();

            @SuppressWarnings("unchecked")
            Map<String, Object> index = JSON.readValue(runRoot.resolve("run-index.json").toFile(), LinkedHashMap.class);
            String runProfile = String.valueOf(index.get("runProfile"));
            String fingerprint = String.valueOf(index.get("runProfileFingerprint"));
            assertTrue(runProfile.contains("pkb_tags=@profile-direct-validation"));
            assertTrue(runProfile.contains("pkb_service_token=${protected:pkb_service_token}"));
            assertFalse(runProfile.contains("runtime-secret"));
            assertFalse(runProfile.contains("pkb_investigation_id"));
            assertEquals(Boolean.TRUE, index.get("directRunProfile"));
            assertEquals(64, fingerprint.length());

            @SuppressWarnings("unchecked")
            Map<String, Object> comparison = (Map<String, Object>) index.get("comparisonMetadata");
            assertEquals(fingerprint, comparison.get("runProfileFingerprint"));
            assertEquals(Boolean.TRUE, comparison.get("directRunProfile"));

            @SuppressWarnings("unchecked")
            Map<String, Object> configuration = JSON.readValue(
                    runRoot.resolve("configuration.json").toFile(), LinkedHashMap.class);
            assertEquals(runProfile, configuration.get("runProfile"));
            assertEquals(fingerprint, configuration.get("runProfileFingerprint"));
            assertEquals(Boolean.TRUE, configuration.get("directRunProfile"));
            @SuppressWarnings("unchecked")
            Map<String, Object> effective = (Map<String, Object>) configuration.get("effective");
            assertFalse(effective.containsKey("pkb_investigation_id"));
            assertFalse(Files.readString(runRoot.resolve("configuration.json")).contains("runtime-secret"));

            Files.delete(runRoot.resolve("run-index.json"));
            Map<String, Object> rebuilt = DiagnosticIndexRebuilder.rebuildRunIndex(runRoot);
            assertEquals(runProfile, rebuilt.get("runProfile"));
            assertEquals(fingerprint, rebuilt.get("runProfileFingerprint"));
            assertEquals(Boolean.TRUE, rebuilt.get("directRunProfile"));
            @SuppressWarnings("unchecked")
            Map<String, Object> rebuiltComparison = (Map<String, Object>) rebuilt.get("comparisonMetadata");
            assertEquals(fingerprint, rebuiltComparison.get("runProfileFingerprint"));
            assertEquals(Boolean.TRUE, rebuiltComparison.get("directRunProfile"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    void failureSignatureNormalizesVolatileNumbersAndPreservesNoSiteFallback() {
        String site = "stable-site";
        String first = Diagnostic213CompletionAspect.failureSignatureForTesting(
                new RuntimeException("Timed out after 1000 ms at 0xA12F"),
                site
        );
        String second = Diagnostic213CompletionAspect.failureSignatureForTesting(
                new RuntimeException("Timed out after 2500 ms at 0xB99C"),
                site
        );
        assertEquals(first, second);

        RuntimeException failure = new RuntimeException("FAILED Assertion evaluates to: false");
        String expectedLegacy = ScenarioIdentity.shortHash(
                RuntimeException.class.getName() + "|FAILED Assertion evaluates to: false"
        );
        assertEquals(expectedLegacy,
                Diagnostic213CompletionAspect.failureSignatureForTesting(failure, ""));
    }

    private static boolean isTraceOrDebug(Map<String, Object> event) {
        if (!"log".equals(String.valueOf(event.get("type")))) return false;
        String level = String.valueOf(event.get("level"));
        return "TRACE".equals(level) || "DEBUG".equals(level);
    }

    private static List<Map<String, Object>> readJsonLines(Path path) throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) events.add(JSON.readValue(line, LinkedHashMap.class));
        }
        return events;
    }

    private static List<Map<String, Object>> readJsonLinesGzip(Path path) throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) events.add(JSON.readValue(line, LinkedHashMap.class));
            }
        }
        return events;
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
