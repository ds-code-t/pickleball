package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.LinkedHashSet;
import java.util.zip.GZIPInputStream;

/** Rebuilds derived diagnostic indexes from surviving run metadata and scenario summaries. */
public final class DiagnosticIndexRebuilder {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DiagnosticIndexRebuilder() {
    }

    public static Map<String, Object> rebuildRunIndex(Path runRoot) throws IOException {
        Map<String, Object> manifest = readMap(runRoot.resolve("manifest.json"));
        Map<String, Object> configuration = readMap(runRoot.resolve("configuration.json"));
        Map<String, Object> environment = readMap(runRoot.resolve("environment.json"));
        Map<String, Object> sourceProvenance = readMap(runRoot.resolve("source-provenance.json"));
        List<Map<String, Object>> scenarios = readScenarioSummaries(runRoot.resolve("scenarios"));

        Map<String, Integer> counts = counts(scenarios);
        String outcome = outcome(manifest, counts);
        String completion = text(manifest.get("completion"));
        if (completion.isBlank()) completion = "UNKNOWN".equals(outcome) ? "INTERRUPTED" : "COMPLETE";

        Map<String, Object> index = new LinkedHashMap<>();
        index.put("schemaVersion", 1);
        index.put("runId", manifest.get("runId"));
        index.put("outcome", outcome);
        index.put("completion", completion);
        index.put("startedAt", manifest.get("startedAt"));
        index.put("reportRetention", manifest.get("reportRetention"));
        index.put("configurationHash", first(manifest.get("configurationHash"), configuration.get("configurationHash")));
        index.put("environmentHash", first(manifest.get("environmentHash"), environment.get("environmentHash")));
        index.put("sourceProvenanceHash", first(manifest.get("sourceProvenanceHash"), sourceProvenance.get("sourceProvenanceHash")));
        index.put("selectionFingerprint", manifest.get("selectionFingerprint"));
        index.put("sourceFingerprint", sourceFingerprint(scenarios));
        index.put("dependencyFingerprint", manifest.get("dependencyFingerprint"));
        String runProfile = text(configuration.get("runProfile"));
        if (!runProfile.isBlank()) index.put("runProfile", runProfile);
        index.put("runProfileFingerprint", first(configuration.get("runProfileFingerprint"), manifest.get("runProfileFingerprint")));
        index.put("directRunProfile", first(configuration.get("directRunProfile"), manifest.get("directRunProfile")));
        index.put("evidenceIntegrity", first(manifest.get("evidenceIntegrity"), "PARTIAL"));
        if (manifest.get("lineage") != null) index.put("lineage", manifest.get("lineage"));
        index.put("comparisonMetadata", comparisonMetadata(configuration, environment, sourceProvenance, index));
        index.put("counts", counts);
        index.put("steps", aggregateSteps(scenarios));
        index.put("nativeCapabilitiesObserved", aggregateCapabilities(scenarios));
        index.put("nativeCapabilityCounts", aggregateCapabilityCounts(scenarios));
        index.put("capabilitySemantics", "Presence means native Pickleball instrumentation observed the capability; absence does not prove the capability was unused by consumer-defined code.");
        index.put("scenarios", scenarios);
        index.put("paths", Map.of(
                "manifest", "manifest.json",
                "configuration", "configuration.json",
                "environment", "environment.json",
                "sourceProvenance", "source-provenance.json",
                "runEvents", "run-events.jsonl",
                "clusters", "clusters.json",
                "runCatalog", "../run-catalog.json"
        ));
        rebuildMissingFingerprints(runRoot.resolve("scenarios"));
        writeAtomic(runRoot.resolve("run-index.json"), index);
        rebuildClusters(runRoot, scenarios);
        return index;
    }

    public static void rebuildRunCatalog(Path runsRoot) throws IOException {
        List<Map<String, Object>> runs = new ArrayList<>();
        if (Files.isDirectory(runsRoot)) {
            try (var paths = Files.list(runsRoot)) {
                for (Path runRoot : paths.filter(Files::isDirectory).toList()) {
                    Path runIndex = runRoot.resolve("run-index.json");
                    Map<String, Object> index;
                    try {
                        index = Files.isRegularFile(runIndex) ? readMap(runIndex) : rebuildRunIndex(runRoot);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    Map<String, Object> summary = new LinkedHashMap<>();
                    for (String key : List.of(
                            "runId", "outcome", "completion", "startedAt", "reportRetention",
                            "configurationHash", "runProfile", "runProfileFingerprint",
                            "comparisonMetadata", "counts", "lineage"
                    )) {
                        summary.put(key, index.get(key));
                    }
                    summary.put("runIndex", runRoot.getFileName() + "/run-index.json");
                    runs.add(summary);
                }
            }
        }
        runs.sort((a, b) -> text(b.get("startedAt")).compareTo(text(a.get("startedAt"))));
        writeAtomic(runsRoot.resolve("run-catalog.json"), Map.of(
                "schemaVersion", 1,
                "updatedAt", Instant.now().toString(),
                "runs", runs
        ));
    }

    private static List<Map<String, Object>> readScenarioSummaries(Path scenariosRoot) throws IOException {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        if (!Files.isDirectory(scenariosRoot)) return scenarios;
        try (var paths = Files.list(scenariosRoot)) {
            for (Path scenarioRoot : paths.filter(Files::isDirectory).toList()) {
                try {
                    Map<String, Object> summary = recoverScenarioSummary(scenarioRoot);
                    if (!summary.isEmpty()) scenarios.add(summary);
                } catch (Throwable ignored) {
                }
            }
        }
        scenarios.sort(Comparator.comparing(item -> text(item.get("startedAt"))));
        return scenarios;
    }

    private static Map<String, Object> recoverScenarioSummary(Path scenarioRoot) throws IOException {
        Path summaryPath = scenarioRoot.resolve("summary.json");
        Map<String, Object> summary = Files.isRegularFile(summaryPath)
                ? readMap(summaryPath)
                : new LinkedHashMap<>();
        Path eventsPath = scenarioRoot.resolve("events.jsonl");
        Path traceRaw = scenarioRoot.resolve("trace.jsonl");
        Path traceGzip = scenarioRoot.resolve("trace.jsonl.gz");
        List<Map<String, Object>> events = readJsonLines(eventsPath);
        List<Map<String, Object>> traceEvents = Files.isRegularFile(traceGzip)
                ? readJsonLinesGzip(traceGzip)
                : readJsonLines(traceRaw);
        List<Map<String, Object>> allEvents = new ArrayList<>(events);
        allEvents.addAll(traceEvents);
        String executionId = text(summary.get("scenarioExecutionId"));
        if (executionId.isBlank()) executionId = scenarioRoot.getFileName().toString();
        summary.put("schemaVersion", 1);
        summary.put("scenarioExecutionId", executionId);

        Map<String, Object> start = firstEvent(events, "scenario_start");
        Map<String, Object> end = lastEvent(events, "scenario_end");
        if (summary.get("identity") == null && start.get("identity") != null) {
            summary.put("identity", start.get("identity"));
        }
        if (text(summary.get("startedAt")).isBlank() && !text(start.get("timestamp")).isBlank()) {
            summary.put("startedAt", start.get("timestamp"));
        }
        if (!end.isEmpty()) {
            if (end.get("outcome") != null) summary.put("outcome", end.get("outcome"));
            if (end.get("completion") != null) summary.put("completion", end.get("completion"));
            if (!text(end.get("timestamp")).isBlank()) summary.put("endedAt", end.get("timestamp"));
        } else {
            if ("RUNNING".equals(text(summary.get("outcome"))) || text(summary.get("outcome")).isBlank()) {
                summary.put("outcome", "UNKNOWN");
            }
            if ("IN_PROGRESS".equals(text(summary.get("completion"))) || text(summary.get("completion")).isBlank()) {
                summary.put("completion", "INTERRUPTED");
            }
        }

        long eventCount = allEvents.stream()
                .map(event -> event.get("scenarioSeq"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToLong(Number::longValue)
                .max().orElse(number(summary.get("eventCount")));
        summary.put("eventCount", eventCount);
        summary.put("eventRange", Map.of(
                "scenarioExecutionId", executionId,
                "scenarioSeqStart", eventCount == 0 ? 0 : 1,
                "scenarioSeqEnd", eventCount
        ));
        boolean retained = Files.isRegularFile(eventsPath)
                || Files.isRegularFile(traceRaw)
                || Files.isRegularFile(traceGzip)
                || Files.isDirectory(scenarioRoot.resolve("screenshots"))
                || Files.isDirectory(scenarioRoot.resolve("fingerprints"));
        summary.put("detailedEvidenceRetained", retained);
        summary.put("summary", "scenarios/" + executionId + "/summary.json");
        summary.put("events", Files.isRegularFile(eventsPath)
                ? "scenarios/" + executionId + "/events.jsonl"
                : null);
        if (!traceEvents.isEmpty()) {
            long firstTrace = traceEvents.stream().map(event -> event.get("eventSeq")).filter(Number.class::isInstance)
                    .map(Number.class::cast).mapToLong(Number::longValue).min().orElse(0);
            long lastTrace = traceEvents.stream().map(event -> event.get("eventSeq")).filter(Number.class::isInstance)
                    .map(Number.class::cast).mapToLong(Number::longValue).max().orElse(0);
            summary.put("traceEvidence", Map.of(
                    "path", "scenarios/" + executionId + "/" + (Files.isRegularFile(traceGzip) ? "trace.jsonl.gz" : "trace.jsonl"),
                    "contentType", "application/x-ndjson",
                    "contentEncoding", Files.isRegularFile(traceGzip) ? "gzip" : "identity",
                    "eventCount", traceEvents.size(),
                    "eventSeqFirst", firstTrace,
                    "eventSeqLast", lastTrace
            ));
        }
        recoverStepMetadata(summary, events);
        List<Map<String, Object>> screenshotEvents = events.stream()
                .filter(event -> "screenshot".equals(text(event.get("type"))))
                .toList();
        long screenshotCount = screenshotEvents.size();
        if (number(summary.get("screenshotCount")) < screenshotCount) summary.put("screenshotCount", screenshotCount);
        if (!(summary.get("representativeScreenshots") instanceof List<?> existing) || existing.isEmpty()) {
            summary.put("representativeScreenshots", recoverRepresentatives(screenshotEvents));
        }

        String startedAt = text(summary.get("startedAt"));
        String endedAt = text(summary.get("endedAt"));
        if (!startedAt.isBlank() && !endedAt.isBlank()) {
            try {
                long duration = Math.max(0, java.time.Duration.between(
                        Instant.parse(startedAt), Instant.parse(endedAt)).toMillis());
                summary.put("durationMillis", duration);
            } catch (Throwable ignored) {
            }
        }
        copyRunProfileIfMissing(summary, scenarioRoot);
        writeAtomic(summaryPath, summary);
        return summary;
    }

    private static void copyRunProfileIfMissing(Map<String, Object> summary, Path scenarioRoot) {
        if (!text(summary.get("runProfile")).isBlank()) {
            return;
        }
        Path scenariosRoot = scenarioRoot.getParent();
        Path runRoot = scenariosRoot == null ? null : scenariosRoot.getParent();
        if (runRoot == null) {
            return;
        }
        for (String name : List.of("configuration.json", "run-index.json")) {
            Path source = runRoot.resolve(name);
            if (!Files.isRegularFile(source)) {
                continue;
            }
            try {
                Object runProfile = readMap(source).get("runProfile");
                if (runProfile != null && !text(runProfile).isBlank()) {
                    summary.put("runProfile", runProfile);
                    return;
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static List<Map<String, Object>> recoverRepresentatives(List<Map<String, Object>> screenshots) {
        if (screenshots.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        addRecoveredRepresentative(result, screenshots.getFirst(), "FIRST_VISUAL_STATE");
        for (Map<String, Object> screenshot : screenshots) {
            if (text(screenshot.get("name")).toLowerCase(java.util.Locale.ROOT).contains("failure")) {
                addRecoveredRepresentative(result, screenshot, "FAILURE");
                break;
            }
        }
        addRecoveredRepresentative(result, screenshots.getLast(), "FINAL_VISUAL_STATE");
        return result;
    }

    private static void addRecoveredRepresentative(
            List<Map<String, Object>> target,
            Map<String, Object> screenshot,
            String reason
    ) {
        String screenshotId = text(screenshot.get("screenshotId"));
        if (screenshotId.isBlank()
                || target.stream().anyMatch(item -> screenshotId.equals(text(item.get("screenshotId"))))) return;
        Map<String, Object> representative = new LinkedHashMap<>();
        representative.put("screenshotId", screenshotId);
        representative.put("reason", reason);
        representative.put("image", screenshot.get("image"));
        representative.put("fingerprint", screenshot.get("fingerprint"));
        target.add(representative);
    }

    private static List<Map<String, Object>> readJsonLines(Path path) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isRegularFile(path)) return result;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = JSON.readValue(line, LinkedHashMap.class);
                    result.add(event);
                } catch (Throwable ignored) {
                    // An interrupted final JSONL record is intentionally ignored.
                }
            }
        }
        return result;
    }

    private static List<Map<String, Object>> readJsonLinesGzip(Path path) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isRegularFile(path)) return result;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> event = JSON.readValue(line, LinkedHashMap.class);
                    result.add(event);
                } catch (Throwable ignored) {
                }
            }
        } catch (java.util.zip.ZipException ignored) {
            // A partially written compressed stream is treated as partial evidence.
        }
        return result;
    }

    private static void recoverStepMetadata(Map<String, Object> summary, List<Map<String, Object>> events) {
        List<Map<String, Object>> steps = events.stream()
                .filter(event -> "step".equals(text(event.get("type"))))
                .toList();
        if (steps.isEmpty()) return;
        long passed = 0, failed = 0, skipped = 0, other = 0, pickleball = 0, nonPickleball = 0;
        Map<String, Long> capabilityCounts = new TreeMap<>();
        Set<String> capabilities = new LinkedHashSet<>();
        for (Map<String, Object> step : steps) {
            switch (text(step.get("status"))) {
                case "PASSED" -> passed++;
                case "FAILED", "AMBIGUOUS" -> failed++;
                case "SKIPPED" -> skipped++;
                default -> other++;
            }
            String origin = text(asMap(step.get("definition")).get("origin"));
            if ("PICKLEBALL".equals(origin)) pickleball++;
            else if ("NON_PICKLEBALL".equals(origin)) nonPickleball++;
            Object rawCapabilities = step.get("nativeCapabilitiesObserved");
            if (rawCapabilities instanceof List<?> list) {
                for (Object raw : list) {
                    String capability = text(raw);
                    if (capability.isBlank()) continue;
                    capabilities.add(capability);
                    capabilityCounts.merge(capability, 1L, Long::sum);
                }
            }
        }
        summary.put("steps", Map.of(
                "executed", steps.size(),
                "passed", passed,
                "failed", failed,
                "skipped", skipped,
                "other", other,
                "pickleball", pickleball,
                "nonPickleball", nonPickleball
        ));
        summary.put("nativeCapabilitiesObserved", capabilities.stream().sorted().toList());
        summary.put("nativeCapabilityCounts", capabilityCounts);
    }

    private static Map<String, Object> firstEvent(List<Map<String, Object>> events, String type) {
        return events.stream().filter(event -> type.equals(text(event.get("type")))).findFirst().orElse(Map.of());
    }

    private static Map<String, Object> lastEvent(List<Map<String, Object>> events, String type) {
        for (int i = events.size() - 1; i >= 0; i--) {
            Map<String, Object> event = events.get(i);
            if (type.equals(text(event.get("type")))) return event;
        }
        return Map.of();
    }

    private static Map<String, Object> aggregateSteps(List<Map<String, Object>> scenarios) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String key : List.of("executed", "passed", "failed", "skipped", "other", "pickleball", "nonPickleball")) totals.put(key, 0L);
        for (Map<String, Object> scenario : scenarios) {
            Map<String, Object> steps = asMap(scenario.get("steps"));
            totals.replaceAll((key, value) -> value + number(steps.get(key)));
        }
        return new LinkedHashMap<>(totals);
    }

    private static List<String> aggregateCapabilities(List<Map<String, Object>> scenarios) {
        Set<String> capabilities = new java.util.TreeSet<>();
        for (Map<String, Object> scenario : scenarios) {
            Object raw = scenario.get("nativeCapabilitiesObserved");
            if (raw instanceof List<?> list) for (Object item : list) capabilities.add(text(item));
        }
        capabilities.remove("");
        return List.copyOf(capabilities);
    }

    private static Map<String, Object> aggregateCapabilityCounts(List<Map<String, Object>> scenarios) {
        Map<String, Long> scenarioCounts = new TreeMap<>();
        Map<String, Long> stepCounts = new TreeMap<>();
        for (Map<String, Object> scenario : scenarios) {
            Object raw = scenario.get("nativeCapabilitiesObserved");
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    String capability = text(item);
                    if (!capability.isBlank()) scenarioCounts.merge(capability, 1L, Long::sum);
                }
            }
            asMap(scenario.get("nativeCapabilityCounts")).forEach((capability, count) ->
                    stepCounts.merge(capability, number(count), Long::sum));
        }
        Map<String, Object> result = new TreeMap<>();
        Set<String> all = new java.util.TreeSet<>();
        all.addAll(scenarioCounts.keySet());
        all.addAll(stepCounts.keySet());
        for (String capability : all) result.put(capability, Map.of(
                "scenarioCount", scenarioCounts.getOrDefault(capability, 0L),
                "stepCount", stepCounts.getOrDefault(capability, 0L)
        ));
        return result;
    }

    private static Map<String, Integer> counts(List<Map<String, Object>> scenarios) {
        int passed = 0, failed = 0, unknown = 0;
        for (Map<String, Object> scenario : scenarios) {
            switch (text(scenario.get("outcome"))) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                default -> unknown++;
            }
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("total", scenarios.size());
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("unknown", unknown);
        return result;
    }

    private static String outcome(Map<String, Object> manifest, Map<String, Integer> counts) {
        String manifestOutcome = text(manifest.get("outcome"));
        if (!manifestOutcome.isBlank() && !"RUNNING".equals(manifestOutcome)) return manifestOutcome;
        if (counts.get("total") == 0) return "NO_TESTS";
        if (counts.get("failed") > 0) return "FAILED";
        if (counts.get("unknown") > 0) return "UNKNOWN";
        return "PASSED";
    }

    private static String sourceFingerprint(List<Map<String, Object>> scenarios) {
        String source = scenarios.stream()
                .map(item -> asMap(item.get("identity")))
                .map(identity -> text(identity.get("semanticKey")) + "|" + text(identity.get("exactSourceKey")))
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return ScenarioIdentity.shortHash(source);
    }

    private static Map<String, Object> comparisonMetadata(
            Map<String, Object> configuration,
            Map<String, Object> environment,
            Map<String, Object> sourceProvenance,
            Map<String, Object> index
    ) {
        Map<String, Object> meta = new LinkedHashMap<>();
        copyEffective(configuration, meta, "pkb_browser", "browser");
        copyEffective(configuration, meta, "pkb_environment", "environment");
        copyEffective(configuration, meta, "pkb_tags", "tags");
        copyEffective(configuration, meta, "cucumber.filter.tags", "cucumberTags");
        copyEffective(configuration, meta, "pkb_features", "features");
        copyEffective(configuration, meta, "cucumber.features", "cucumberFeatures");
        copyEffective(configuration, meta, "pkb_name", "nameFilter");
        copyEffective(configuration, meta, "cucumber.filter.name", "cucumberNameFilter");
        copyEffective(configuration, meta, "pkb_parallel", "parallelism");
        meta.put("configurationHash", index.get("configurationHash"));
        meta.put("environmentHash", index.get("environmentHash"));
        meta.put("selectionFingerprint", index.get("selectionFingerprint"));
        meta.put("sourceFingerprint", index.get("sourceFingerprint"));
        meta.put("dependencyFingerprint", index.get("dependencyFingerprint"));
        meta.put("runProfileFingerprint", index.get("runProfileFingerprint"));
        meta.put("directRunProfile", index.get("directRunProfile"));
        for (String key : List.of("javaVersion", "osName", "osVersion", "timezone")) {
            if (environment.get(key) != null) meta.put(key, environment.get(key));
        }
        Object repositories = sourceProvenance.get("repositories");
        if (repositories instanceof List<?> list) {
            for (Object raw : list) {
                Map<String, Object> repository = asMap(raw);
                String role = text(repository.get("role"));
                if ("consumer".equals(role)) {
                    meta.put("consumerRepository", repository.get("name"));
                    meta.put("consumerCommit", repository.get("commit"));
                    meta.put("consumerBranch", repository.get("branch"));
                    meta.put("consumerDirty", repository.get("dirty"));
                    meta.put("consumerReproducibleFromGit", repository.get("reproducibleFromGit"));
                } else if ("pickleball".equals(role)) {
                    meta.put("pickleballVersion", repository.get("version"));
                    meta.put("pickleballCommit", repository.get("commit"));
                    meta.put("pickleballDirty", repository.get("dirty"));
                    meta.put("pickleballArtifactSha256", repository.get("artifactSha256"));
                }
            }
        }
        return meta;
    }

    private static void copyEffective(
            Map<String, Object> configuration,
            Map<String, Object> target,
            String wantedKey,
            String outputKey
    ) {
        Map<String, Object> effective = asMap(configuration.get("effective"));
        effective.forEach((key, raw) -> {
            if (!key.equalsIgnoreCase(wantedKey)) return;
            Map<String, Object> value = asMap(raw);
            target.put(outputKey, value.isEmpty() ? raw : value.get("value"));
        });
    }

    private static void rebuildMissingFingerprints(Path scenariosRoot) throws IOException {
        if (!Files.isDirectory(scenariosRoot)) return;
        try (var scenarioPaths = Files.list(scenariosRoot)) {
            for (Path scenarioRoot : scenarioPaths.filter(Files::isDirectory).toList()) {
                Path screenshots = scenarioRoot.resolve("screenshots");
                if (!Files.isDirectory(screenshots)) continue;
                Path fingerprints = scenarioRoot.resolve("fingerprints");
                Files.createDirectories(fingerprints);
                try (var images = Files.list(screenshots)) {
                    for (Path image : images.filter(Files::isRegularFile).toList()) {
                        String fileName = image.getFileName().toString();
                        int dot = fileName.lastIndexOf('.');
                        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
                        Path fingerprint = fingerprints.resolve(stem + ".pkbf");
                        if (Files.isRegularFile(fingerprint)) continue;
                        try {
                            VisualFingerprint visual = VisualFingerprint.fromImageBytes(Files.readAllBytes(image));
                            Files.write(fingerprint, visual.toBytes());
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
    }

    private static void rebuildClusters(Path runRoot, List<Map<String, Object>> scenarios) throws IOException {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> scenario : scenarios) {
            String signature = text(scenario.get("failureSignature"));
            if (signature.isBlank()) continue;
            grouped.computeIfAbsent(signature, ignored -> new ArrayList<>())
                    .add(text(scenario.get("scenarioExecutionId")));
        }
        List<Map<String, Object>> clusters = new ArrayList<>();
        grouped.forEach((signature, ids) -> clusters.add(Map.of(
                "failureSignature", signature,
                "scenarioCount", ids.size(),
                "scenarioExecutionIds", ids
        )));
        writeAtomic(runRoot.resolve("clusters.json"), Map.of(
                "schemaVersion", 1,
                "clusters", clusters
        ));
    }

    private static synchronized void writeAtomic(Path target, Object value) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        JSON.writeValue(temp.toFile(), value);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return new LinkedHashMap<>();
        return JSON.readValue(path.toFile(), LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static Object first(Object first, Object fallback) {
        return first == null || text(first).isBlank() ? fallback : first;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
