package tools.dscode.common.reporting.diagnostic;

import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.DiagnosticStepMetadata;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Result;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import static tools.dscode.testengine.PKB_props.PKB_CUCUMBER_CLI_ARGS;
import static tools.dscode.testengine.PKB_props.PKB_CUCUMBER_CLI_FEATURE_SELECTORS;

/** Finishes the remaining 2.1.3 diagnostic-reporting contract without changing normal reporting. */
public privileged aspect Diagnostic213CompletionAspect {
    private final ThreadLocal<String> configurationSource = new ThreadLocal<>();
    private final Map<DiagnosticReporter, RunTraceState> runTraceStates = new ConcurrentHashMap<>();
    private final ThreadLocal<ScenarioIdentity> failureScenario = new ThreadLocal<>();
    private final ThreadLocal<Deque<ScenarioIdentity>> failureNestedScenarios =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<FailureSite> failureSite = new ThreadLocal<>();
    private final Map<String, FailureMetadata> failureMetadataBySignature = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, FailureMetadata>> rebuildingFailureMetadata = new ThreadLocal<>();

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

    before(CurrentScenarioState state):
            execution(void DiagnosticReporter.startScenario(io.cucumber.core.runner.CurrentScenarioState))
            && args(state) {
        failureScenario.set(ScenarioIdentity.from(state));
        failureNestedScenarios.get().clear();
        failureSite.remove();
    }

    before(ScenarioIdentity callee):
            execution(void DiagnosticReporter.beginNested(ScenarioIdentity))
            && args(callee) {
        if (callee != null) failureNestedScenarios.get().push(callee);
    }

    after(): execution(void DiagnosticReporter.endNested(boolean)) {
        Deque<ScenarioIdentity> stack = failureNestedScenarios.get();
        if (!stack.isEmpty()) stack.pop();
    }

    before(StepExtension step, Result result, Throwable error):
            execution(void DiagnosticReporter.endStep(
                    io.cucumber.core.runner.StepExtension,
                    io.cucumber.plugin.event.Result,
                    Throwable))
            && args(step, result, error) {
        if (failureSite.get() != null) return;

        Throwable failure = error != null ? error : result == null ? null : result.getError();
        if (failure == null) return;

        DiagnosticStepMetadata metadata = DiagnosticStepMetadata.from(step);
        if (metadata == null) return;

        Deque<ScenarioIdentity> stack = failureNestedScenarios.get();
        ScenarioIdentity identity = stack.isEmpty() ? failureScenario.get() : stack.peek();
        FailureSite site = failureSite(identity, metadata);
        if (site != null && !site.key.isBlank()) failureSite.set(site);
    }

    String around(Throwable failure):
            execution(private static String DiagnosticReporter.failureSignature(Throwable))
            && args(failure) {
        FailureSite site = failureSite.get();
        String signature = failureSignature(failure, site == null ? "" : site.key);
        failureMetadataBySignature.put(signature, FailureMetadata.from(site));
        return signature;
    }

    void around(Path target, Object value):
            execution(private void DiagnosticReporter.writeJsonAtomic(java.nio.file.Path, Object))
            && args(target, value) {
        proceed(target, enrichFailureMetadata(value, failureMetadataBySignature));
    }

    void around(Path runRoot, List scenarios):
            execution(private static void DiagnosticIndexRebuilder.rebuildClusters(java.nio.file.Path, java.util.List))
            && args(runRoot, scenarios) {
        Map<String, FailureMetadata> previous = rebuildingFailureMetadata.get();
        rebuildingFailureMetadata.set(failureMetadataFromScenarios(scenarios));
        try {
            proceed(runRoot, scenarios);
        } finally {
            if (previous == null) rebuildingFailureMetadata.remove();
            else rebuildingFailureMetadata.set(previous);
        }
    }

    void around(Path target, Object value):
            execution(private static void DiagnosticIndexRebuilder.writeAtomic(java.nio.file.Path, Object))
            && args(target, value) {
        Map<String, FailureMetadata> metadata = rebuildingFailureMetadata.get();
        if (metadata != null && target != null && target.getFileName() != null
                && "clusters.json".equals(target.getFileName().toString())) {
            proceed(target, enrichFailureMetadata(value, metadata));
            return;
        }
        proceed(target, value);
    }

    after(Map scenario) returning(Map compact):
            execution(private static java.util.Map DiagnosticRunComparator.compactScenario(java.util.Map))
            && args(scenario) {
        copyFailureMetadata(scenario, compact);
    }

    after(): execution(void DiagnosticReporter.endScenario(
            io.cucumber.core.runner.CurrentScenarioState,
            boolean,
            Throwable)) {
        failureScenario.remove();
        failureNestedScenarios.remove();
        failureSite.remove();
    }

    public static String failureSignatureForTesting(Throwable failure, String siteKey) {
        return failureSignature(failure, siteKey);
    }

    public static String failureSiteKeyForTesting(
            String featureUri,
            int stepLine,
            String stepText,
            Method method
    ) {
        FailureSite site = failureSite(featureUri, stepLine, stepText, method);
        return site == null ? "" : site.key;
    }

    public static Map<String, Object> failureMetadataForTesting(
            Throwable failure,
            String featureUri,
            int stepLine,
            String stepText,
            Method method
    ) {
        FailureSite site = failureSite(featureUri, stepLine, stepText, method);
        String signature = failureSignature(failure, site == null ? "" : site.key);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("failureSignature", signature);
        FailureMetadata.from(site).addTo(result);
        return result;
    }

    private static String failureSignature(Throwable failure, String siteKey) {
        String type = failure == null ? "" : failure.getClass().getName();
        String message = normalizedFailureMessage(failure == null ? null : failure.getMessage());
        String site = siteKey == null ? "" : siteKey.trim();

        // Preserve the previous signature for failures that have no structured step site.
        if (site.isBlank()) return ScenarioIdentity.shortHash(type + "|" + message);
        return ScenarioIdentity.shortHash("v2|" + type + "|" + message + "|" + site);
    }

    private static FailureSite failureSite(ScenarioIdentity identity, DiagnosticStepMetadata metadata) {
        if (metadata == null) return null;
        return failureSite(
                identity == null ? "" : identity.featureUri(),
                metadata.stepLine(),
                metadata.stepText(),
                metadata.method()
        );
    }

    private static FailureSite failureSite(
            String featureUri,
            int stepLine,
            String stepText,
            Method method
    ) {
        String source = ScenarioIdentity.canonicalSourceUri(featureUri);
        String normalizedStep = normalizedSiteText(stepText);
        String definition = method == null
                ? ""
                : method.getDeclaringClass().getName() + "#" + method.getName();
        if (source.isBlank() && stepLine <= 0 && normalizedStep.isBlank() && definition.isBlank()) return null;

        String location = stepLine > 0
                ? source + ":" + stepLine
                : source + "|step=" + normalizedStep;
        String raw = location + "|" + definition;
        return new FailureSite(ScenarioIdentity.shortHash(raw), source, stepLine, definition);
    }

    private static Object enrichFailureMetadata(Object value, Map<String, FailureMetadata> metadata) {
        if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(entry.getKey(), enrichFailureMetadata(entry.getValue(), metadata));
            }
            Object rawSignature = map.get("failureSignature");
            if (rawSignature != null) {
                FailureMetadata failure = metadata.get(String.valueOf(rawSignature));
                if (failure != null) failure.addTo(copy);
            }
            return copy;
        }
        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) copy.add(enrichFailureMetadata(item, metadata));
            return copy;
        }
        return value;
    }

    private static Map<String, FailureMetadata> failureMetadataFromScenarios(List scenarios) {
        Map<String, FailureMetadata> metadata = new LinkedHashMap<>();
        if (scenarios == null) return metadata;
        for (Object raw : scenarios) {
            if (!(raw instanceof Map<?, ?>)) continue;
            Map<?, ?> scenario = (Map<?, ?>) raw;
            Object rawSignature = scenario.get("failureSignature");
            Object rawVersion = scenario.get("failureSignatureVersion");
            if (rawSignature == null || !(rawVersion instanceof Number)) continue;
            Number version = (Number) rawVersion;

            String siteKey = scenario.get("failureSiteKey") == null
                    ? ""
                    : String.valueOf(scenario.get("failureSiteKey"));
            Map<String, Object> site = new LinkedHashMap<>();
            Object rawSite = scenario.get("failureSite");
            if (rawSite instanceof Map<?, ?>) {
                Map<?, ?> siteMap = (Map<?, ?>) rawSite;
                for (Map.Entry<?, ?> entry : siteMap.entrySet()) {
                    site.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            metadata.put(String.valueOf(rawSignature),
                    new FailureMetadata(version.intValue(), siteKey, site));
        }
        return metadata;
    }

    private static void copyFailureMetadata(Map source, Map target) {
        if (source == null || target == null) return;
        for (String key : List.of("failureSignatureVersion", "failureSiteKey", "failureSite")) {
            if (source.containsKey(key)) target.put(key, source.get(key));
        }
    }

    private static String normalizedFailureMessage(String value) {
        return DiagnosticReporter.sanitizeText(value)
                .replaceAll("\\b\\d+\\b", "#")
                .replaceAll("0x[0-9a-fA-F]+", "0x#")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String normalizedSiteText(String value) {
        return normalizedFailureMessage(value).toLowerCase(Locale.ROOT);
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

    private static final class FailureSite {
        final String key;
        final String feature;
        final int stepLine;
        final String definition;

        FailureSite(String key, String feature, int stepLine, String definition) {
            this.key = key == null ? "" : key;
            this.feature = feature == null ? "" : feature;
            this.stepLine = stepLine;
            this.definition = definition == null ? "" : definition;
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            if (!feature.isBlank()) result.put("feature", feature);
            if (stepLine > 0) result.put("stepLine", stepLine);
            if (!definition.isBlank()) result.put("definition", definition);
            return result;
        }
    }

    private static final class FailureMetadata {
        final int version;
        final String siteKey;
        final Map<String, Object> site;

        FailureMetadata(int version, String siteKey, Map<String, Object> site) {
            this.version = version;
            this.siteKey = siteKey == null ? "" : siteKey;
            this.site = site == null ? Map.of() : new LinkedHashMap<>(site);
        }

        static FailureMetadata from(FailureSite site) {
            return site == null
                    ? new FailureMetadata(1, "", Map.of())
                    : new FailureMetadata(2, site.key, site.asMap());
        }

        void addTo(Map target) {
            if (target == null) return;
            target.put("failureSignatureVersion", version);
            if (!siteKey.isBlank()) target.put("failureSiteKey", siteKey);
            if (!site.isEmpty()) target.put("failureSite", new LinkedHashMap<>(site));
        }
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
        synchronized (reporter) {
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
