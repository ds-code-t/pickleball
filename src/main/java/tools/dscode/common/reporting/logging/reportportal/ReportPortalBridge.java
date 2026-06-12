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
import tools.dscode.common.reporting.logging.CleanupTrace;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class ReportPortalBridge {

    private static final Object INIT_LOCK = new Object();

    private static volatile boolean initialized;
    private static volatile boolean enabled;
    private static volatile boolean launchStarted;

    private static volatile Maybe<String> launchUuid = Maybe.empty();

    private static final ThreadLocal<Maybe<String>> CURRENT_TEST = new ThreadLocal<>();
    private static final Set<String> KNOWN_SUITES = ConcurrentHashMap.newKeySet();

    private static final AtomicReference<Throwable> CLIENT_FAILURE = new AtomicReference<>();
    private static volatile boolean rxErrorHandlerInstalled;

    private ReportPortalBridge() { }

    private static void installRxErrorHandlerIfNeeded() {
        if (rxErrorHandlerInstalled) {
            return;
        }

        synchronized (INIT_LOCK) {
            if (rxErrorHandlerInstalled) {
                return;
            }

            RxJavaPlugins.setErrorHandler(e -> {
                Throwable t = (e instanceof UndeliverableException ude && ude.getCause() != null)
                        ? ude.getCause()
                        : e;
                CLIENT_FAILURE.compareAndSet(null, t);
            });

            rxErrorHandlerInstalled = true;
        }
    }

    public static void throwIfFailure() {
        Throwable t = CLIENT_FAILURE.getAndSet(null);
        if (t == null) {
            return;
        }
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error err) throw err;
        throw new RuntimeException("ReportPortal client failure", t);
    }

    /**
     * Retained for source and binary compatibility. ReportPortal bridge work now runs synchronously.
     */
    @Deprecated(forRemoval = false)
    public static void throwIfAsyncFailure() {
        throwIfFailure();
    }

    public static void initIfNeeded() {
        installRxErrorHandlerIfNeeded();
        if (initialized) {
            return;
        }

        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }

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
        if (!enabled) {
            return Maybe.empty();
        }
        if (launchStarted) {
            return launchUuid;
        }

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
        if (!enabled) {
            return Maybe.empty();
        }

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
        if (!enabled) {
            return Maybe.empty();
        }

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
        if (!enabled) {
            return Maybe.empty();
        }

        startLaunchIfNeeded();

        Maybe<String> suite = getOrCreateSuite(suiteName);
        String safeTestName = fallback(testName, "Unnamed");
        Maybe<String> test = (suite == null)
                ? ReportPortalHierarchy.startTest(safeTestName)
                : ReportPortalHierarchy.startTest(safeTestName, suite);

        CURRENT_TEST.set(test);
        return test;
    }

    public static Maybe<String> startTest(String testName, List<String> suitePath) {
        initIfNeeded();
        if (!enabled) {
            return Maybe.empty();
        }

        startLaunchIfNeeded();

        Maybe<String> suite = getOrCreateSuitePath(suitePath);
        String safeTestName = fallback(testName, "Unnamed");
        Maybe<String> test = (suite == null)
                ? ReportPortalHierarchy.startTest(safeTestName)
                : ReportPortalHierarchy.startTest(safeTestName, suite);

        CURRENT_TEST.set(test);
        return test;
    }

    public static void finishCurrentTest(String status) {
        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] START status=" + status);

        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] START: initIfNeeded()");
        initIfNeeded();
        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] END: initIfNeeded()");

        if (!enabled) {
            CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] SKIP: ReportPortal disabled");
            return;
        }

        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] START: CURRENT_TEST.get()/remove()");
        Maybe<String> test = CURRENT_TEST.get();
        CURRENT_TEST.remove();
        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] END: CURRENT_TEST.get()/remove(), hasCurrentTest=" + (test != null));

        String finalStatus = fallback(status, "PASSED");
        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] START: runReportPortalWork finalStatus="
                + finalStatus + ", hasCurrentTest=" + (test != null));

        runReportPortalWork(() -> {
            CleanupTrace.print("[ReportPortalBridge.finishCurrentTest work] START finalStatus="
                    + finalStatus + ", hasCurrentTest=" + (test != null));
            try {
                if (test != null) {
                    CleanupTrace.print("[ReportPortalBridge.finishCurrentTest work] START: ReportPortalHierarchy.finishTest(" + finalStatus + ")");
                    ReportPortalHierarchy.finishTestAndWait(test, finalStatus);
                    CleanupTrace.print("[ReportPortalBridge.finishCurrentTest work] END: ReportPortalHierarchy.finishTest(" + finalStatus + ")");
                } else {
                    CleanupTrace.print("[ReportPortalBridge.finishCurrentTest work] START: ReportPortalHierarchy.finishCurrentTest(" + finalStatus + ")");
                    ReportPortalHierarchy.finishCurrentTestAndWait(finalStatus);
                    CleanupTrace.print("[ReportPortalBridge.finishCurrentTest work] END: ReportPortalHierarchy.finishCurrentTest(" + finalStatus + ")");
                }
                CleanupTrace.print("[ReportPortalBridge.finishCurrentTest work] COMPLETE finalStatus=" + finalStatus);
            } catch (Throwable t) {
                CleanupTrace.print("[ReportPortalBridge.finishCurrentTest work] THROWABLE: " + describeThrowable(t));
                throw unchecked(t);
            }
        });

        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] END: runReportPortalWork finalStatus=" + finalStatus);
        CleanupTrace.print("[ReportPortalBridge.finishCurrentTest] COMPLETE status=" + finalStatus);
    }

    public static void log(String level, String message) {
        log(level, message, Instant.now());
    }

    public static void log(String level, String message, Instant logTime) {
        initIfNeeded();
        if (!enabled) {
            return;
        }

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = safe(message);
        Date when = Date.from(logTime != null ? logTime : Instant.now());

        runReportPortalWork(() -> logNow(test, lvl, msg, when));
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
        if (!enabled) {
            return;
        }

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = fallback(message, "attachment");
        byte[] data = Objects.requireNonNullElseGet(bytes, () -> new byte[0]);
        String safeName = fallback(filenameHint, "attachment.bin");
        Date when = Date.from(logTime != null ? logTime : Instant.now());

        runReportPortalWork(() -> logAttachmentNow(test, lvl, msg, data, null, safeName, when));
    }

    /**
     * Preferred attachment path. Uses a file-backed byte source when the installed
     * ReportPortal client supports one.
     */
    public static void logAttachmentFile(String level,
                                         String message,
                                         Path file,
                                         String filenameHint,
                                         Instant logTime) {
        initIfNeeded();
        if (!enabled) {
            return;
        }

        startLaunchIfNeeded();

        Maybe<String> test = CURRENT_TEST.get();
        String lvl = fallback(level, "INFO");
        String msg = fallback(message, "attachment");
        String safeName = fallback(filenameHint, file == null || file.getFileName() == null ? "attachment.bin" : file.getFileName().toString());
        Date when = Date.from(logTime != null ? logTime : Instant.now());
        Path safeFile = file == null ? null : file.toAbsolutePath().normalize();

        runReportPortalWork(() -> logAttachmentNow(test, lvl, msg, null, safeFile, safeName, when));
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
     * versions differ, so reflection keeps this drop-in compatible with 5.4.x. If unavailable,
     * fall back to byte[] materialization.
     */
    private static ByteSource byteSourceFromFileOrFallback(Path file) throws Exception {
        if (file == null) {
            return ByteSource.wrap(new byte[0]);
        }

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
        CleanupTrace.print("[ReportPortalBridge.finishLaunch] START status=" + status
                + ", enabled=" + enabled
                + ", launchStarted=" + launchStarted);

        CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: initIfNeeded()");
        try {
            initIfNeeded();
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: initIfNeeded()");
        } catch (Throwable t) {
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] THROWABLE: initIfNeeded() | " + describeThrowable(t));
            throw unchecked(t);
        }

        try {
            if (!enabled || !launchStarted) {
                CleanupTrace.print("[ReportPortalBridge.finishLaunch] SKIP enabled=" + enabled + ", launchStarted=" + launchStarted);
                return;
            }

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: fallback(status, PASSED)");
            String finalStatus = fallback(status, "PASSED");
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: fallback(status, PASSED) finalStatus=" + finalStatus);

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: ReportPortalHierarchy.finishAllSuites("
                    + finalStatus + "), knownSuites=" + KNOWN_SUITES.size());
            ReportPortalHierarchy.finishAllSuites(finalStatus);
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: ReportPortalHierarchy.finishAllSuites(" + finalStatus + ")");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: create FinishExecutionRQ");
            FinishExecutionRQ rq = new FinishExecutionRQ();
            rq.setStatus(finalStatus);
            rq.setEndTime(new Date());
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: create FinishExecutionRQ");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: ReportPortalHierarchy.getLaunch().finish("
                    + finalStatus + ")");
            ReportPortalHierarchy.getLaunch().finish(rq);
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: ReportPortalHierarchy.getLaunch().finish(" + finalStatus + ")");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: throwIfFailure()");
            throwIfFailure();
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: throwIfFailure()");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] COMPLETE status=" + finalStatus);
        } catch (Throwable t) {
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] THROWABLE: " + describeThrowable(t));
            throw unchecked(t);
        } finally {
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: cleanup");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: CURRENT_TEST.remove()");
            CURRENT_TEST.remove();
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: CURRENT_TEST.remove()");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: launchUuid = Maybe.empty()");
            launchUuid = Maybe.empty();
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: launchUuid = Maybe.empty()");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: launchStarted = false");
            launchStarted = false;
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: launchStarted = false");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: KNOWN_SUITES.clear()");
            KNOWN_SUITES.clear();
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: KNOWN_SUITES.clear()");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: CLIENT_FAILURE.set(null)");
            CLIENT_FAILURE.set(null);
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: CLIENT_FAILURE.set(null)");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] START: ReportPortalHierarchy.resetLaunch()");
            ReportPortalHierarchy.resetLaunch();
            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: ReportPortalHierarchy.resetLaunch()");

            CleanupTrace.print("[ReportPortalBridge.finishLaunch] END: cleanup");
        }
    }

    /**
     * Retained for source and binary compatibility. There is no bridge executor to shut down.
     */
    @Deprecated(forRemoval = false)
    public static void shutdown() {
    }

    /**
     * Retained for source and binary compatibility. Work is complete before logging methods return.
     */
    @Deprecated(forRemoval = false)
    public static CompletableFuture<Void> drainSubmittedWorkAsync() {
        initIfNeeded();
        try {
            throwIfFailure();
            return CompletableFuture.completedFuture(null);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private static void runReportPortalWork(Runnable work) {
        work.run();
        throwIfFailure();
    }

    private static RuntimeException unchecked(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new RuntimeException(t);
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) return "null";
        String message = t.getMessage();
        return t.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message);
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

}
