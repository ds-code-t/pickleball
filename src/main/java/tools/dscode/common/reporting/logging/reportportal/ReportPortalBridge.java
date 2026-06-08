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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final Semaphore PENDING_LOG_SLOTS = new Semaphore(Math.max(1, MAX_PENDING_LOGS));
    private static final Object LAUNCH_WORK_KEY = new Object();
    private static final AtomicLong WORKER_ID = new AtomicLong();
    private static final ConcurrentHashMap<Object, CompletableFuture<Void>> WORK_CHAINS = new ConcurrentHashMap<>();
    private static final ExecutorService RP_EXECUTOR = Executors.newFixedThreadPool(WORKER_THREADS, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "dscode-reportportal-log-worker-" + WORKER_ID.incrementAndGet());
            t.setDaemon(false);
            return t;
        }
    });

    private ReportPortalBridge() { }

    private static void installRxErrorHandlerIfNeeded() {
        if (rxErrorHandlerInstalled) return;

        synchronized (INIT_LOCK) {
            if (rxErrorHandlerInstalled) return;

            RxJavaPlugins.setErrorHandler(e -> {
                Throwable t = (e instanceof UndeliverableException ude && ude.getCause() != null)
                        ? ude.getCause()
                        : e;
                ASYNC_FAILURE.compareAndSet(null, t);
            });

            rxErrorHandlerInstalled = true;
        }
    }

    public static void throwIfAsyncFailure() {
        Throwable t = ASYNC_FAILURE.getAndSet(null);
        if (t == null) return;
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        throw new RuntimeException("ReportPortal async failure", t);
    }

    public static void initIfNeeded() {
        installRxErrorHandlerIfNeeded();
        if (initialized) return;

        synchronized (INIT_LOCK) {
            if (initialized) return;

            ListenerParameters params = new ListenerParameters(PropertiesLoader.load());
            enabled = Optional.ofNullable(params.getEnable()).orElse(false);

            if (enabled) {
                ReportPortalHierarchy.setParameters(params);
                ReportPortalHierarchy.setLaunchName(fallback(params.getLaunchName(), "Launch"));
            }

            initialized = true;
        }
    }

    public static boolean isEnabled() {
        initIfNeeded();
        return enabled;
    }

    public static Maybe<String> startLaunchIfNeeded() {
        initIfNeeded();
        if (!enabled) return Maybe.empty();
        if (launchStarted) return launchUuid;

        synchronized (INIT_LOCK) {
            if (!launchStarted) {
                Launch launch = ReportPortalHierarchy.getLaunch();
                launchUuid = launch.start();
                launchStarted = true;
            }
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
        initIfNeeded();
        if (!enabled || !launchStarted) return;

        drainReportPortalWork();

        String finalStatus = fallback(status, "PASSED");

        ReportPortalHierarchy.finishAllSuites(finalStatus);

        FinishExecutionRQ rq = new FinishExecutionRQ();
        rq.setStatus(finalStatus);
        rq.setEndTime(new Date());
        ReportPortalHierarchy.getLaunch().finish(rq);

        CURRENT_TEST.remove();
        launchUuid = Maybe.empty();
        launchStarted = false;
    }

    /** Waits until all previously submitted ReportPortal logs/attachments have been handed off. */
    public static void flush() {
        initIfNeeded();
        if (!enabled) return;
        drainReportPortalWork();
        throwIfAsyncFailure();
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
        initIfNeeded();

        if (!enabled || !ASYNC_LOGGING) {
            try {
                throwIfAsyncFailure();
                return CompletableFuture.completedFuture(null);
            } catch (Throwable t) {
                return failedFuture(t);
            }
        }

        List<CompletableFuture<Void>> snapshot = new ArrayList<>(WORK_CHAINS.values());
        CompletableFuture<Void> marker = snapshot.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new));

        return marker.handle((ignored, throwable) -> {
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
        if (!ASYNC_LOGGING) {
            runReportPortalWork(work);
            throwIfAsyncFailure();
            return;
        }

        acquirePendingSlot();

        Object key = orderingKey == null ? LAUNCH_WORK_KEY : orderingKey;

        try {
            WORK_CHAINS.compute(key, (k, previous) -> {
                CompletableFuture<Void> base = previous == null
                        ? CompletableFuture.completedFuture(null)
                        : previous.exceptionally(t -> null);

                CompletableFuture<Void> next = base.thenRunAsync(() -> runReportPortalWork(work), RP_EXECUTOR);
                next.whenComplete((ignored, throwable) -> {
                    try {
                        if (throwable != null) {
                            ASYNC_FAILURE.compareAndSet(null, unwrapCompletionFailure(throwable));
                        }
                    } finally {
                        PENDING_LOG_SLOTS.release();
                        WORK_CHAINS.remove(k, next);
                    }
                });
                return next;
            });
        } catch (RuntimeException e) {
            PENDING_LOG_SLOTS.release();
            throw e;
        }
    }

    private static void runReportPortalWork(Runnable work) {
        try {
            work.run();
        } catch (Throwable t) {
            ASYNC_FAILURE.compareAndSet(null, t);
        }
    }

    private static void drainReportPortalWork() {
        if (!ASYNC_LOGGING) return;

        while (true) {
            List<CompletableFuture<Void>> snapshot = new ArrayList<>(WORK_CHAINS.values());
            if (snapshot.isEmpty()) break;

            try {
                CompletableFuture.allOf(snapshot.toArray(CompletableFuture[]::new)).get();
            } catch (Exception e) {
                ASYNC_FAILURE.compareAndSet(null, unwrapCompletionFailure(e));
            }
        }

        throwIfAsyncFailure();
    }

    private static void acquirePendingSlot() {
        try {
            PENDING_LOG_SLOTS.acquire();
        } catch (InterruptedException e) {
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
