package tools.dscode.common.reporting.diagnostic;

import org.junit.platform.engine.ExecutionRequest;
import tools.dscode.testengine.DynamicSuiteEngine;
import tools.dscode.testengine.PickleballRunner;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import static tools.dscode.testengine.PKB_props.PKB_CUCUMBER_CLI_ARGS;
import static tools.dscode.testengine.PKB_props.PKB_CUCUMBER_CLI_FEATURE_SELECTORS;

/** Finishes the remaining 2.1.3 diagnostic-reporting contract without changing normal reporting. */
public privileged aspect Diagnostic213CompletionAspect {
    private final ThreadLocal<String> configurationSource = new ThreadLocal<>();
    private final Map<DiagnosticReporter, RunTraceState> runTraceStates = new ConcurrentHashMap<>();

    before(String resource):
            execution(private void PickleballRunner.mergeResourcePropertiesIfMissing(String))
            && args(resource) {
        configurationSource.set("resource-if-missing:" + resource);
    }

    before(String resource):
            execution(private void PickleballRunner.mergeResourcePropertiesOverwriting(String))
            && args(resource) {
        configurationSource.set("resource:" + resource);
    }

    before(): execution(private void PickleballRunner.mergeAllSystemProperties()) {
        configurationSource.set("system-properties");
    }

    after(): (execution(private void PickleballRunner.mergeResourcePropertiesIfMissing(String))
            || execution(private void PickleballRunner.mergeResourcePropertiesOverwriting(String))
            || execution(private void PickleballRunner.mergeAllSystemProperties())) {
        configurationSource.remove();
    }

    after(String aliasKey, String canonicalKey, String value):
            execution(private void PickleballRunner.putCliOverride(String, String, String))
            && args(aliasKey, canonicalKey, value) {
        if (value == null || value.isBlank()) return;
        String trimmed = value.trim();
        ConfigurationProvenance.captureSupplied("cucumber-cli", aliasKey, trimmed);
        ConfigurationProvenance.captureSupplied("cucumber-cli", canonicalKey, trimmed);
    }

    after(String key, String value):
            execution(private void PickleballRunner.putCliReference(String, String))
            && args(key, value) {
        if (value == null || value.isBlank()) return;
        ConfigurationProvenance.captureSupplied("cucumber-cli", key, value.trim());
    }

    after(PickleballRunner runner, String[] argv):
            execution(public void PickleballRunner.captureCucumberCliArgs(String[]))
            && this(runner) && args(argv) {
        ConfigurationProvenance.capture("cucumber-cli", runner.values());
        refreshCliConfiguration(runner);
    }

    after(Object key, Object value) returning(Object previous):
            call(* java.util.Map+.putIfAbsent(Object, Object))
            && withincode(private void PickleballRunner.mergeResourcePropertiesIfMissing(String))
            && args(key, value) {
        if (previous == null) captureSuppliedConfiguration(key, value);
    }

    after(Object key, Object value):
            call(* java.util.Map+.put(Object, Object))
            && (withincode(private void PickleballRunner.mergeResourcePropertiesOverwriting(String))
            || withincode(private void PickleballRunner.mergeAllSystemProperties()))
            && args(key, value) {
        captureSuppliedConfiguration(key, value);
    }

    private void captureSuppliedConfiguration(Object key, Object value) {
        String source = configurationSource.get();
        if (source == null || key == null) return;
        ConfigurationProvenance.captureSupplied(source, String.valueOf(key), value == null ? null : String.valueOf(value));
    }

    after(SourceProvenance provenance, Method method, String codeLocation) returning(Map definition):
            execution(public java.util.Map SourceProvenance.definitionSource(java.lang.reflect.Method, String))
            && this(provenance) && args(method, codeLocation) {
        if (method == null || definition == null
                || !"NON_PICKLEBALL".equals(String.valueOf(definition.get("origin")))
                || !"external".equals(String.valueOf(definition.get("repository")))) return;

        Path source = findConsumerSource(provenance, method.getDeclaringClass());
        if (source == null) return;

        String relative = provenance.relativeToConsumer(source);
        if (relative.isBlank()) return;
        definition.put("repository", "consumer");
        definition.put("sourcePath", relative);
        definition.put("sourceSha256", SourceProvenance.sha256(source));
        definition.put("commit", provenance.consumer.commit().isBlank() ? null : provenance.consumer.commit());
        definition.put("reproducibleFromGit", provenance.consumer.reproducibleFromGit());
    }

    after(SourceProvenance provenance, String featureUri, long line) returning(Map source):
            execution(public java.util.Map SourceProvenance.featureSource(String, long))
            && this(provenance) && args(featureUri, line) {
        if (source == null || source.get("path") != null || featureUri == null
                || !featureUri.startsWith("classpath:")) return;

        Path feature = findConsumerFeature(provenance, featureUri);
        if (feature == null) return;

        String relative = provenance.relativeToConsumer(feature);
        if (relative.isBlank()) return;
        source.put("path", relative);
        source.put("sha256", SourceProvenance.sha256(feature));
    }

    private Path findConsumerFeature(SourceProvenance provenance, String featureUri) {
        if (provenance.consumerRoot == null || featureUri == null) return null;

        String raw = featureUri.substring("classpath:".length()).replace('\\', '/');
        while (raw.startsWith("/")) raw = raw.substring(1);

        List<Path> roots = new ArrayList<>();
        try {
            Path working = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            if (working.startsWith(provenance.consumerRoot)) roots.add(working);
        } catch (Throwable ignored) {
        }
        roots.add(provenance.consumerRoot);

        for (Path root : roots) {
            for (String prefix : List.of("src/test/resources/", "src/main/resources/", "")) {
                Path candidate = root.resolve(prefix + raw).toAbsolutePath().normalize();
                if (candidate.startsWith(provenance.consumerRoot) && Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }

        String testSuffix = "/src/test/resources/" + raw;
        String mainSuffix = "/src/main/resources/" + raw;
        try (var paths = Files.find(provenance.consumerRoot, 7,
                (path, attrs) -> attrs.isRegularFile()
                        && (path.toString().replace('\\', '/').endsWith(testSuffix)
                        || path.toString().replace('\\', '/').endsWith(mainSuffix)))) {
            return paths
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(path -> path.startsWith(provenance.consumerRoot))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path findConsumerSource(SourceProvenance provenance, Class<?> declaringClass) {
        if (provenance.consumerRoot == null || declaringClass == null) return null;
        String classPath = declaringClass.getName().replace('.', '/').replaceAll("\\$.*$", "") + ".java";

        Path moduleRoot = moduleRoot(declaringClass);
        Path found = findConventionalSource(provenance.consumerRoot, moduleRoot, classPath);
        if (found != null) return found;

        try (var paths = Files.find(provenance.consumerRoot, 8,
                (path, attrs) -> attrs.isRegularFile()
                        && path.toString().replace('\\', '/').endsWith(classPath)
                        && isConventionalJavaSource(path))) {
            return paths
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .filter(path -> path.startsWith(provenance.consumerRoot))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path moduleRoot(Class<?> type) {
        try {
            URL location = type.getProtectionDomain().getCodeSource().getLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) return null;
            Path classes = Path.of(location.toURI()).toAbsolutePath().normalize();
            if (!Files.isDirectory(classes)) return null;
            Path parent = classes.getParent();
            if (parent != null && "target".equals(String.valueOf(parent.getFileName()))) return parent.getParent();
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Path findConventionalSource(Path consumerRoot, Path moduleRoot, String classPath) {
        List<Path> roots = new ArrayList<>();
        if (moduleRoot != null && moduleRoot.startsWith(consumerRoot)) roots.add(moduleRoot);
        roots.add(consumerRoot);
        for (Path root : roots) {
            for (String prefix : List.of("src/test/java/", "src/main/java/")) {
                Path candidate = root.resolve(prefix + classPath).toAbsolutePath().normalize();
                if (candidate.startsWith(consumerRoot) && Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean isConventionalJavaSource(Path path) {
        String normalized = path.toString().replace('\\', '/');
        return normalized.contains("/src/test/java/") || normalized.contains("/src/main/java/");
    }

    void around(DiagnosticReporter reporter, Path file, Map event):
            execution(private void DiagnosticReporter.append(java.nio.file.Path, java.util.Map))
            && this(reporter) && args(file, event) {
        if (reporter.finished && !"run_end".equals(String.valueOf(event.get("type")))) return;
        if (!file.equals(reporter.runEvents) || !DiagnosticReporter.isDeepTrace(event)) {
            proceed(reporter, file, event);
            return;
        }

        RunTraceState state = runTraceStates.computeIfAbsent(reporter, ignored -> new RunTraceState());
        long seq = event.get("eventSeq") instanceof Number number ? number.longValue() : 0L;
        state.eventCount++;
        if (state.eventSeqFirst == 0 || (seq > 0 && seq < state.eventSeqFirst)) state.eventSeqFirst = seq;
        if (seq > state.eventSeqLast) state.eventSeqLast = seq;
        state.path = "run-trace.jsonl";
        state.encoding = "identity";
        proceed(reporter, reporter.runRoot.resolve("run-trace.jsonl"), event);
    }

    before(DiagnosticReporter reporter):
            execution(void DiagnosticReporter.finishRun()) && this(reporter) {
        if (!reporter.finished) finalizeRunTrace(reporter);
    }

    after(DiagnosticReporter reporter):
            execution(private void DiagnosticReporter.writeRunIndex(String, String)) && this(reporter) {
        enrichRunIndex(reporter);
    }

    void around(Map summary, List events):
            execution(private static void DiagnosticIndexRebuilder.recoverStepMetadata(java.util.Map, java.util.List))
            && args(summary, events) {
        java.util.Set<String> preserved = new java.util.TreeSet<>();
        Object existing = summary.get("nativeCapabilitiesObserved");
        if (existing instanceof List list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) preserved.add(String.valueOf(item));
            }
        }

        proceed(summary, events);

        Object rebuilt = summary.get("nativeCapabilitiesObserved");
        if (rebuilt instanceof List list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) preserved.add(String.valueOf(item));
            }
        }
        for (Object raw : events) {
            if (!(raw instanceof Map event)) continue;
            String type = String.valueOf(event.get("type"));
            if ("nested_scenario_start".equals(type)) preserved.add("scenario.nested");
            if ("screenshot".equals(type)) preserved.add("browser.screenshot");
        }
        summary.put("nativeCapabilitiesObserved", List.copyOf(preserved));
    }

    after(Path runRoot) returning(Map index):
            execution(public static java.util.Map DiagnosticIndexRebuilder.rebuildRunIndex(java.nio.file.Path))
            && args(runRoot) {
        Map<String, Object> trace = recoverRunTraceEvidence(runRoot);
        if (trace.isEmpty()) return;
        index.put("runTraceEvidence", trace);
        Map<String, Object> paths = new LinkedHashMap<>(asMap(index.get("paths")));
        paths.put("runTrace", trace.get("path"));
        index.put("paths", paths);
        try {
            DiagnosticIndexRebuilder.writeAtomic(runRoot.resolve("run-index.json"), index);
        } catch (Exception ignored) {
        }
    }

    after(): execution(public void DynamicSuiteEngine.execute(ExecutionRequest))
            && !cflow(execution(public static byte io.cucumber.core.cli.Main.run(String[], ClassLoader))) {
        finishDiagnosticRun();
    }

    after(): execution(public static byte io.cucumber.core.cli.Main.run(String[], ClassLoader)) {
        finishDiagnosticRun();
    }

    after(DiagnosticReporter reporter) returning(Map metadata):
            execution(private java.util.Map DiagnosticReporter.comparisonMetadata())
            && this(reporter) {
        copyComparisonConfig(reporter, metadata, PKB_CUCUMBER_CLI_ARGS, "cucumberCliArgs");
        copyComparisonConfig(reporter, metadata, PKB_CUCUMBER_CLI_FEATURE_SELECTORS, "cucumberCliFeatureSelectors");
    }

    private void finishDiagnosticRun() {
        if (DiagnosticRuntime.isDiagnostic()) DiagnosticRuntime.finishRun();
    }

    private void refreshCliConfiguration(PickleballRunner runner) {
        if (!DiagnosticRuntime.isDiagnostic()) return;
        DiagnosticReporter reporter = DiagnosticRuntime.reporter;
        if (reporter == null || reporter.finished) return;
        try {
            reporter.effectiveConfig = Map.copyOf(runner.values());
            reporter.writeConfiguration();
            reporter.writeManifest("RUNNING", "IN_PROGRESS", null);
            reporter.writeRunIndex("RUNNING", "IN_PROGRESS");
            reporter.writeRunCatalog();
        } catch (Throwable error) {
            reporter.failEvidence("refresh Cucumber CLI diagnostic configuration", error);
        }
    }

    private static void copyComparisonConfig(
            DiagnosticReporter reporter,
            Map metadata,
            String configKey,
            String outputKey
    ) {
        Object value = reporter.effectiveConfig.get(configKey);
        if (value != null && !String.valueOf(value).isBlank()) {
            metadata.put(outputKey, String.valueOf(value));
        }
    }

    private void finalizeRunTrace(DiagnosticReporter reporter) {
        RunTraceState state = runTraceStates.get(reporter);
        Path raw = reporter.runRoot.resolve("run-trace.jsonl");
        Path gzip = reporter.runRoot.resolve("run-trace.jsonl.gz");
        if (state == null || state.eventCount == 0 || !Files.isRegularFile(raw)) return;

        if (!ReportRetentionPolicy.writeAutomaticRunFiles()) {
            deleteIfExists(raw);
            deleteIfExists(gzip);
            state.retained = false;
            state.path = null;
            return;
        }

        Path temp = reporter.runRoot.resolve("run-trace.jsonl.gz.tmp");
        try (InputStream in = Files.newInputStream(raw);
             OutputStream fileOut = Files.newOutputStream(temp);
             GZIPOutputStream out = new GZIPOutputStream(fileOut)) {
            in.transferTo(out);
        } catch (Throwable error) {
            deleteIfExists(temp);
            state.path = "run-trace.jsonl";
            state.encoding = "identity";
            reporter.failEvidence("compress run trace", error);
            return;
        }

        try {
            Files.move(temp, gzip, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(raw);
            state.path = "run-trace.jsonl.gz";
            state.encoding = "gzip";
        } catch (Throwable error) {
            deleteIfExists(temp);
            state.path = "run-trace.jsonl";
            state.encoding = "identity";
            reporter.failEvidence("finalize compressed run trace", error);
        }
    }

    private void enrichRunIndex(DiagnosticReporter reporter) {
        RunTraceState state = runTraceStates.get(reporter);
        if (state == null || !state.retained || state.eventCount == 0 || state.path == null) return;
        Path indexPath = reporter.runRoot.resolve("run-index.json");
        if (!Files.isRegularFile(indexPath)) return;
        try {
            Map<String, Object> index = DiagnosticReporter.JSON.readValue(indexPath.toFile(), LinkedHashMap.class);
            Map<String, Object> trace = state.asMap();
            index.put("runTraceEvidence", trace);
            Map<String, Object> paths = new LinkedHashMap<>(asMap(index.get("paths")));
            paths.put("runTrace", trace.get("path"));
            index.put("paths", paths);
            reporter.writeJsonAtomic(indexPath, index);
        } catch (Throwable error) {
            reporter.failEvidence("write run trace index metadata", error);
        }
    }

    private static Map<String, Object> recoverRunTraceEvidence(Path runRoot) {
        try {
            Path gzip = runRoot.resolve("run-trace.jsonl.gz");
            Path raw = runRoot.resolve("run-trace.jsonl");
            boolean compressed = Files.isRegularFile(gzip);
            List<Map<String, Object>> events = compressed
                    ? DiagnosticIndexRebuilder.readJsonLinesGzip(gzip)
                    : DiagnosticIndexRebuilder.readJsonLines(raw);
            if (events.isEmpty()) return Map.of();
            long first = events.stream().map(event -> event.get("eventSeq"))
                    .filter(Number.class::isInstance).map(Number.class::cast)
                    .mapToLong(Number::longValue).min().orElse(0);
            long last = events.stream().map(event -> event.get("eventSeq"))
                    .filter(Number.class::isInstance).map(Number.class::cast)
                    .mapToLong(Number::longValue).max().orElse(0);
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("path", compressed ? "run-trace.jsonl.gz" : "run-trace.jsonl");
            trace.put("contentType", "application/x-ndjson");
            trace.put("contentEncoding", compressed ? "gzip" : "identity");
            trace.put("eventCount", events.size());
            trace.put("eventSeqFirst", first);
            trace.put("eventSeqLast", last);
            return trace;
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private static final class RunTraceState {
        long eventCount;
        long eventSeqFirst;
        long eventSeqLast;
        boolean retained = true;
        String path;
        String encoding = "identity";

        Map<String, Object> asMap() {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("path", path);
            trace.put("contentType", "application/x-ndjson");
            trace.put("contentEncoding", encoding);
            trace.put("eventCount", eventCount);
            trace.put("eventSeqFirst", eventSeqFirst);
            trace.put("eventSeqLast", eventSeqLast);
            return trace;
        }
    }
}
