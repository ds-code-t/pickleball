package tools.dscode.common.reporting.logging.reportportal;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.message.ReportPortalMessage;
import com.epam.reportportal.message.TypeAwareByteSource;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.utils.files.ByteSource;
import com.epam.reportportal.utils.properties.PropertiesLoader;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import io.reactivex.Maybe;
import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    private static final AtomicReference<Throwable> ASYNC_FAILURE = new AtomicReference<>();
    private static volatile boolean rxErrorHandlerInstalled;

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

    public static void finishCurrentTest(String status) {
        initIfNeeded();
        if (!enabled) return;

        Maybe<String> test = CURRENT_TEST.get();
        if (test != null) {
            ReportPortalHierarchy.finishTest(test, fallback(status, "PASSED"));
            CURRENT_TEST.remove();
            return;
        }

        ReportPortalHierarchy.finishCurrentTest(fallback(status, "PASSED"));
    }

    public static void log(String level, String message) {
        log(level, message, Instant.now());
    }

    public static void log(String level, String message, Instant logTime) {
        initIfNeeded();
        if (!enabled) return;

        startLaunchIfNeeded();

        String lvl = fallback(level, "INFO");
        String msg = safe(message);
        Date when = Date.from(logTime != null ? logTime : Instant.now());

        Maybe<String> test = CURRENT_TEST.get();
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

        String lvl = fallback(level, "INFO");
        String msg = fallback(message, "attachment");
        byte[] data = Objects.requireNonNullElseGet(bytes, () -> new byte[0]);
        Date when = Date.from(logTime != null ? logTime : Instant.now());


        try {
            String safeName = fallback(filenameHint, "attachment.bin");
            String mimeType = URLConnection.guessContentTypeFromName(safeName);
            if (mimeType == null) mimeType = "application/octet-stream";

            TypeAwareByteSource source = new TypeAwareByteSource(ByteSource.wrap(data), mimeType);
            ReportPortalMessage rpMessage = new ReportPortalMessage(source, msg);

            Maybe<String> test = CURRENT_TEST.get();
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

    public static void finishLaunch(String status) {
        initIfNeeded();
        if (!enabled || !launchStarted) return;

        String finalStatus = fallback(status, "PASSED");

        for (String suiteName : KNOWN_SUITES) {
            ReportPortalHierarchy.finishSuite(ReportPortalHierarchy.getOrCreateSuite(suiteName), finalStatus);
        }

        FinishExecutionRQ rq = new FinishExecutionRQ();
        rq.setStatus(finalStatus);
        rq.setEndTime(new Date());
        ReportPortalHierarchy.getLaunch().finish(rq);

        CURRENT_TEST.remove();
        launchUuid = Maybe.empty();
        launchStarted = false;
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