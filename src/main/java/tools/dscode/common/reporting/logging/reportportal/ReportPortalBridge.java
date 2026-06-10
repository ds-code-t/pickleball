package tools.dscode.common.reporting.logging.reportportal;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.message.ReportPortalMessage;
import com.epam.reportportal.message.TypeAwareByteSource;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.utils.files.ByteSource;
import com.epam.reportportal.utils.properties.PropertiesLoader;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import io.reactivex.Maybe;
import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

import static tools.dscode.common.reporting.logging.LogForwarder.logDebug;

public final class ReportPortalBridge {

    private static final Object INIT_LOCK = new Object();

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static volatile boolean launchStarted;

    private static volatile Maybe<String> launchUuid = Maybe.empty();

    private static final ThreadLocal<Maybe<String>> CURRENT_TEST = new ThreadLocal<>();
    private static final Set<String> KNOWN_SUITES = ConcurrentHashMap.newKeySet();

    private static final AtomicReference<Throwable> ASYNC_FAILURE = new AtomicReference<>();
    private static volatile boolean rxErrorHandlerInstalled;

    /**
     * ReportPortal client-java already supports parallel logging.  This bridge keeps heavy
     * attachment materialization off scenario threads by default, but no longer serializes
     * all ReportPortal sends through one worker or a global send lock.
     *
     * Work is ordered per ReportPortal item/test so a test's finish request cannot overtake
     * that same test's earlier logs or screenshots.  Different tests can still hand work to
     * ReportPortal in parallel.
     */
    private static final boolean ASYNC_LOGGING = boolSetting("dscode.reportportal.asyncLogging", true);
    private static final int WORKER_THREADS = intSetting(
            "dscode.reportportal.workerThreads",
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );
    private static final int MAX_PENDING_LOGS = intSetting("dscode.reportportal.maxPendingLogs", 512);
    private static final int SHUTDOWN_TIMEOUT_SECONDS = intSetting("dscode.reportportal.shutdownTimeoutSeconds", 30);
    private static final Semaphore PENDING_LOG_SLOTS = new Semaphore(Math.max(1, MAX_PENDING_LOGS));
    private static final Object LAUNCH_WORK_KEY = new Object();
    private static final AtomicLong WORKER_ID = new AtomicLong();
    private static final ConcurrentHashMap<Object, CompletableFuture<Void>> WORK_CHAINS = new ConcurrentHashMap<>();
    private static final ExecutorService RP_EXECUTOR = Executors.newFixedThreadPool(WORKER_THREADS, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dscode-reportportal-log-worker-" + WORKER_ID.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private ReportPortalBridge() { }

    private static String bridgeState() {
        return "enabled=" + enabled
                + ", initialized=" + initialized
                + ", launchStarted=" + launchStarted
                + ", asyncLogging=" + ASYNC_LOGGING
                + ", workerThreads=" + WORKER_THREADS
                + ", pendingWorkChains=" + WORK_CHAINS.size()
                + ", availableQueueSlots=" + PENDING_LOG_SLOTS.availablePermits();
    }

    private static String throwableSummary(Throwable t) {
        if (t == null) return "null";
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void installRxErrorHandlerIfNeeded() {
        if (rxErrorHandlerInstalled) {
            logDebug("ReportPortalBridge.installRxErrorHandlerIfNeeded: already-installed");
            return;
        }

        logDebug("ReportPortalBridge.installRxErrorHandlerIfNeeded: waiting-for-init-lock");
        synchronized (INIT_LOCK) {
            logDebug("ReportPortalBridge.installRxErrorHandlerIfNeeded: acquired-init-lock");
            if (rxErrorHandlerInstalled) {
                logDebug("ReportPortalBridge.installRxErrorHandlerIfNeeded: already-installed-after-lock");
                return;
            }

            RxJavaPlugins.setErrorHandler(e -> {
                Throwable t = (e instanceof UndeliverableException ude && ude.getCause() != null)
                        ? ude.getCause()
                        : e;
                ASYNC_FAILURE.compareAndSet(null, t);
                logDebug("ReportPortalBridge.rxErrorHandler: captured " + throwableSummary(t));
            });

            rxErrorHandlerInstalled = true;
            logDebug("ReportPortalBridge.installRxErrorHandlerIfNeeded: installed");
        }
    }

    public static void throwIfAsyncFailure() {
        logDebug("ReportPortalBridge.throwIfAsyncFailure: checking");
        Throwable t = ASYNC_FAILURE.getAndSet(null);
        if (t == null) {
            logDebug("ReportPortalBridge.throwIfAsyncFailure: none");
            return;
        }
        logDebug("ReportPortalBridge.throwIfAsyncFailure: throwing " + throwableSummary(t));
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        throw new RuntimeException("ReportPortal async failure", t);
    }

    public static void initIfNeeded() {
        logDebug("ReportPortalBridge.initIfNeeded: starting " + bridgeState());
        installRxErrorHandlerIfNeeded();
        if (initialized) {
            logDebug("ReportPortalBridge.initIfNeeded: already-initialized " + bridgeState());
            return;
        }

        logDebug("ReportPortalBridge.initIfNeeded: waiting-for-init-lock");
        synchronized (INIT_LOCK) {
            logDebug("ReportPortalBridge.initIfNeeded: acquired-init-lock");
            if (initialized) {
                logDebug("ReportPortalBridge.initIfNeeded: already-initialized-after-lock " + bridgeState());
                return;
            }

            logDebug("ReportPortalBridge.initIfNeeded: loading-properties");
            ListenerParameters params = new ListenerParameters(PropertiesLoader.load());
            enabled = Optional.ofNullable(params.getEnable()).orElse(false);
            logDebug("ReportPortalBridge.initIfNeeded: loaded-properties enabled=" + enabled);

            if (enabled) {
                logDebug("ReportPortalBridge.initIfNeeded: setting-hierarchy-parameters launchName=" + fallback(params.getLaunchName(), "Launch"));
                ReportPortalHierarchy.setParameters(params);
                ReportPortalHierarchy.setLaunchName(fallback(params.getLaunchName(), "Launch"));
                logDebug("ReportPortalBridge.initIfNeeded: set-hierarchy-parameters-complete");
            }

            initialized = true;
            logDebug("ReportPortalBridge.initIfNeeded: finished " + bridgeState());
        }
    }

    public static boolean isEnabled() {
        initIfNeeded();
        return enabled;
    }

    public static Maybe<String> startLaunchIfNeeded() {
        logDebug("ReportPortalBridge.startLaunchIfNeeded: starting " + bridgeState());
        initIfNeeded();
        if (!enabled) {
            logDebug("ReportPortalBridge.startLaunchIfNeeded: disabled-return-empty");
            return Maybe.empty();
        }
        if (launchStarted) {
            logDebug("ReportPortalBridge.startLaunchIfNeeded: already-started");
            return launchUuid;
        }

        logDebug("ReportPortalBridge.startLaunchIfNeeded: waiting-for-init-lock");
        synchronized (INIT_LOCK) {
            logDebug("ReportPortalBridge.startLaunchIfNeeded: acquired-init-lock");
            if (!launchStarted) {
                logDebug("ReportPortalBridge.startLaunchIfNeeded: getting-launch");
                Launch launch = ReportPortalHierarchy.getLaunch();
                logDebug("ReportPortalBridge.startLaunchIfNeeded: starting-reportportal-launch");
                launchUuid = launch.start();
                launchStarted = true;
                logDebug("ReportPortalBridge.startLaunchIfNeeded: reportportal-launch-started");
            }
            logDebug("ReportPortalBridge.startLaunchIfNeeded: finished " + bridgeState());
            return launchUuid;
        }
    }

    public static Maybe<String> getOrCreateSuite(String suiteName) {
        initIfNeeded();
        if (!enabled) return Maybe.empty();

        startLaunchIfNeeded();

        String name = blankToNull(suiteName);
        if (name == null) {
            return ReportPortalHierarchy.getDefaultOrLastSuite();
        }

        KNOWN_SUITES.add(name);
        return ReportPortalHierarchy.getOrCreateSuite(name);
    }

    public static Maybe<String> getOrCreateSuitePath(List<String> suitePath) {
        initIfNeeded();
        if (!enabled) return Maybe.empty();

        startLaunchIfNeeded();

        if (suitePath == null || suitePath.isEmpty()) {
            return ReportPortalHierarchy.getDefaultOrLastSuite();
        }

        suitePath.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .forEach(KNOWN_SUITES::add);

        return ReportPortalHierarchy.getOrCreateSuitePath(suitePath);
    }

    public static Maybe<String> startTest(String testName, String suiteName) {
        initIfNeeded();
        if (!enabled) return Maybe.empty();

        startLaunchIfNeeded();

        Maybe<String> suite = getOrCreateSuite(suiteName);
        Maybe<String> test = (suite == null)
                ? ReportPortalHierarchy.startTest(fallback(testName, "Unnamed"))
                : ReportPortalHierarchy.startTest(fallback(testName, "Unnamed"), suite);

        CURRENT_TEST.set(test);
        return test;
    }

    public static Maybe<String> startTest(String testName, List<String> suitePath) {
        initIfNeeded();
        if (!enabled) return Maybe.empty();

        startLaunchIfNeeded();

        Maybe<String> suite = getOrCreateSuitePath(suitePath);
        Maybe<String> test = (suite == null)
                ? ReportPortalHierarchy.startTest(fallback(testName, "Unnamed"))
                : ReportPortalHierarchy.startTest(fallback(testName, "Unnamed"), suite);

        CURRENT_TEST.set(test);
        return test;
    }

    public static void finishCurrentTest(String status) {
        initIfNeeded();
        if (!enabled) return;

        Maybe<String> test = CURRENT_TEST.get();
        CURRENT_TEST.remove();
        String finalStatus = fallback(status, "PASSED");

        submitReportPortalWork(test, () -> {
            if (test != null) {
                ReportPortalHierarchy.finishTest(test, finalStatus);
            } else {
                ReportPortalHierarchy.finishCurrentTest(finalStatus);
            }
        });
    }

    public static void log(String level, String message) {
        log(level, message, Instant.now());
    }

    public static void log(String level, String message, Instant logTime) {
        initIfNeeded();
        if (!enabled) return;

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = safe(message);
        Date when = Date.from(logTime != null ? logTime : Instant.now());

        submitReportPortalWork(test, () -> logNow(test, lvl, msg, when));
    }

    private static void logNow(Maybe<String> test, String lvl, String msg, Date when) {
        if (test != null) {
            ReportPortalHierarchy.getLaunch().log(test, itemUuid -> {
                SaveLogRQ rq = new SaveLogRQ();
                rq.setItemUuid(itemUuid);
                rq.setLevel(lvl);
                rq.setLogTime(when);
                rq.setMessage(msg);
                return rq;
            });
            return;
        }

        ReportPortalHierarchy.getLaunch().log(launchId -> {
            SaveLogRQ rq = new SaveLogRQ();
            rq.setLaunchUuid(launchId);
            rq.setLevel(lvl);
            rq.setLogTime(when);
            rq.setMessage(msg);
            return rq;
        });
    }

    public static void logAttachment(String level, String message, byte[] bytes, String filenameHint) {
        logAttachment(level, message, bytes, filenameHint, Instant.now());
    }

    public static void logAttachment(String level,
                                     String message,
                                     byte[] bytes,
                                     String filenameHint,
                                     Instant logTime) {
        initIfNeeded();
        if (!enabled) return;

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = fallback(message, "attachment");
        byte[] data = Objects.requireNonNullElseGet(bytes, () -> new byte[0]);
        String safeName = fallback(filenameHint, "attachment.bin");
        Date when = Date.from(logTime != null ? logTime : Instant.now());

        submitReportPortalWork(test, () -> logAttachmentNow(test, lvl, msg, data, null, safeName, when));
    }

    /**
     * Preferred attachment path.  The screenshot file is materialized by a ReportPortal
     * bridge worker thread so scenario threads do not have to read or clone large files.
     */
    public static void logAttachmentFile(String level,
                                         String message,
                                         Path file,
                                         String filenameHint,
                                         Instant logTime) {
        initIfNeeded();
        if (!enabled) return;

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = fallback(message, "attachment");
        String safeName = fallback(filenameHint, file == null || file.getFileName() == null ? "attachment.bin" : file.getFileName().toString());
        Date when = Date.from(logTime != null ? logTime : Instant.now());
        Path safeFile = file == null ? null : file.toAbsolutePath().normalize();

        submitReportPortalWork(test, () -> logAttachmentNow(test, lvl, msg, null, safeFile, safeName, when));
    }

    private static void logAttachmentNow(Maybe<String> test,
                                         String lvl,
                                         String msg,
                                         byte[] data,
                                         Path file,
                                         String safeName,
                                         Date when) {
        try {
            String mimeType = URLConnection.guessContentTypeFromName(safeName);
            if (mimeType == null) mimeType = "application/octet-stream";

            ByteSource byteSource = file != null
                    ? byteSourceFromFileOrFallback(file)
                    : ByteSource.wrap(Objects.requireNonNullElseGet(data, () -> new byte[0]));

            TypeAwareByteSource source = new TypeAwareByteSource(byteSource, mimeType);
            ReportPortalMessage rpMessage = new ReportPortalMessage(source, msg);

            if (test != null) {
                ReportPortalHierarchy.getLaunch().log(test, itemUuid ->
                        ReportPortal.toSaveLogRQ(null, itemUuid, lvl, when, rpMessage));
            } else {
                ReportPortalHierarchy.getLaunch().log(launchId ->
                        ReportPortal.toSaveLogRQ(launchId, null, lvl, when, rpMessage));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to log ReportPortal attachment", e);
        }
    }

    /**
     * Prefer a file/path-backed ByteSource if the installed client exposes one.  client-java
     * versions differ, so reflection keeps this drop-in compatible with 5.4.x.  If unavailable,
     * fall back to byte[] materialization on the ReportPortal worker thread, not the scenario thread.
     */
    private static ByteSource byteSourceFromFileOrFallback(Path file) throws Exception {
        if (file == null) return ByteSource.wrap(new byte[0]);

        for (Method m : ByteSource.class.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!ByteSource.class.isAssignableFrom(m.getReturnType())) continue;
            if (m.getParameterCount() != 1) continue;

            Class<?> p = m.getParameterTypes()[0];
            try {
                if (Path.class.isAssignableFrom(p)) {
                    return (ByteSource) m.invoke(null, file);
                }
                if (File.class.isAssignableFrom(p)) {
                    return (ByteSource) m.invoke(null, file.toFile());
                }
            } catch (ReflectiveOperationException ignored) {
                // Try the next compatible factory method.
            }
        }

        return ByteSource.wrap(Files.readAllBytes(file));
    }

    public static void finishLaunch(String status) {
        logDebug("starting-finishLaunch");
        logDebug("ReportPortalBridge.finishLaunch: starting status=" + fallback(status, "PASSED") + " " + bridgeState());
        initIfNeeded();
        logDebug("ReportPortalBridge.finishLaunch: init-complete " + bridgeState());

        try {
            if (!enabled || !launchStarted) {
                logDebug("ReportPortalBridge.finishLaunch: skipped enabled=" + enabled + ", launchStarted=" + launchStarted);
                return;
            }

            logDebug("ReportPortalBridge.finishLaunch: before-drainReportPortalWork " + bridgeState());
            drainReportPortalWork();
            logDebug("ReportPortalBridge.finishLaunch: after-drainReportPortalWork " + bridgeState());

            String finalStatus = fallback(status, "PASSED");
            logDebug("ReportPortalBridge.finishLaunch: finalStatus=" + finalStatus);

            logDebug("ReportPortalBridge.finishLaunch: before-finishAllSuites knownSuites=" + KNOWN_SUITES.size());
            ReportPortalHierarchy.finishAllSuites(finalStatus);
            logDebug("ReportPortalBridge.finishLaunch: after-finishAllSuites");

            FinishExecutionRQ rq = new FinishExecutionRQ();
            rq.setStatus(finalStatus);
            rq.setEndTime(new Date());
            logDebug("ReportPortalBridge.finishLaunch: before-getLaunch-finish");
            ReportPortalHierarchy.getLaunch().finish(rq);
            logDebug("ReportPortalBridge.finishLaunch: after-getLaunch-finish");

            logDebug("ReportPortalBridge.finishLaunch: before-throwIfAsyncFailure");
            throwIfAsyncFailure();
            logDebug("ReportPortalBridge.finishLaunch: after-throwIfAsyncFailure");
        } finally {
            logDebug("ReportPortalBridge.finishLaunch: finally-start " + bridgeState());
            CURRENT_TEST.remove();
            launchUuid = Maybe.empty();
            launchStarted = false;
            logDebug("ReportPortalBridge.finishLaunch: before-shutdownReportPortalExecutor " + bridgeState());
            shutdownReportPortalExecutor();
            logDebug("ReportPortalBridge.finishLaunch: finally-finished " + bridgeState());
        }
    }

    /**
     * Final JVM cleanup for the bridge worker pool. Call this from the outer
     * @AfterAll path even when ReportPortal was disabled or no launch was started.
     */
    public static void shutdown() {
        logDebug("ReportPortalBridge.shutdown: starting " + bridgeState());
        shutdownReportPortalExecutor();
        logDebug("ReportPortalBridge.shutdown: finished " + bridgeState());
    }


    /**
     * Returns a non-blocking marker future that completes after ReportPortal work
     * submitted before this call has drained.
     *
     * This is useful for scenario-level cleanup orchestration: scenario close can
     * continue, while the external lifecycle can wait for this future before deleting
     * scenario/run attachment files.
     */
    public static CompletableFuture<Void> drainSubmittedWorkAsync() {
        logDebug("ReportPortalBridge.drainSubmittedWorkAsync: starting " + bridgeState());
        initIfNeeded();

        if (!enabled || !ASYNC_LOGGING) {
            logDebug("ReportPortalBridge.drainSubmittedWorkAsync: immediate-path enabled=" + enabled + ", asyncLogging=" + ASYNC_LOGGING);
            try {
                throwIfAsyncFailure();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable t) {
                logDebug("ReportPortalBridge.drainSubmittedWorkAsync: failed-immediate-path " + throwableSummary(t));
                return failedFuture(t);
            }
        }

        List<CompletableFuture<Void>> snapshot = new ArrayList<>(WORK_CHAINS.values());
        logDebug("ReportPortalBridge.drainSubmittedWorkAsync: snapshot-size=" + snapshot.size());
        CompletableFuture<Void> marker = snapshot.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new));

        return marker.handle((ignored, throwable) -> {
            logDebug("ReportPortalBridge.drainSubmittedWorkAsync: marker-completed throwable=" + throwableSummary(throwable));
            if (throwable != null) {
                throw new java.util.concurrent.CompletionException(unwrapCompletionFailure(throwable));
            }

            Throwable asyncFailure = ASYNC_FAILURE.get();
            if (asyncFailure != null) {
                logDebug("ReportPortalBridge.drainSubmittedWorkAsync: async-failure-present " + throwableSummary(asyncFailure));
                throw new java.util.concurrent.CompletionException(asyncFailure);
            }

            logDebug("ReportPortalBridge.drainSubmittedWorkAsync: finished");
            return null;
        });
    }

    private static CompletableFuture<Void> failedFuture(Throwable t) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        f.completeExceptionally(t);
        return f;
    }

    private static void submitReportPortalWork(Maybe<String> test, Runnable work) {
        Object key = test == null ? LAUNCH_WORK_KEY : test;
        submitReportPortalWork(key, work);
    }

    private static void submitReportPortalWork(Object orderingKey, Runnable work) {
        logDebug("ReportPortalBridge.submitReportPortalWork: starting asyncLogging=" + ASYNC_LOGGING + " " + bridgeState());
        if (!ASYNC_LOGGING) {
            logDebug("ReportPortalBridge.submitReportPortalWork: running-synchronously");
            runReportPortalWork(work);
            logDebug("ReportPortalBridge.submitReportPortalWork: synchronous-work-complete");
            throwIfAsyncFailure();
            return;
        }

        logDebug("ReportPortalBridge.submitReportPortalWork: before-acquirePendingSlot");
        acquirePendingSlot();
        logDebug("ReportPortalBridge.submitReportPortalWork: after-acquirePendingSlot " + bridgeState());

        Object key = orderingKey == null ? LAUNCH_WORK_KEY : orderingKey;

        try {
            WORK_CHAINS.compute(key, (k, previous) -> {
                logDebug("ReportPortalBridge.submitReportPortalWork: compute-chain previousPresent=" + (previous != null));
                CompletableFuture<Void> base = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.exceptionally(t -> null);

                CompletableFuture<Void> next = base.thenRunAsync(() -> {
                    logDebug("ReportPortalBridge.submitReportPortalWork: worker-start thread=" + Thread.currentThread().getName());
                    runReportPortalWork(work);
                    logDebug("ReportPortalBridge.submitReportPortalWork: worker-finished thread=" + Thread.currentThread().getName());
                }, RP_EXECUTOR);
                next.whenComplete((ignored, throwable) -> {
                    logDebug("ReportPortalBridge.submitReportPortalWork: whenComplete throwable=" + throwableSummary(throwable));
                    try {
                        if (throwable != null) {
                            ASYNC_FAILURE.compareAndSet(null, unwrapCompletionFailure(throwable));
                        }
                    } finally {
                        PENDING_LOG_SLOTS.release();
                        WORK_CHAINS.remove(k, next);
                        logDebug("ReportPortalBridge.submitReportPortalWork: released-slot-and-removed-chain " + bridgeState());
                    }
                });
                logDebug("ReportPortalBridge.submitReportPortalWork: queued-work " + bridgeState());
                return next;
            });
        } catch (RuntimeException e) {
            PENDING_LOG_SLOTS.release();
            logDebug("ReportPortalBridge.submitReportPortalWork: failed-to-queue " + throwableSummary(e));
            throw e;
        }
    }

    private static void runReportPortalWork(Runnable work) {
        try {
            logDebug("ReportPortalBridge.runReportPortalWork: before-work thread=" + Thread.currentThread().getName());
            work.run();
            logDebug("ReportPortalBridge.runReportPortalWork: after-work thread=" + Thread.currentThread().getName());
        } catch (Throwable t) {
            ASYNC_FAILURE.compareAndSet(null, t);
            logDebug("ReportPortalBridge.runReportPortalWork: captured-failure " + throwableSummary(t));
        }
    }

    private static void drainReportPortalWork() {
        logDebug("ReportPortalBridge.drainReportPortalWork: starting " + bridgeState());
        if (!ASYNC_LOGGING) {
            logDebug("ReportPortalBridge.drainReportPortalWork: skipped-async-logging-disabled");
            return;
        }

        long iteration = 0;
        while (true) {
            iteration++;
            List<CompletableFuture<Void>> snapshot = new ArrayList<>(WORK_CHAINS.values());
            logDebug("ReportPortalBridge.drainReportPortalWork: iteration=" + iteration + ", snapshot-size=" + snapshot.size() + " " + bridgeState());
            if (snapshot.isEmpty()) break;

            try {
                logDebug("ReportPortalBridge.drainReportPortalWork: before-wait iteration=" + iteration);
                CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new)).get();
                logDebug("ReportPortalBridge.drainReportPortalWork: after-wait iteration=" + iteration);
            } catch (Exception e) {
                ASYNC_FAILURE.compareAndSet(null, unwrapCompletionFailure(e));
                logDebug("ReportPortalBridge.drainReportPortalWork: wait-failed iteration=" + iteration + " " + throwableSummary(e));
            }
        }

        logDebug("ReportPortalBridge.drainReportPortalWork: before-throwIfAsyncFailure");
        throwIfAsyncFailure();
        logDebug("ReportPortalBridge.drainReportPortalWork: finished " + bridgeState());
    }

    private static void shutdownReportPortalExecutor() {
        logDebug("ReportPortalBridge.shutdownReportPortalExecutor: starting " + bridgeState()
                + ", executorShutdown=" + RP_EXECUTOR.isShutdown()
                + ", executorTerminated=" + RP_EXECUTOR.isTerminated());
        if (!ASYNC_LOGGING || RP_EXECUTOR.isShutdown()) {
            logDebug("ReportPortalBridge.shutdownReportPortalExecutor: skipped asyncLogging=" + ASYNC_LOGGING
                    + ", executorShutdown=" + RP_EXECUTOR.isShutdown());
            return;
        }

        logDebug("ReportPortalBridge.shutdownReportPortalExecutor: calling-shutdown");
        RP_EXECUTOR.shutdown();

        try {
            logDebug("ReportPortalBridge.shutdownReportPortalExecutor: before-awaitTermination timeoutSeconds=" + SHUTDOWN_TIMEOUT_SECONDS);
            if (!RP_EXECUTOR.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logDebug("ReportPortalBridge.shutdownReportPortalExecutor: await-timeout-calling-shutdownNow");
                RP_EXECUTOR.shutdownNow();
                logDebug("ReportPortalBridge.shutdownReportPortalExecutor: before-second-awaitTermination timeoutSeconds=" + SHUTDOWN_TIMEOUT_SECONDS);
                RP_EXECUTOR.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                logDebug("ReportPortalBridge.shutdownReportPortalExecutor: after-second-awaitTermination terminated=" + RP_EXECUTOR.isTerminated());
            } else {
                logDebug("ReportPortalBridge.shutdownReportPortalExecutor: terminated-after-first-await");
            }
        } catch (InterruptedException e) {
            logDebug("ReportPortalBridge.shutdownReportPortalExecutor: interrupted " + throwableSummary(e));
            RP_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logDebug("ReportPortalBridge.shutdownReportPortalExecutor: finished executorShutdown=" + RP_EXECUTOR.isShutdown()
                + ", executorTerminated=" + RP_EXECUTOR.isTerminated());
    }

    private static void acquirePendingSlot() {
        try {
            logDebug("ReportPortalBridge.acquirePendingSlot: before-acquire availableQueueSlots=" + PENDING_LOG_SLOTS.availablePermits()
                    + ", maxPendingLogs=" + MAX_PENDING_LOGS);
            PENDING_LOG_SLOTS.acquire();
            logDebug("ReportPortalBridge.acquirePendingSlot: after-acquire availableQueueSlots=" + PENDING_LOG_SLOTS.availablePermits());
        } catch (InterruptedException e) {
            logDebug("ReportPortalBridge.acquirePendingSlot: interrupted " + throwableSummary(e));
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for ReportPortal log queue capacity", e);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String fallback(String s, String def) {
        String v = blankToNull(s);
        return v == null ? def : v;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static boolean boolSetting(String property, boolean defaultValue) {
        String raw = setting(property);
        if (raw == null || raw.isBlank()) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    private static int intSetting(String property, int defaultValue) {
        String raw = setting(property);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Math.max(1, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String setting(String property) {
        String raw = System.getProperty(property);
        if (raw != null) return raw;
        String env = property.toUpperCase().replace('.', '_').replace('-', '_');
        return System.getenv(env);
    }
}
