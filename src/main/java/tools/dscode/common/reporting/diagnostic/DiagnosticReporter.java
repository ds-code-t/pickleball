package tools.dscode.common.reporting.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.cucumber.core.runner.CurrentScenarioState;
import io.cucumber.core.runner.DiagnosticStepMetadata;
import io.cucumber.core.runner.StepExtension;
import io.cucumber.plugin.event.Result;
import io.cucumber.plugin.event.Status;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import tools.dscode.common.reporting.logging.Entry;
import tools.dscode.common.reporting.logging.Level;
import tools.dscode.testengine.PKB_props;

import static tools.dscode.testengine.PKB_props.PKB_DIAGNOSTIC_OUTPUT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.regex.Pattern;

final class DiagnosticReporter {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_-]?key|authorization|credential|cookie|access[_-]?key|private[_-]?key)(\\s*[:=]\\s*)([^,\\s}\\]]+)"
    );
    private static final int MAX_TEXT_CHARS = 20_000;
    private static final int MAX_COLLECTION_ITEMS = 200;

    private final String runId = RUN_STAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    private final Instant startedAt = Instant.now();
    private final long monotonicOriginNanos = System.nanoTime();
    private final Path runsRoot;
    private final Path runRoot;
    private final Path runEvents;
    private final AtomicLong eventSeq = new AtomicLong();
    private final Map<Path, Object> appendLocks = new ConcurrentHashMap<>();
    private final Map<String, ScenarioSummary> scenarios = new ConcurrentHashMap<>();
    private final ThreadLocal<ScenarioContext> current = new ThreadLocal<>();
    private final ThreadLocal<Deque<NestedContext>> nested = ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Deque<StepContext>> steps = ThreadLocal.withInitial(ArrayDeque::new);
    private final Set<String> runCapabilities = ConcurrentHashMap.newKeySet();
    private final SourceProvenance sourceProvenance;
    private final boolean directRunProfile;
    private volatile boolean partial;
    private volatile boolean finished;
    private volatile Map<String, String> effectiveConfig = Map.of();
    private volatile String configurationHash = "";
    private volatile String environmentHash = "";
    private volatile String sourceProvenanceHash = "";
    private volatile String runProfile = "";
    private volatile String runProfileFingerprint = "";
    private final Map<String, Object> startResources = resourceSnapshot();
    private volatile Map<String, Object> endResources = Map.of();

    DiagnosticReporter(Map<String, String> values) {
        this(values, false);
    }

    DiagnosticReporter(Map<String, String> values, boolean directRunProfile) {
        this.effectiveConfig = values == null ? Map.of() : Map.copyOf(values);
        this.directRunProfile = directRunProfile;
        this.sourceProvenance = SourceProvenance.capture(values);
        this.runsRoot = resolveRunsRoot(values);
        this.runRoot = runsRoot.resolve(runId);
        this.runEvents = runRoot.resolve("run-events.jsonl");
        try {
            Files.createDirectories(runRoot.resolve("scenarios"));
            recoverIncompleteRuns(runsRoot, runRoot);
            writeConfiguration();
            writeEnvironment();
            writeSourceProvenance();
            writeManifest("RUNNING", "IN_PROGRESS", null);
            writeRunIndex("RUNNING", "IN_PROGRESS");
            writeRunCatalog();
            Map<String, Object> runStart = new LinkedHashMap<>();
            runStart.put("runId", runId);
            runStart.put("reportingMode", "diagnostic");
            runStart.put("reportRetention", ReportRetentionPolicy.configuredValue());
            runStart.put("resources", startResources);
            runStart.put("sourceProvenanceHash", sourceProvenanceHash);
            runStart.put("source", sourceProvenance.comparisonMetadata());
            append(runEvents, event("run_start", runStart));
        } catch (Throwable t) {
            failEvidence("initialize diagnostic run", t);
        }
    }

    void startScenario(CurrentScenarioState state) {
        String executionId = UUID.randomUUID().toString();
        ScenarioIdentity identity = ScenarioIdentity.from(state);
        Path root = runRoot.resolve("scenarios").resolve(executionId);
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            failEvidence("create scenario evidence directory", e);
        }

        ScenarioContext context = new ScenarioContext(executionId, identity, root, Instant.now());
        current.set(context);
        nested.get().clear();
        steps.get().clear();
        ScenarioSummary summary = new ScenarioSummary(
                executionId,
                identity,
                context.startedAt,
                configurationHash,
                sourceProvenance.featureSource(identity.featureUri(), identity.scenarioLine())
        );
        scenarios.put(executionId, summary);

        append(runEvents, event("scenario_start", Map.of(
                "scenarioExecutionId", executionId,
                "identity", identity.asMap()
        )));
        recordScenarioEvent("scenario_start", Map.of("identity", identity.asMap()));
        writeScenarioSummary(summary);
        writeRunIndex("RUNNING", "IN_PROGRESS");
    }

    void endScenario(CurrentScenarioState state, boolean interrupted, Throwable terminalError) {
        ScenarioContext context = current.get();
        if (context == null) return;
        boolean failed = state != null && state.isScenarioFailed();
        ScenarioSummary summary = scenarios.get(context.executionId);
        if (summary == null) return;

        if (failed && state != null && !state.stepFailures.isEmpty()) {
            Throwable failure = state.stepFailures.getFirst();
            summary.failureClass = failure.getClass().getName();
            summary.failureMessage = sanitizeText(failure.getMessage());
            summary.failureSignature = failureSignature(failure);
            recordScenarioEvent("failure", Map.of(
                    "failures", failureDetails(state.stepFailures)
            ));
        } else if (terminalError != null) {
            summary.failureClass = terminalError.getClass().getName();
            summary.failureMessage = sanitizeText(terminalError.getMessage());
            summary.failureSignature = failureSignature(terminalError);
            recordScenarioEvent("failure", Map.of(
                    "failures", List.of(failureDetails(terminalError))
            ));
        }

        recordScenarioEvent("scenario_end", Map.of(
                "outcome", failed ? "FAILED" : interrupted ? "UNKNOWN" : "PASSED",
                "completion", interrupted ? "INTERRUPTED" : "COMPLETE"
        ));
        summary.endedAt = Instant.now();
        summary.durationMillis = Math.max(0, summary.endedAt.toEpochMilli() - summary.startedAt.toEpochMilli());
        summary.outcome = failed ? "FAILED" : interrupted ? "UNKNOWN" : "PASSED";
        summary.completion = interrupted ? "INTERRUPTED" : "COMPLETE";
        summary.lastEventSeq = eventSeq.get();
        summary.eventCount = context.scenarioSeq.get();
        summary.traceEventCount = context.traceEventCount.get();
        summary.traceEventSeqFirst = context.traceEventSeqFirst;
        summary.traceEventSeqLast = context.traceEventSeqLast;
        summary.detailedEvidenceRetained = ReportRetentionPolicy.keepScenarioDetails(failed, interrupted);
        if (!summary.detailedEvidenceRetained) {
            summary.representativeScreenshots.clear();
        } else if (context.previousScreenshotId != null
                && context.previousImagePath != null
                && context.previousFingerprintPath != null
                && summary.representativeScreenshots.size() < 8
                && summary.representativeScreenshots.stream()
                .noneMatch(item -> context.previousScreenshotId.equals(item.get("screenshotId")))) {
            Map<String, Object> representative = new LinkedHashMap<>();
            representative.put("screenshotId", context.previousScreenshotId);
            representative.put("reason", "FINAL_VISUAL_STATE");
            representative.put("image", relative(context.previousImagePath));
            representative.put("fingerprint", relative(context.previousFingerprintPath));
            summary.representativeScreenshots.add(representative);
        }
        if (summary.detailedEvidenceRetained) {
            compressTrace(context, summary);
        }
        writeScenarioSummary(summary);

        append(runEvents, event("scenario_end", Map.of(
                "scenarioExecutionId", context.executionId,
                "outcome", summary.outcome,
                "completion", summary.completion,
                "eventCount", summary.eventCount,
                "detailedEvidenceRetained", summary.detailedEvidenceRetained
        )));

        if (!summary.detailedEvidenceRetained) pruneDenseEvidence(context.root);
        writeRunIndex("RUNNING", "IN_PROGRESS");
        current.remove();
        nested.remove();
        steps.remove();
    }

    void recordEntry(Entry entry, String phase) {
        if (entry == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("entryId", String.valueOf(entry.id));
        data.put("parentEntryId", entry.parent == null ? null : String.valueOf(entry.parent.id));
        data.put("level", entry.level == null ? null : entry.level.name());
        data.put("status", entry.status == null ? null : entry.status.name());
        data.put("text", sanitizeText(entry.text));
        data.put("tags", sanitize(entry.tags, 0));
        data.put("fields", sanitize(entry.fields, 0));
        if (entry.attachments != null && !entry.attachments.isEmpty()) {
            data.put("attachmentCount", entry.attachments.size());
        }
        recordScenarioOrRunEvent("log", data);
    }

    void recordFilteredLog(Level level, String message) {
        recordScenarioOrRunEvent("log", Map.of(
                "phase", "instant",
                "level", level.name(),
                "text", sanitizeText(message)
        ));
    }

    void beginStep(StepExtension step) {
        ScenarioContext scenario = current.get();
        if (scenario == null || step == null) return;
        ScenarioSummary summary = scenarios.get(scenario.executionId);
        if (summary == null) return;
        long stepNumber = summary.stepExecuted.incrementAndGet();
        StepContext context = new StepContext(step, stepNumber);
        steps.get().push(context);
    }

    void bindStepDefinition(StepExtension step) {
        Deque<StepContext> stack = steps.get();
        if (stack.isEmpty()) return;
        bindDefinition(stack.peek(), DiagnosticStepMetadata.from(step));
    }

    void endStep(StepExtension step, Result result, Throwable error) {
        Deque<StepContext> stack = steps.get();
        if (stack.isEmpty()) return;
        StepContext context = stack.pop();
        ScenarioContext scenario = current.get();
        if (scenario == null) return;
        ScenarioSummary summary = scenarios.get(scenario.executionId);
        if (summary == null) return;

        if (context.definition.isEmpty()) {
            bindDefinition(context, DiagnosticStepMetadata.from(step));
        }
        String status = result == null || result.getStatus() == null
                ? error == null ? "UNKNOWN" : "FAILED"
                : result.getStatus().name();
        switch (status) {
            case "PASSED" -> summary.stepPassed.incrementAndGet();
            case "FAILED", "AMBIGUOUS" -> summary.stepFailed.incrementAndGet();
            case "SKIPPED" -> summary.stepSkipped.incrementAndGet();
            default -> summary.stepOther.incrementAndGet();
        }
        String origin = String.valueOf(context.definition.getOrDefault("origin", "UNKNOWN"));
        if ("PICKLEBALL".equals(origin)) summary.stepPickleball.incrementAndGet();
        else if ("NON_PICKLEBALL".equals(origin)) summary.stepNonPickleball.incrementAndGet();

        List<String> capabilities = context.capabilities.stream().sorted().toList();
        for (String capability : capabilities) {
            summary.capabilities.add(capability);
            summary.capabilityStepCounts.merge(capability, 1L, Long::sum);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepNumber", context.stepNumber);
        data.put("nestingLevel", step == null ? 0 : step.getNestingLevel());
        data.put("keyword", context.keyword);
        data.put("text", context.text);
        data.put("source", context.source);
        data.put("definition", context.definition);
        data.put("status", status);
        if (result != null && result.getDuration() != null) data.put("durationMillis", result.getDuration().toMillis());
        Throwable failure = error != null ? error : result == null ? null : result.getError();
        if (failure != null) {
            data.put("errorClass", failure.getClass().getName());
            data.put("errorMessage", sanitizeText(failure.getMessage()));
        }
        data.put("nativeCapabilitiesObserved", capabilities);
        recordScenarioEvent("step", data);
    }

    void observeCapability(String capability) {
        if (capability == null || capability.isBlank()) return;
        String normalized = capability.trim().toLowerCase(Locale.ROOT);
        runCapabilities.add(normalized);
        ScenarioContext scenario = current.get();
        if (scenario == null) return;
        ScenarioSummary summary = scenarios.get(scenario.executionId);
        if (summary != null) summary.capabilities.add(normalized);
        Deque<StepContext> stack = steps.get();
        if (!stack.isEmpty()) stack.peek().capabilities.add(normalized);
    }

    private void bindDefinition(StepContext context, DiagnosticStepMetadata metadata) {
        if (context == null || metadata == null) return;
        context.definition = sourceProvenance.definitionSource(metadata.method(), metadata.codeLocation());
        context.stepLine = metadata.stepLine();
        context.keyword = metadata.keyword();
        context.text = metadata.stepText();
        ScenarioContext scenario = current.get();
        if (scenario == null) return;
        ScenarioIdentity identity = nested.get().isEmpty()
                ? scenario.identity
                : nested.get().peek().identity;
        context.source = sourceProvenance.featureSource(identity.featureUri(), context.stepLine);
    }

    void beginNested(ScenarioIdentity callee) {
        observeCapability("scenario.nested");
        ScenarioContext context = current.get();
        if (context == null) return;
        ScenarioIdentity caller = nested.get().isEmpty() ? context.identity : nested.get().peek().identity;
        NestedContext nestedContext = new NestedContext(UUID.randomUUID().toString(), callee);
        nested.get().push(nestedContext);
        recordScenarioEvent("nested_scenario_start", Map.of(
                "invocationId", nestedContext.invocationId,
                "caller", caller.asMap(),
                "callee", callee.asMap()
        ));
    }

    void endNested(boolean failed) {
        Deque<NestedContext> stack = nested.get();
        if (stack.isEmpty()) return;
        NestedContext context = stack.pop();
        recordScenarioEvent("nested_scenario_end", Map.of(
                "invocationId", context.invocationId,
                "callee", context.identity.asMap(),
                "outcome", failed ? "FAILED" : "PASSED"
        ));
    }

    void captureScreenshot(WebDriver driver, String name) {
        if (driver == null) return;
        try {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            captureScreenshotBytes(
                    png,
                    name == null || name.isBlank() ? "Screenshot" : name,
                    safeDriverValue(driver, true),
                    safeDriverValue(driver, false)
            );
        } catch (Throwable t) {
            recordScenarioOrRunEvent("screenshot_error", Map.of("message", sanitizeText(t.getMessage())));
        }
    }

    void captureScreenshotBytes(byte[] png, String name) {
        captureScreenshotBytes(png, name, "", "");
    }

    private void captureScreenshotBytes(byte[] png, String name, String currentUrl, String title) {
        ScenarioContext context = current.get();
        if (context == null || png == null || png.length == 0) return;
        long number = context.screenshotSeq.incrementAndGet();
        String stem = String.format(Locale.ROOT, "%04d-%s", number, safeFileName(name));
        Path image = context.root.resolve("screenshots").resolve(stem + ".png");
        Path fingerprint = context.root.resolve("fingerprints").resolve(stem + ".pkbf");
        try {
            Files.createDirectories(image.getParent());
            Files.createDirectories(fingerprint.getParent());
            observeCapability("browser.screenshot");
            Files.write(image, png);
            VisualFingerprint visual = VisualFingerprint.fromImageBytes(png);
            Files.write(fingerprint, visual.toBytes());
            Map<String, Object> screenshotEvent = new LinkedHashMap<>();
            screenshotEvent.put("screenshotId", stem);
            screenshotEvent.put("name", name);
            screenshotEvent.put("image", relative(image));
            screenshotEvent.put("fingerprint", relative(fingerprint));
            screenshotEvent.put("fingerprintVersion", VisualFingerprint.VERSION);
            screenshotEvent.put("width", visual.width());
            screenshotEvent.put("height", visual.height());
            screenshotEvent.put("imageBytes", png.length);
            screenshotEvent.put("fingerprintBytes", Files.size(fingerprint));
            if (!currentUrl.isBlank()) screenshotEvent.put("currentUrl", sanitizeText(currentUrl));
            if (!title.isBlank()) screenshotEvent.put("pageTitle", sanitizeText(title));
            String representativeReason = number == 1 ? "FIRST_VISUAL_STATE" : null;
            if (context.previousFingerprint != null) {
                VisualFingerprintComparator.Result comparison =
                        VisualFingerprintComparator.compare(context.previousFingerprint, visual);
                screenshotEvent.put("previousScreenshotId", context.previousScreenshotId);
                screenshotEvent.put("comparisonToPrevious", comparison.asMap());
                if (comparison.category() == VisualFingerprintComparator.Category.VERY_DIFFERENT
                        || comparison.category() == VisualFingerprintComparator.Category.SOMEWHAT_SIMILAR) {
                    representativeReason = "VISUAL_CHANGE_" + comparison.category().name();
                }
            }
            if (name != null && name.toLowerCase(Locale.ROOT).contains("failure")) {
                representativeReason = "FAILURE";
            }
            ScenarioSummary summary = scenarios.get(context.executionId);
            if (summary != null) {
                summary.screenshotCount = number;
                if (representativeReason != null && summary.representativeScreenshots.size() < 8) {
                    Map<String, Object> representative = new LinkedHashMap<>();
                    representative.put("screenshotId", stem);
                    representative.put("reason", representativeReason);
                    representative.put("image", relative(image));
                    representative.put("fingerprint", relative(fingerprint));
                    summary.representativeScreenshots.add(representative);
                }
            }
            context.previousFingerprint = visual;
            context.previousScreenshotId = stem;
            context.previousImagePath = image;
            context.previousFingerprintPath = fingerprint;
            recordScenarioEvent("screenshot", screenshotEvent);
        } catch (Throwable t) {
            failEvidence("capture screenshot", t);
        }
    }

    synchronized void finishRun() {
        if (finished) return;
        finished = true;
        String outcome = runOutcome();
        endResources = resourceSnapshot();
        try {
            Map<String, Object> runEnd = new LinkedHashMap<>();
            runEnd.put("outcome", outcome);
            runEnd.put("completion", "COMPLETE");
            runEnd.put("evidenceIntegrity", partial ? "PARTIAL" : "COMPLETE");
            runEnd.put("resources", endResources);
            append(runEvents, event("run_end", runEnd));
            writeClusters();
            writeRunIndex(outcome, "COMPLETE");
            writeManifest(outcome, "COMPLETE", Instant.now());
            writeRunCatalog();
        } catch (Throwable t) {
            failEvidence("finish diagnostic run", t);
        }
    }

    String runId() { return runId; }
    Path runRoot() { return runRoot; }
    boolean isFinished() { return finished; }

    private void recordScenarioOrRunEvent(String type, Map<String, ?> data) {
        if (current.get() == null) append(runEvents, event(type, data));
        else recordScenarioEvent(type, data);
    }

    private void recordScenarioEvent(String type, Map<String, ?> data) {
        ScenarioContext context = current.get();
        if (context == null) {
            append(runEvents, event(type, data));
            return;
        }
        long scenarioSeq = context.scenarioSeq.incrementAndGet();
        Map<String, Object> event = event(type, data);
        event.put("scenarioExecutionId", context.executionId);
        event.put("scenarioSeq", scenarioSeq);
        Deque<NestedContext> stack = nested.get();
        if (!stack.isEmpty()) event.put("nestedInvocationId", stack.peek().invocationId);
        if (isDeepTrace(event)) {
            long globalSeq = ((Number) event.get("eventSeq")).longValue();
            context.traceEventCount.incrementAndGet();
            if (context.traceEventSeqFirst == 0) context.traceEventSeqFirst = globalSeq;
            context.traceEventSeqLast = globalSeq;
            append(context.root.resolve("trace.jsonl"), event);
        } else {
            append(context.root.resolve("events.jsonl"), event);
        }
    }

    private static boolean isDeepTrace(Map<String, Object> event) {
        if (!"log".equals(String.valueOf(event.get("type")))) return false;
        String level = String.valueOf(event.get("level"));
        return "TRACE".equals(level) || "DEBUG".equals(level);
    }

    private Map<String, Object> event(String type, Map<String, ?> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", 1);
        event.put("runId", runId);
        event.put("eventSeq", eventSeq.incrementAndGet());
        event.put("timestamp", Instant.now().toString());
        event.put("monotonicOffsetNanos", Math.max(0, System.nanoTime() - monotonicOriginNanos));
        event.put("thread", Thread.currentThread().getName());
        event.put("type", type);
        if (data != null) data.forEach((key, value) -> event.put(key, sanitize(value, 0)));
        return event;
    }

    private void append(Path file, Map<String, ?> event) {
        Object lock = appendLocks.computeIfAbsent(file.toAbsolutePath().normalize(), ignored -> new Object());
        synchronized (lock) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(
                        file,
                        JSON.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(event) + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND
                );
            } catch (Throwable t) {
                failEvidence("append " + file.getFileName(), t);
            }
        }
    }

    private void writeConfiguration() throws IOException {
        Map<String, ConfigurationProvenance.Value> effective = ConfigurationProvenance.effective(effectiveConfig);
        configurationHash = sha256Hex(JSON.writeValueAsBytes(effective));
        String configuredRunProfile = find(effectiveConfig, PKB_props.PKB_RUN_PROFILE);
        runProfile = configuredRunProfile == null ? "" : configuredRunProfile;
        runProfileFingerprint = runProfileFingerprint(effectiveConfig);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("capturedAt", Instant.now().toString());
        body.put("configurationHash", configurationHash);
        if (!runProfile.isBlank()) body.put("runProfile", runProfile);
        body.put("runProfileFingerprint", runProfileFingerprint);
        body.put("directRunProfile", directRunProfile);
        body.put("effective", effective);
        writeJsonAtomic(runRoot.resolve("configuration.json"), body);
    }

    private void writeEnvironment() throws IOException {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("schemaVersion", 1);
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("javaVendor", System.getProperty("java.vendor"));
        environment.put("osName", System.getProperty("os.name"));
        environment.put("osVersion", System.getProperty("os.version"));
        environment.put("osArch", System.getProperty("os.arch"));
        environment.put("timezone", java.time.ZoneId.systemDefault().getId());
        environment.put("locale", Locale.getDefault().toLanguageTag());
        environment.put("processors", Runtime.getRuntime().availableProcessors());
        environment.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        environment.put("ci", System.getenv("CI") != null);
        environment.put("ciType", firstNonBlankEnv("GITHUB_ACTIONS", "GITLAB_CI", "JENKINS_URL", "TEAMCITY_VERSION", "TF_BUILD"));
        environment.put("containerHint", Files.exists(Path.of("/.dockerenv")));
        environmentHash = sha256Hex(JSON.writeValueAsBytes(environment));
        environment.put("environmentHash", environmentHash);
        environment.put("capturedAt", Instant.now().toString());
        writeJsonAtomic(runRoot.resolve("environment.json"), environment);
    }

    private void writeSourceProvenance() throws IOException {
        Map<String, Object> provenance = new LinkedHashMap<>(sourceProvenance.asMap());
        sourceProvenance.writeOptionalSnapshot(runRoot);
        if (!sourceProvenance.optionalSnapshotPath().isBlank()) {
            provenance.put("workingTreeSnapshot", Map.of(
                    "path", sourceProvenance.optionalSnapshotPath(),
                    "contentEncoding", "gzip",
                    "format", "git-diff-and-status"
            ));
        }
        sourceProvenanceHash = sha256Hex(JSON.writeValueAsBytes(provenance));
        provenance.put("sourceProvenanceHash", sourceProvenanceHash);
        writeJsonAtomic(runRoot.resolve("source-provenance.json"), provenance);
    }

    private void writeManifest(String outcome, String completion, Instant endedAt) throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("runId", runId);
        manifest.put("reportingMode", "DIAGNOSTIC");
        manifest.put("reportRetention", ReportRetentionPolicy.configuredValue());
        manifest.put("startedAt", startedAt.toString());
        manifest.put("endedAt", endedAt == null ? null : endedAt.toString());
        manifest.put("durationMillis", endedAt == null ? null : Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli()));
        manifest.put("monotonicOriginNanos", monotonicOriginNanos);
        manifest.put("processId", ProcessHandle.current().pid());
        manifest.put("timezone", java.time.ZoneId.systemDefault().getId());
        manifest.put("worker", Thread.currentThread().getName());
        manifest.put("outcome", outcome);
        manifest.put("completion", completion);
        manifest.put("shutdownReason", "COMPLETE".equals(completion) ? "NORMAL" : "IN_PROGRESS");
        manifest.put("evidenceIntegrity", partial ? "PARTIAL" : "COMPLETE");
        manifest.put("configurationHash", configurationHash);
        manifest.put("environmentHash", environmentHash);
        manifest.put("sourceProvenanceHash", sourceProvenanceHash);
        manifest.put("selectionFingerprint", selectionFingerprint());
        manifest.put("sourceFingerprint", sourceFingerprint());
        manifest.put("dependencyFingerprint", dependencyFingerprint());
        manifest.put("runProfileFingerprint", runProfileFingerprint);
        manifest.put("directRunProfile", directRunProfile);
        manifest.put("resourceStart", startResources);
        if (!endResources.isEmpty()) manifest.put("resourceEnd", endResources);
        if (!lineageMetadata().isEmpty()) manifest.put("lineage", lineageMetadata());
        manifest.put("sanitization", Map.of(
                "secretLikeConfiguration", "redacted",
                "secretLikeLogAssignments", "redacted",
                "largeValues", "bounded-and-marked-when-truncated",
                "platformSnapshot", "focused-subset-plus-configurable-caller-stamp",
                "capabilityFlags", "positive-native-observations-only"
        ));
        writeJsonAtomic(runRoot.resolve("manifest.json"), manifest);
    }

    private void writeRunIndex(String outcome, String completion) {
        try {
            List<Map<String, Object>> scenarioList = scenarios.values().stream()
                    .sorted(Comparator.comparing(summary -> summary.startedAt))
                    .map(ScenarioSummary::indexMap)
                    .toList();
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("schemaVersion", 1);
            index.put("runId", runId);
            index.put("outcome", outcome);
            index.put("completion", completion);
            index.put("startedAt", startedAt.toString());
            index.put("reportRetention", ReportRetentionPolicy.configuredValue());
            index.put("configurationHash", configurationHash);
            index.put("environmentHash", environmentHash);
            index.put("sourceProvenanceHash", sourceProvenanceHash);
            index.put("selectionFingerprint", selectionFingerprint());
            index.put("sourceFingerprint", sourceFingerprint());
            index.put("dependencyFingerprint", dependencyFingerprint());
            if (!runProfile.isBlank()) index.put("runProfile", runProfile);
            index.put("runProfileFingerprint", runProfileFingerprint);
            index.put("directRunProfile", directRunProfile);
            index.put("evidenceIntegrity", partial ? "PARTIAL" : "COMPLETE");
            if (!lineageMetadata().isEmpty()) index.put("lineage", lineageMetadata());
            index.put("comparisonMetadata", comparisonMetadata());
            index.put("counts", counts());
            index.put("steps", runStepCounts());
            index.put("nativeCapabilitiesObserved", runCapabilities.stream().sorted().toList());
            index.put("nativeCapabilityCounts", runCapabilityCounts());
            index.put("capabilitySemantics", "Presence means native Pickleball instrumentation observed the capability; absence does not prove the capability was unused by consumer-defined code.");
            index.put("scenarios", scenarioList);
            index.put("paths", Map.of(
                    "manifest", "manifest.json",
                    "configuration", "configuration.json",
                    "environment", "environment.json",
                    "sourceProvenance", "source-provenance.json",
                    "runEvents", "run-events.jsonl",
                    "clusters", "clusters.json",
                    "runCatalog", "../run-catalog.json"
            ));
            writeJsonAtomic(runRoot.resolve("run-index.json"), index);
        } catch (Throwable t) {
            failEvidence("write run index", t);
        }
    }


    private void writeRunCatalog() {
        try {
            List<Map<String, Object>> runs = new ArrayList<>();
            if (Files.isDirectory(runsRoot)) {
                try (var children = Files.list(runsRoot)) {
                    children.filter(Files::isDirectory).forEach(path -> {
                        Path indexPath = path.resolve("run-index.json");
                        if (!Files.isRegularFile(indexPath)) return;
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> index = JSON.readValue(indexPath.toFile(), LinkedHashMap.class);
                            Map<String, Object> summary = new LinkedHashMap<>();
                            for (String key : List.of(
                                    "runId", "outcome", "completion", "startedAt", "reportRetention",
                                    "configurationHash", "runProfile", "runProfileFingerprint",
                                    "comparisonMetadata", "counts", "lineage"
                            )) {
                                summary.put(key, index.get(key));
                            }
                            summary.put("runIndex", path.getFileName() + "/run-index.json");
                            runs.add(summary);
                        } catch (Throwable ignored) { }
                    });
                }
            }
            runs.sort((a, b) -> String.valueOf(b.get("startedAt")).compareTo(String.valueOf(a.get("startedAt"))));
            Map<String, Object> catalog = new LinkedHashMap<>();
            catalog.put("schemaVersion", 1);
            catalog.put("updatedAt", Instant.now().toString());
            catalog.put("runs", runs);
            writeJsonAtomic(runsRoot.resolve("run-catalog.json"), catalog);
        } catch (Throwable t) {
            failEvidence("write run catalog", t);
        }
    }

    private Map<String, Object> lineageMetadata() {
        Map<String, Object> lineage = new LinkedHashMap<>();
        copyIfPresent(lineage, "pkb_investigation_id", "investigationId");
        copyIfPresent(lineage, "pkb_run_purpose", "runPurpose");
        copyIfPresent(lineage, "pkb_parent_run_id", "parentRunId");
        copyIfPresent(lineage, "pkb_baseline_run_id", "baselineRunId");
        copyIfPresent(lineage, "pkb_changed_variables", "changedVariables");
        return lineage;
    }

    private void copyIfPresent(Map<String, Object> target, String key, String outputKey) {
        String value = find(effectiveConfig, key);
        if (value != null && !value.isBlank()) target.put(outputKey, sanitizeText(value));
    }

    private Map<String, Object> comparisonMetadata() {
        Map<String, Object> meta = new LinkedHashMap<>();
        copyConfig(meta, "pkb_browser", "browser");
        copyConfig(meta, "pkb_environment", "environment");
        copyConfig(meta, "pkb_tags", "tags");
        copyConfig(meta, "cucumber.filter.tags", "cucumberTags");
        copyConfig(meta, "pkb_features", "features");
        copyConfig(meta, "cucumber.features", "cucumberFeatures");
        copyConfig(meta, "pkb_name", "nameFilter");
        copyConfig(meta, "cucumber.filter.name", "cucumberNameFilter");
        copyConfig(meta, "pkb_parallel", "parallelism");
        copyConfig(meta, "pkb_options", "options");
        Package frameworkPackage = DiagnosticReporter.class.getPackage();
        String frameworkVersion = frameworkPackage == null ? null : frameworkPackage.getImplementationVersion();
        meta.put("pickleballVersion", frameworkVersion == null ? "unknown" : frameworkVersion);
        meta.put("configurationHash", configurationHash);
        meta.put("environmentHash", environmentHash);
        meta.put("selectionFingerprint", selectionFingerprint());
        meta.put("sourceFingerprint", sourceFingerprint());
        meta.put("dependencyFingerprint", dependencyFingerprint());
        meta.put("runProfileFingerprint", runProfileFingerprint);
        meta.put("directRunProfile", directRunProfile);
        meta.put("javaVersion", System.getProperty("java.version"));
        meta.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        meta.put("timezone", java.time.ZoneId.systemDefault().getId());
        String commit = firstNonBlankEnv("GITHUB_SHA", "CI_COMMIT_SHA", "BUILD_VCS_NUMBER", "GIT_COMMIT");
        if (!commit.isBlank()) meta.put("sourceRevision", commit);
        meta.putAll(sourceProvenance.comparisonMetadata());
        return meta;
    }

    private String selectionFingerprint() {
        Map<String, String> selection = new LinkedHashMap<>();
        for (String key : List.of(
                "pkb_features", "cucumber.features", "pkb_tags", "cucumber.filter.tags",
                "pkb_name", "cucumber.filter.name", "pkb_parallel", "pkb_options"
        )) {
            String value = find(effectiveConfig, key);
            if (value != null) selection.put(key, value);
        }
        try {
            return sha256Hex(JSON.writeValueAsBytes(selection));
        } catch (IOException e) {
            return "";
        }
    }

    private static String runProfileFingerprint(Map<String, String> values) {
        Map<String, String> runVars = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (PKB_props.isRunVariableKey(key) && value != null) {
                    runVars.put(key.toLowerCase(Locale.ROOT), value);
                }
            });
        }
        try {
            return sha256Hex(JSON.writeValueAsBytes(runVars));
        } catch (IOException e) {
            return "";
        }
    }

    private String dependencyFingerprint() {
        String classPath = System.getProperty("java.class.path", "");
        if (classPath.isBlank()) return ScenarioIdentity.shortHash("");
        List<String> entries = new ArrayList<>();
        for (String raw : classPath.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (raw == null || raw.isBlank()) continue;
            try {
                Path path = Path.of(raw);
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                long size = Files.isRegularFile(path) ? Files.size(path) : -1L;
                entries.add(name + "|" + size);
            } catch (Throwable ignored) {
                entries.add(Path.of(raw).getFileName().toString());
            }
        }
        entries.sort(String::compareTo);
        return ScenarioIdentity.shortHash(String.join("\n", entries));
    }

    private String sourceFingerprint() {
        String source = scenarios.values().stream()
                .map(summary -> summary.identity.semanticKey() + "|" + summary.identity.exactSourceKey())
                .sorted()
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return ScenarioIdentity.shortHash(source);
    }


    private static Map<String, Object> resourceSnapshot() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> resources = new LinkedHashMap<>();
        resources.put("heapUsedBytes", Math.max(0, runtime.totalMemory() - runtime.freeMemory()));
        resources.put("heapCommittedBytes", runtime.totalMemory());
        resources.put("heapMaxBytes", runtime.maxMemory());
        resources.put("processors", runtime.availableProcessors());
        ProcessHandle.current().info().totalCpuDuration()
                .ifPresent(duration -> resources.put("processCpuMillis", duration.toMillis()));
        return resources;
    }

    private static String firstNonBlankEnv(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return name + "=" + value;
        }
        return "";
    }

    private void copyConfig(Map<String, Object> target, String wantedKey, String outputKey) {
        effectiveConfig.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(wantedKey))
                .findFirst()
                .ifPresent(e -> target.put(outputKey,
                        ConfigurationProvenance.sensitive(e.getKey()) ? "<redacted>" : e.getValue()));
    }

    private Map<String, Integer> counts() {
        int pass = 0, fail = 0, unknown = 0;
        for (ScenarioSummary summary : scenarios.values()) {
            if ("PASSED".equals(summary.outcome)) pass++;
            else if ("FAILED".equals(summary.outcome)) fail++;
            else unknown++;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("total", scenarios.size());
        counts.put("passed", pass);
        counts.put("failed", fail);
        counts.put("unknown", unknown);
        return counts;
    }

    private Map<String, Object> runStepCounts() {
        long executed = 0, passed = 0, failed = 0, skipped = 0, other = 0, pickleball = 0, nonPickleball = 0;
        for (ScenarioSummary summary : scenarios.values()) {
            executed += summary.stepExecuted.get();
            passed += summary.stepPassed.get();
            failed += summary.stepFailed.get();
            skipped += summary.stepSkipped.get();
            other += summary.stepOther.get();
            pickleball += summary.stepPickleball.get();
            nonPickleball += summary.stepNonPickleball.get();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executed", executed);
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("skipped", skipped);
        result.put("other", other);
        result.put("pickleball", pickleball);
        result.put("nonPickleball", nonPickleball);
        return result;
    }

    private Map<String, Object> runCapabilityCounts() {
        Map<String, Long> scenarioCounts = new TreeMap<>();
        Map<String, Long> stepCounts = new TreeMap<>();
        for (ScenarioSummary summary : scenarios.values()) {
            for (String capability : summary.capabilities) {
                scenarioCounts.merge(capability, 1L, Long::sum);
            }
            summary.capabilityStepCounts.forEach((capability, count) -> stepCounts.merge(capability, count, Long::sum));
        }
        Map<String, Object> result = new TreeMap<>();
        for (String capability : runCapabilities.stream().sorted().toList()) {
            result.put(capability, Map.of(
                    "scenarioCount", scenarioCounts.getOrDefault(capability, 0L),
                    "stepCount", stepCounts.getOrDefault(capability, 0L)
            ));
        }
        return result;
    }

    private String runOutcome() {
        Map<String, Integer> counts = counts();
        if (counts.get("total") == 0) return "NO_TESTS";
        if (counts.get("failed") > 0) return "FAILED";
        if (counts.get("unknown") > 0) return "UNKNOWN";
        return "PASSED";
    }

    private void writeClusters() throws IOException {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        scenarios.values().stream()
                .filter(s -> s.failureSignature != null && !s.failureSignature.isBlank())
                .forEach(s -> grouped.computeIfAbsent(s.failureSignature, ignored -> new ArrayList<>())
                        .add(s.executionId));
        List<Map<String, Object>> clusters = new ArrayList<>();
        grouped.forEach((signature, ids) -> clusters.add(Map.of(
                "failureSignature", signature,
                "scenarioCount", ids.size(),
                "scenarioExecutionIds", ids
        )));
        writeJsonAtomic(runRoot.resolve("clusters.json"), Map.of(
                "schemaVersion", 1,
                "clusters", clusters
        ));
    }

    private void writeScenarioSummary(ScenarioSummary summary) {
        try {
            Map<String, Object> body = summary.fullMap();
            if (!runProfile.isBlank()) {
                body.put("runProfile", runProfile);
            }
            writeJsonAtomic(summaryPath(summary.executionId), body);
        } catch (Throwable t) {
            failEvidence("write scenario summary", t);
        }
    }

    private Path summaryPath(String executionId) {
        return runRoot.resolve("scenarios").resolve(executionId).resolve("summary.json");
    }

    private void compressTrace(ScenarioContext context, ScenarioSummary summary) {
        if (context.traceEventCount.get() == 0) return;
        Path raw = context.root.resolve("trace.jsonl");
        if (!Files.isRegularFile(raw)) return;
        Path compressed = context.root.resolve("trace.jsonl.gz");
        Path temp = context.root.resolve("trace.jsonl.gz.tmp");
        try (InputStream in = Files.newInputStream(raw);
             OutputStream fileOut = Files.newOutputStream(temp);
             GZIPOutputStream gzip = new GZIPOutputStream(fileOut)) {
            in.transferTo(gzip);
        } catch (Throwable error) {
            deleteIfExists(temp);
            summary.tracePath = "scenarios/" + context.executionId + "/trace.jsonl";
            summary.traceEncoding = "identity";
            failEvidence("compress scenario trace", error);
            return;
        }
        try {
            Files.move(temp, compressed, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(raw);
            summary.tracePath = "scenarios/" + context.executionId + "/trace.jsonl.gz";
            summary.traceEncoding = "gzip";
        } catch (Throwable error) {
            deleteIfExists(temp);
            summary.tracePath = "scenarios/" + context.executionId + "/trace.jsonl";
            summary.traceEncoding = "identity";
            failEvidence("finalize compressed scenario trace", error);
        }
    }

    private void pruneDenseEvidence(Path scenarioRoot) {
        deleteIfExists(scenarioRoot.resolve("events.jsonl"));
        deleteIfExists(scenarioRoot.resolve("trace.jsonl"));
        deleteIfExists(scenarioRoot.resolve("trace.jsonl.gz"));
        deleteTree(scenarioRoot.resolve("screenshots"));
        deleteTree(scenarioRoot.resolve("fingerprints"));
    }

    private static void deleteIfExists(Path path) {
        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(DiagnosticReporter::deleteIfExists);
        } catch (IOException ignored) { }
    }

    private synchronized void writeJsonAtomic(Path target, Object value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        JSON.writeValue(temp.toFile(), value);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void failEvidence(String action, Throwable error) {
        partial = true;
        System.err.println("[Pickleball diagnostic] Could not " + action + ": " + error.getMessage());
    }

    private static Path resolveRunsRoot(Map<String, String> values) {
        String configured = find(values, PKB_DIAGNOSTIC_OUTPUT);
        return configured == null || configured.isBlank()
                ? Path.of("reports", "diagnostic-runs")
                : Path.of(configured.trim());
    }

    private static String find(Map<String, String> values, String key) {
        if (values == null) return null;
        return values.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private static void recoverIncompleteRuns(Path runsRoot, Path currentRun) {
        if (!Files.isDirectory(runsRoot)) return;
        try (var children = Files.list(runsRoot)) {
            children.filter(Files::isDirectory)
                    .filter(path -> !path.equals(currentRun))
                    .forEach(path -> {
                        Path manifest = path.resolve("manifest.json");
                        if (!Files.isRegularFile(manifest)) return;
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = JSON.readValue(manifest.toFile(), LinkedHashMap.class);
                            if (!"RUNNING".equals(String.valueOf(data.get("outcome")))) return;
                            String recoveredAt = Instant.now().toString();
                            data.put("outcome", "UNKNOWN");
                            data.put("completion", "INTERRUPTED");
                            data.put("shutdownReason", "ABRUPT_OR_TERMINATED");
                            data.put("recoveredAt", recoveredAt);
                            JSON.writeValue(manifest.toFile(), data);

                            Path runIndex = path.resolve("run-index.json");
                            if (Files.isRegularFile(runIndex)) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> index = JSON.readValue(runIndex.toFile(), LinkedHashMap.class);
                                index.put("outcome", "UNKNOWN");
                                index.put("completion", "INTERRUPTED");
                                index.put("recoveredAt", recoveredAt);
                                JSON.writeValue(runIndex.toFile(), index);
                            }
                            DiagnosticIndexRebuilder.rebuildRunIndex(path);
                        } catch (Throwable ignored) { }
                    });
        } catch (IOException ignored) { }
    }

    private static Object sanitize(Object value, int depth) {
        if (value == null) return null;
        if (depth > 3) return sanitizeText(String.valueOf(value));
        if (value instanceof String text) return sanitizeText(text);
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return value;
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    copy.put("_diagnosticTruncatedEntries", Math.max(0, map.size() - MAX_COLLECTION_ITEMS));
                    break;
                }
                String name = String.valueOf(entry.getKey());
                copy.put(name, ConfigurationProvenance.sensitive(name)
                        ? "<redacted>"
                        : sanitize(entry.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            int count = 0;
            for (Object nested : iterable) {
                if (count++ >= MAX_COLLECTION_ITEMS) {
                    copy.add("<diagnostic-truncated-after-" + MAX_COLLECTION_ITEMS + "-items>");
                    break;
                }
                copy.add(sanitize(nested, depth + 1));
            }
            return copy;
        }
        return sanitizeText(String.valueOf(value));
    }

    private static String sanitizeText(String value) {
        if (value == null) return "";
        String sanitized = SECRET.matcher(value).replaceAll("$1$2<redacted>");
        if (sanitized.length() <= MAX_TEXT_CHARS) return sanitized;
        return sanitized.substring(0, MAX_TEXT_CHARS)
                + "<diagnostic-truncated originalChars=" + sanitized.length() + ">";
    }

    private static String safeDriverValue(WebDriver driver, boolean url) {
        try {
            String value = url ? driver.getCurrentUrl() : driver.getTitle();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safeFileName(String value) {
        String safe = value == null ? "screenshot" : value.trim().replaceAll("[^A-Za-z0-9._-]+", "-");
        safe = safe.replaceAll("-+", "-").replaceAll("^-|-$", "");
        return safe.isBlank() ? "screenshot" : safe.substring(0, Math.min(80, safe.length()));
    }

    private String relative(Path path) {
        return runRoot.relativize(path).toString().replace('\\', '/');
    }

    private static List<Map<String, Object>> failureDetails(List<Throwable> failures) {
        if (failures == null || failures.isEmpty()) return List.of();
        List<Map<String, Object>> details = new ArrayList<>();
        for (Throwable failure : failures.stream().limit(8).toList()) {
            details.add(failureDetails(failure));
        }
        return details;
    }

    private static Map<String, Object> failureDetails(Throwable failure) {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (failure == null) return detail;
        detail.put("class", failure.getClass().getName());
        detail.put("message", sanitizeText(failure.getMessage()));
        List<String> stack = new ArrayList<>();
        for (StackTraceElement frame : failure.getStackTrace()) {
            if (stack.size() >= 80) break;
            stack.add(sanitizeText(frame.toString()));
        }
        detail.put("stackTrace", stack);
        List<Map<String, Object>> causes = new ArrayList<>();
        Throwable cause = failure.getCause();
        int depth = 0;
        while (cause != null && cause != failure && depth++ < 8) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("class", cause.getClass().getName());
            item.put("message", sanitizeText(cause.getMessage()));
            causes.add(item);
            cause = cause.getCause();
        }
        detail.put("causes", causes);
        return detail;
    }

    private static String failureSignature(Throwable failure) {
        String message = failure == null ? "" : sanitizeText(failure.getMessage())
                .replaceAll("\\b\\d+\\b", "#")
                .replaceAll("0x[0-9a-fA-F]+", "0x#")
                .replaceAll("\\s+", " ")
                .trim();
        return ScenarioIdentity.shortHash((failure == null ? "" : failure.getClass().getName()) + "|" + message);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(64);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class ScenarioContext {
        final String executionId;
        final ScenarioIdentity identity;
        final Path root;
        final Instant startedAt;
        final AtomicLong scenarioSeq = new AtomicLong();
        final AtomicLong screenshotSeq = new AtomicLong();
        final AtomicLong traceEventCount = new AtomicLong();
        volatile long traceEventSeqFirst;
        volatile long traceEventSeqLast;
        VisualFingerprint previousFingerprint;
        String previousScreenshotId;
        Path previousImagePath;
        Path previousFingerprintPath;

        ScenarioContext(String executionId, ScenarioIdentity identity, Path root, Instant startedAt) {
            this.executionId = executionId;
            this.identity = identity;
            this.root = root;
            this.startedAt = startedAt;
        }
    }

    private record NestedContext(String invocationId, ScenarioIdentity identity) {}

    private static final class StepContext {
        final StepExtension step;
        final long stepNumber;
        final Set<String> capabilities = ConcurrentHashMap.newKeySet();
        volatile int stepLine;
        volatile String keyword = "";
        volatile String text = "";
        volatile Map<String, Object> source = Map.of();
        volatile Map<String, Object> definition = Map.of();

        StepContext(StepExtension step, long stepNumber) {
            this.step = step;
            this.stepNumber = stepNumber;
        }
    }

    private static final class ScenarioSummary {
        final String executionId;
        final ScenarioIdentity identity;
        final Instant startedAt;
        final String configurationHash;
        final Map<String, Object> source;
        volatile Instant endedAt;
        volatile long durationMillis;
        volatile String outcome = "RUNNING";
        volatile String completion = "IN_PROGRESS";
        volatile long lastEventSeq;
        volatile long eventCount;
        volatile long traceEventCount;
        volatile long traceEventSeqFirst;
        volatile long traceEventSeqLast;
        volatile String tracePath;
        volatile String traceEncoding;
        volatile boolean detailedEvidenceRetained = true;
        volatile long screenshotCount;
        final List<Map<String, Object>> representativeScreenshots = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile String failureClass;
        volatile String failureMessage;
        volatile String failureSignature;
        final Set<String> capabilities = ConcurrentHashMap.newKeySet();
        final Map<String, Long> capabilityStepCounts = new ConcurrentHashMap<>();
        final AtomicLong stepExecuted = new AtomicLong();
        final AtomicLong stepPassed = new AtomicLong();
        final AtomicLong stepFailed = new AtomicLong();
        final AtomicLong stepSkipped = new AtomicLong();
        final AtomicLong stepOther = new AtomicLong();
        final AtomicLong stepPickleball = new AtomicLong();
        final AtomicLong stepNonPickleball = new AtomicLong();

        ScenarioSummary(
                String executionId,
                ScenarioIdentity identity,
                Instant startedAt,
                String configurationHash,
                Map<String, Object> source
        ) {
            this.executionId = executionId;
            this.identity = identity;
            this.startedAt = startedAt;
            this.configurationHash = configurationHash;
            this.source = source == null ? Map.of() : new LinkedHashMap<>(source);
        }

        Map<String, Object> indexMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("schemaVersion", 1);
            map.put("scenarioExecutionId", executionId);
            map.put("identity", identity.asMap());
            map.put("source", source);
            map.put("outcome", outcome);
            map.put("completion", completion);
            map.put("startedAt", startedAt.toString());
            map.put("endedAt", endedAt == null ? null : endedAt.toString());
            map.put("durationMillis", durationMillis);
            map.put("eventCount", eventCount);
            map.put("eventRange", Map.of(
                    "scenarioExecutionId", executionId,
                    "scenarioSeqStart", eventCount == 0 ? 0 : 1,
                    "scenarioSeqEnd", eventCount
            ));
            map.put("detailedEvidenceRetained", detailedEvidenceRetained);
            map.put("steps", stepCountsMap());
            map.put("nativeCapabilitiesObserved", capabilities.stream().sorted().toList());
            map.put("nativeCapabilityCounts", new TreeMap<>(capabilityStepCounts));
            map.put("screenshotCount", screenshotCount);
            synchronized (representativeScreenshots) {
                map.put("representativeScreenshots", List.copyOf(representativeScreenshots));
            }
            map.put("failureSignature", failureSignature);
            map.put("summary", "scenarios/" + executionId + "/summary.json");
            map.put("events", detailedEvidenceRetained ? "scenarios/" + executionId + "/events.jsonl" : null);
            if (detailedEvidenceRetained && traceEventCount > 0) {
                Map<String, Object> trace = new LinkedHashMap<>();
                trace.put("path", tracePath);
                trace.put("contentType", "application/x-ndjson");
                trace.put("contentEncoding", traceEncoding);
                trace.put("eventCount", traceEventCount);
                trace.put("eventSeqFirst", traceEventSeqFirst);
                trace.put("eventSeqLast", traceEventSeqLast);
                map.put("traceEvidence", trace);
            }
            return map;
        }

        Map<String, Object> stepCountsMap() {
            Map<String, Object> steps = new LinkedHashMap<>();
            steps.put("executed", stepExecuted.get());
            steps.put("passed", stepPassed.get());
            steps.put("failed", stepFailed.get());
            steps.put("skipped", stepSkipped.get());
            steps.put("other", stepOther.get());
            steps.put("pickleball", stepPickleball.get());
            steps.put("nonPickleball", stepNonPickleball.get());
            return steps;
        }

        Map<String, Object> fullMap() {
            Map<String, Object> map = new LinkedHashMap<>(indexMap());
            map.put("configurationHash", configurationHash);
            map.put("failureClass", failureClass);
            map.put("failureMessage", failureMessage);
            return map;
        }
    }
}
