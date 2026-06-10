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
import tools.dscode.common.reporting.logging.Level;

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

import static tools.dscode.common.reporting.logging.LogForwarder.shouldLog;

public final class ReportPortalBridge {

    public static boolean shouldLog = shouldLog(Level.DEBUG);

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

    private static void debug(String message) {
        if (shouldLog) System.out.println("[ReportPortalBridge] " + message);
    }

    private static void debug(String message, Throwable t) {
        if (shouldLog) System.out.println("[ReportPortalBridge] " + message + " | " + t);
    }

    private static void installRxErrorHandlerIfNeeded() {
        if (rxErrorHandlerInstalled) {
            debug("installRxErrorHandlerIfNeeded skip - already installed");
            return;
        }

        synchronized (INIT_LOCK) {
            if (rxErrorHandlerInstalled) {
                debug("installRxErrorHandlerIfNeeded skip inside lock - already installed");
                return;
            }

            debug("installRxErrorHandlerIfNeeded installing RxJava error handler");
            RxJavaPlugins.setErrorHandler(e -> {
                Throwable t = (e instanceof UndeliverableException ude && ude.getCause() != null)
                        ? ude.getCause()
                        : e;
                ASYNC_FAILURE.compareAndSet(null, t);
                debug("RxJava undeliverable/async failure captured", t);
            });

            rxErrorHandlerInstalled = true;
            debug("installRxErrorHandlerIfNeeded complete");
        }
    }

    public static void throwIfAsyncFailure() {
        Throwable t = ASYNC_FAILURE.getAndSet(null);
        if (t == null) {
            debug("throwIfAsyncFailure no failure");
            return;
        }
        debug("throwIfAsyncFailure throwing async failure", t);
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        throw new RuntimeException("ReportPortal async failure", t);
    }

    public static void initIfNeeded() {
        debug("initIfNeeded enter initialized=" + initialized);
        installRxErrorHandlerIfNeeded();
        if (initialized) {
            debug("initIfNeeded exit - already initialized enabled=" + enabled);
            return;
        }

        synchronized (INIT_LOCK) {
            if (initialized) {
                debug("initIfNeeded exit inside lock - already initialized enabled=" + enabled);
                return;
            }

            debug("initIfNeeded loading ReportPortal properties");
            ListenerParameters params = new ListenerParameters(PropertiesLoader.load());
            enabled = Optional.ofNullable(params.getEnable()).orElse(false);
            debug("initIfNeeded properties loaded enabled=" + enabled
                    + ", launchName=" + fallback(params.getLaunchName(), "Launch")
                    + ", asyncLogging=" + ASYNC_LOGGING
                    + ", workerThreads=" + WORKER_THREADS
                    + ", maxPendingLogs=" + MAX_PENDING_LOGS
                    + ", shutdownTimeoutSeconds=" + SHUTDOWN_TIMEOUT_SECONDS);

            if (enabled) {
                debug("initIfNeeded setting ReportPortal hierarchy parameters");
                ReportPortalHierarchy.setParameters(params);
                ReportPortalHierarchy.setLaunchName(fallback(params.getLaunchName(), "Launch"));
                debug("initIfNeeded ReportPortal hierarchy configured");
            }

            initialized = true;
            debug("initIfNeeded complete enabled=" + enabled);
        }
    }

    public static boolean isEnabled() {
        debug("isEnabled enter");
        initIfNeeded();
        debug("isEnabled return enabled=" + enabled);
        return enabled;
    }

    public static Maybe<String> startLaunchIfNeeded() {
        debug("startLaunchIfNeeded enter enabled=" + enabled + ", launchStarted=" + launchStarted);
        initIfNeeded();
        if (!enabled) {
            debug("startLaunchIfNeeded skip - ReportPortal disabled");
            return Maybe.empty();
        }
        if (launchStarted) {
            debug("startLaunchIfNeeded skip - launch already started");
            return launchUuid;
        }

        synchronized (INIT_LOCK) {
            if (!launchStarted) {
                debug("startLaunchIfNeeded getting launch");
                Launch launch = ReportPortalHierarchy.getLaunch();
                debug("startLaunchIfNeeded starting launch");
                launchUuid = launch.start();
                launchStarted = true;
                debug("startLaunchIfNeeded launch started");
            } else {
                debug("startLaunchIfNeeded launch already started inside lock");
            }
            return launchUuid;
        }
    }

    public static Maybe<String> getOrCreateSuite(String suiteName) {
        debug("getOrCreateSuite enter suiteName=" + suiteName);
        initIfNeeded();
        if (!enabled) {
            debug("getOrCreateSuite skip - ReportPortal disabled");
            return Maybe.empty();
        }

        startLaunchIfNeeded();

        String name = blankToNull(suiteName);
        if (name == null) {
            debug("getOrCreateSuite using default/last suite");
            return ReportPortalHierarchy.getDefaultOrLastSuite();
        }

        KNOWN_SUITES.add(name);
        debug("getOrCreateSuite creating/fetching suite=" + name + ", knownSuites=" + KNOWN_SUITES.size());
        return ReportPortalHierarchy.getOrCreateSuite(name);
    }

    public static Maybe<String> getOrCreateSuitePath(List<String> suitePath) {
        debug("getOrCreateSuitePath enter suitePath=" + suitePath);
        initIfNeeded();
        if (!enabled) {
            debug("getOrCreateSuitePath skip - ReportPortal disabled");
            return Maybe.empty();
        }

        startLaunchIfNeeded();

        if (suitePath == null || suitePath.isEmpty()) {
            debug("getOrCreateSuitePath using default/last suite");
            return ReportPortalHierarchy.getDefaultOrLastSuite();
        }

        suitePath.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .forEach(KNOWN_SUITES::add);

        debug("getOrCreateSuitePath creating/fetching suitePath=" + suitePath + ", knownSuites=" + KNOWN_SUITES.size());
        return ReportPortalHierarchy.getOrCreateSuitePath(suitePath);
    }

    public static Maybe<String> startTest(String testName, String suiteName) {
        debug("startTest enter testName=" + testName + ", suiteName=" + suiteName);
        initIfNeeded();
        if (!enabled) {
            debug("startTest skip - ReportPortal disabled");
            return Maybe.empty();
        }

        startLaunchIfNeeded();

        Maybe<String> suite = getOrCreateSuite(suiteName);
        String safeTestName = fallback(testName, "Unnamed");
        debug("startTest starting test=" + safeTestName + ", hasSuite=" + (suite != null));
        Maybe<String> test = (suite == null)
                ? ReportPortalHierarchy.startTest(safeTestName)
                : ReportPortalHierarchy.startTest(safeTestName, suite);

        CURRENT_TEST.set(test);
        debug("startTest complete test=" + safeTestName);
        return test;
    }

    public static Maybe<String> startTest(String testName, List<String> suitePath) {
        debug("startTest enter testName=" + testName + ", suitePath=" + suitePath);
        initIfNeeded();
        if (!enabled) {
            debug("startTest skip - ReportPortal disabled");
            return Maybe.empty();
        }

        startLaunchIfNeeded();

        Maybe<String> suite = getOrCreateSuitePath(suitePath);
        String safeTestName = fallback(testName, "Unnamed");
        debug("startTest starting test=" + safeTestName + ", hasSuite=" + (suite != null));
        Maybe<String> test = (suite == null)
                ? ReportPortalHierarchy.startTest(safeTestName)
                : ReportPortalHierarchy.startTest(safeTestName, suite);

        CURRENT_TEST.set(test);
        debug("startTest complete test=" + safeTestName);
        return test;
    }

    public static void finishCurrentTest(String status) {
        debug("finishCurrentTest enter status=" + status);
        initIfNeeded();
        if (!enabled) {
            debug("finishCurrentTest skip - ReportPortal disabled");
            return;
        }

        Maybe<String> test = CURRENT_TEST.get();
        CURRENT_TEST.remove();
        String finalStatus = fallback(status, "PASSED");
        debug("finishCurrentTest submitting finish work finalStatus=" + finalStatus + ", hasCurrentTest=" + (test != null));

        submitReportPortalWork(test, () -> {
            debug("finishCurrentTest worker start finalStatus=" + finalStatus + ", hasCurrentTest=" + (test != null));
            if (test != null) {
                ReportPortalHierarchy.finishTest(test, finalStatus);
            } else {
                ReportPortalHierarchy.finishCurrentTest(finalStatus);
            }
            debug("finishCurrentTest worker complete finalStatus=" + finalStatus);
        });
    }

    public static void log(String level, String message) {
        log(level, message, Instant.now());
    }

    public static void log(String level, String message, Instant logTime) {
        debug("log enter level=" + level + ", messageLength=" + (message == null ? 0 : message.length()));
        initIfNeeded();
        if (!enabled) {
            debug("log skip - ReportPortal disabled");
            return;
        }

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = safe(message);
        Date when = Date.from(logTime != null ? logTime : Instant.now());
        debug("log submitting level=" + lvl + ", hasCurrentTest=" + (test != null) + ", messageLength=" + msg.length());

        submitReportPortalWork(test, () -> logNow(test, lvl, msg, when));
    }

    private static void logNow(Maybe<String> test, String lvl, String msg, Date when) {
        debug("logNow worker start level=" + lvl + ", hasCurrentTest=" + (test != null) + ", messageLength=" + (msg == null ? 0 : msg.length()));
        if (test != null) {
            ReportPortalHierarchy.getLaunch().log(test, itemUuid -> {
                SaveLogRQ rq = new SaveLogRQ();
                rq.setItemUuid(itemUuid);
                rq.setLevel(lvl);
                rq.setLogTime(when);
                rq.setMessage(msg);
                return rq;
            });
            debug("logNow worker complete - item log");
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
        debug("logNow worker complete - launch log");
    }

    public static void logAttachment(String level, String message, byte[] bytes, String filenameHint) {
        logAttachment(level, message, bytes, filenameHint, Instant.now());
    }

    public static void logAttachment(String level,
                                     String message,
                                     byte[] bytes,
                                     String filenameHint,
                                     Instant logTime) {
        debug("logAttachment enter level=" + level + ", filenameHint=" + filenameHint
                + ", bytes=" + (bytes == null ? 0 : bytes.length));
        initIfNeeded();
        if (!enabled) {
            debug("logAttachment skip - ReportPortal disabled");
            return;
        }

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = fallback(message, "attachment");
        byte[] data = Objects.requireNonNullElseGet(bytes, () -> new byte[0]);
        String safeName = fallback(filenameHint, "attachment.bin");
        Date when = Date.from(logTime != null ? logTime : Instant.now());
        debug("logAttachment submitting level=" + lvl + ", safeName=" + safeName
                + ", bytes=" + data.length + ", hasCurrentTest=" + (test != null));

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
        debug("logAttachmentFile enter level=" + level + ", file=" + file + ", filenameHint=" + filenameHint);
        initIfNeeded();
        if (!enabled) {
            debug("logAttachmentFile skip - ReportPortal disabled");
            return;
        }

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = fallback(message, "attachment");
        String safeName = fallback(filenameHint, file == null || file.getFileName() == null ? "attachment.bin" : file.getFileName().toString());
        Date when = Date.from(logTime != null ? logTime : Instant.now());
        Path safeFile = file == null ? null : file.toAbsolutePath().normalize();
        debug("logAttachmentFile submitting level=" + lvl + ", safeName=" + safeName
                + ", safeFile=" + safeFile + ", exists=" + (safeFile != null && Files.exists(safeFile))
                + ", hasCurrentTest=" + (test != null));

        submitReportPortalWork(test, () -> logAttachmentNow(test, lvl, msg, null, safeFile, safeName, when));
    }

    private static void logAttachmentNow(Maybe<String> test,
                                         String lvl,
                                         String msg,
                                         byte[] data,
                                         Path file,
                                         String safeName,
                                         Date when) {
        debug("logAttachmentNow worker start level=" + lvl + ", safeName=" + safeName
                + ", file=" + file + ", bytes=" + (data == null ? 0 : data.length)
                + ", hasCurrentTest=" + (test != null));
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
                debug("logAttachmentNow worker complete - item attachment safeName=" + safeName);
            } else {
                ReportPortalHierarchy.getLaunch().log(launchId ->
                        ReportPortal.toSaveLogRQ(launchId, null, lvl, when, rpMessage));
                debug("logAttachmentNow worker complete - launch attachment safeName=" + safeName);
            }
        } catch (Exception e) {
            debug("logAttachmentNow failed safeName=" + safeName, e);
            throw new RuntimeException("Failed to log ReportPortal attachment", e);
        }
    }

    /**
     * Prefer a file/path-backed ByteSource if the installed client exposes one.  client-java
     * versions differ, so reflection keeps this drop-in compatible with 5.4.x.  If unavailable,
     * fall back to byte[] materialization on the ReportPortal worker thread, not the scenario thread.
     */
    private static ByteSource byteSourceFromFileOrFallback(Path file) throws Exception {
        if (file == null) {
            debug("byteSourceFromFileOrFallback using empty byte source - null file");
            return ByteSource.wrap(new byte[0]);
        }

        debug("byteSourceFromFileOrFallback enter file=" + file + ", exists=" + Files.exists(file));
        for (Method m : ByteSource.class.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (!ByteSource.class.isAssignableFrom(m.getReturnType())) continue;
            if (m.getParameterCount() != 1) continue;

            Class<?> p = m.getParameterTypes()[0];
            try {
                if (Path.class.isAssignableFrom(p)) {
                    debug("byteSourceFromFileOrFallback using ByteSource factory method=" + m.getName() + " with Path");
                    return (ByteSource) m.invoke(null, file);
                }
                if (File.class.isAssignableFrom(p)) {
                    debug("byteSourceFromFileOrFallback using ByteSource factory method=" + m.getName() + " with File");
                    return (ByteSource) m.invoke(null, file.toFile());
                }
            } catch (ReflectiveOperationException ignored) {
                debug("byteSourceFromFileOrFallback factory method failed method=" + m.getName());
                // Try the next compatible factory method.
            }
        }

        debug("byteSourceFromFileOrFallback falling back to Files.readAllBytes file=" + file);
        return ByteSource.wrap(Files.readAllBytes(file));
    }

    public static void finishLaunch(String status) {
        debug("finishLaunch enter status=" + status + ", enabled=" + enabled + ", launchStarted=" + launchStarted
                + ", workChains=" + WORK_CHAINS.size() + ", pendingSlotsAvailable=" + PENDING_LOG_SLOTS.availablePermits());
        initIfNeeded();

        try {
            if (!enabled || !launchStarted) {
                debug("finishLaunch skip enabled=" + enabled + ", launchStarted=" + launchStarted);
                return;
            }

            debug("finishLaunch draining ReportPortal work");
            drainReportPortalWork();
            debug("finishLaunch drain complete");

            String finalStatus = fallback(status, "PASSED");

            debug("finishLaunch finishing all suites status=" + finalStatus + ", knownSuites=" + KNOWN_SUITES.size());
            ReportPortalHierarchy.finishAllSuites(finalStatus);
            debug("finishLaunch finished all suites");

            FinishExecutionRQ rq = new FinishExecutionRQ();
            rq.setStatus(finalStatus);
            rq.setEndTime(new Date());
            debug("finishLaunch calling ReportPortal launch.finish status=" + finalStatus);
            ReportPortalHierarchy.getLaunch().finish(rq);
            debug("finishLaunch ReportPortal launch.finish returned");

            throwIfAsyncFailure();
            debug("finishLaunch complete");
        } finally {
            debug("finishLaunch cleanup start");
            CURRENT_TEST.remove();
            launchUuid = Maybe.empty();
            launchStarted = false;
            shutdownReportPortalExecutor();
            debug("finishLaunch cleanup complete");
        }
    }

    /**
     * Final JVM cleanup for the bridge worker pool. Call this from the outer
     * @AfterAll path even when ReportPortal was disabled or no launch was started.
     */
    public static void shutdown() {
        debug("shutdown enter");
        shutdownReportPortalExecutor();
        debug("shutdown exit");
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
        debug("drainSubmittedWorkAsync enter enabled=" + enabled + ", asyncLogging=" + ASYNC_LOGGING
                + ", workChains=" + WORK_CHAINS.size());
        initIfNeeded();

        if (!enabled || !ASYNC_LOGGING) {
            try {
                throwIfAsyncFailure();
                debug("drainSubmittedWorkAsync returning completed future - disabled or sync logging");
                return CompletableFuture.completedFuture(null);
            } catch (Throwable t) {
                debug("drainSubmittedWorkAsync returning failed future", t);
                return failedFuture(t);
            }
        }

        List<CompletableFuture<Void>> snapshot = new ArrayList<>(WORK_CHAINS.values());
        debug("drainSubmittedWorkAsync snapshot size=" + snapshot.size());
        CompletableFuture<Void> marker = snapshot.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new));

        return marker.handle((ignored, throwable) -> {
            debug("drainSubmittedWorkAsync marker complete throwable=" + throwable);
            if (throwable != null) {
                throw new java.util.concurrent.CompletionException(unwrapCompletionFailure(throwable));
            }

            Throwable asyncFailure = ASYNC_FAILURE.get();
            if (asyncFailure != null) {
                throw new java.util.concurrent.CompletionException(asyncFailure);
            }

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
        debug("submitReportPortalWork enter asyncLogging=" + ASYNC_LOGGING
                + ", workChains=" + WORK_CHAINS.size()
                + ", pendingSlotsAvailable=" + PENDING_LOG_SLOTS.availablePermits());
        if (!ASYNC_LOGGING) {
            debug("submitReportPortalWork running synchronously");
            runReportPortalWork(work);
            throwIfAsyncFailure();
            debug("submitReportPortalWork synchronous complete");
            return;
        }

        acquirePendingSlot();

        Object key = orderingKey == null ? LAUNCH_WORK_KEY : orderingKey;
        debug("submitReportPortalWork acquired slot key=" + System.identityHashCode(key)
                + ", pendingSlotsAvailable=" + PENDING_LOG_SLOTS.availablePermits());

        try {
            WORK_CHAINS.compute(key, (k, previous) -> {
                CompletableFuture<Void> base = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.exceptionally(t -> null);

                CompletableFuture<Void> next = base.thenRunAsync(() -> {
                    debug("submitReportPortalWork worker dispatch start key=" + System.identityHashCode(k));
                    runReportPortalWork(work);
                    debug("submitReportPortalWork worker dispatch complete key=" + System.identityHashCode(k));
                }, RP_EXECUTOR);
                next.whenComplete((ignored, throwable) -> {
                    try {
                        if (throwable != null) {
                            debug("submitReportPortalWork worker completed with throwable key=" + System.identityHashCode(k), unwrapCompletionFailure(throwable));
                            ASYNC_FAILURE.compareAndSet(null, unwrapCompletionFailure(throwable));
                        } else {
                            debug("submitReportPortalWork worker completed key=" + System.identityHashCode(k));
                        }
                    } finally {
                        PENDING_LOG_SLOTS.release();
                        WORK_CHAINS.remove(k, next);
                        debug("submitReportPortalWork released slot key=" + System.identityHashCode(k)
                                + ", workChains=" + WORK_CHAINS.size()
                                + ", pendingSlotsAvailable=" + PENDING_LOG_SLOTS.availablePermits());
                    }
                });
                debug("submitReportPortalWork chained key=" + System.identityHashCode(k) + ", hadPrevious=" + (previous != null));
                return next;
            });
        } catch (RuntimeException e) {
            debug("submitReportPortalWork failed while scheduling", e);
            PENDING_LOG_SLOTS.release();
            throw e;
        }
    }

    private static void runReportPortalWork(Runnable work) {
        debug("runReportPortalWork start thread=" + Thread.currentThread().getName());
        try {
            work.run();
            debug("runReportPortalWork complete thread=" + Thread.currentThread().getName());
        } catch (Throwable t) {
            debug("runReportPortalWork caught throwable", t);
            ASYNC_FAILURE.compareAndSet(null, t);
        }
    }

    private static void drainReportPortalWork() {
        debug("drainReportPortalWork enter asyncLogging=" + ASYNC_LOGGING + ", workChains=" + WORK_CHAINS.size());
        if (!ASYNC_LOGGING) {
            debug("drainReportPortalWork skip - async logging disabled");
            return;
        }

        int iteration = 0;
        while (true) {
            List<CompletableFuture<Void>> snapshot = new ArrayList<>(WORK_CHAINS.values());
            if (snapshot.isEmpty()) {
                debug("drainReportPortalWork no remaining work after iterations=" + iteration);
                break;
            }

            iteration++;
            debug("drainReportPortalWork waiting iteration=" + iteration + ", snapshotSize=" + snapshot.size()
                    + ", workChains=" + WORK_CHAINS.size()
                    + ", pendingSlotsAvailable=" + PENDING_LOG_SLOTS.availablePermits());
            try {
                CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new)).get();
                debug("drainReportPortalWork wait complete iteration=" + iteration);
            } catch (Exception e) {
                debug("drainReportPortalWork wait failed iteration=" + iteration, e);
                ASYNC_FAILURE.compareAndSet(null, unwrapCompletionFailure(e));
            }
        }

        throwIfAsyncFailure();
        debug("drainReportPortalWork complete");
    }

    private static void shutdownReportPortalExecutor() {
        debug("shutdownReportPortalExecutor enter asyncLogging=" + ASYNC_LOGGING
                + ", isShutdown=" + RP_EXECUTOR.isShutdown()
                + ", isTerminated=" + RP_EXECUTOR.isTerminated());
        if (!ASYNC_LOGGING || RP_EXECUTOR.isShutdown()) {
            debug("shutdownReportPortalExecutor skip");
            return;
        }

        debug("shutdownReportPortalExecutor calling shutdown");
        RP_EXECUTOR.shutdown();

        try {
            debug("shutdownReportPortalExecutor awaiting termination seconds=" + SHUTDOWN_TIMEOUT_SECONDS);
            if (!RP_EXECUTOR.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                debug("shutdownReportPortalExecutor timed out; calling shutdownNow");
                RP_EXECUTOR.shutdownNow();
                debug("shutdownReportPortalExecutor awaiting termination after shutdownNow seconds=" + SHUTDOWN_TIMEOUT_SECONDS);
                RP_EXECUTOR.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            debug("shutdownReportPortalExecutor complete isTerminated=" + RP_EXECUTOR.isTerminated());
        } catch (InterruptedException e) {
            debug("shutdownReportPortalExecutor interrupted", e);
            RP_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void acquirePendingSlot() {
        debug("acquirePendingSlot enter availablePermits=" + PENDING_LOG_SLOTS.availablePermits()
                + ", maxPendingLogs=" + MAX_PENDING_LOGS);
        try {
            PENDING_LOG_SLOTS.acquire();
            debug("acquirePendingSlot acquired availablePermits=" + PENDING_LOG_SLOTS.availablePermits());
        } catch (InterruptedException e) {
            debug("acquirePendingSlot interrupted", e);
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
