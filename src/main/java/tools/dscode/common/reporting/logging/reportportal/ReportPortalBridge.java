package tools.dscode.common.reporting.logging.reportportal;

import com.epam.reportportal.listeners.ListenerParameters;
import com.epam.reportportal.message.ReportPortalMessage;
import com.epam.reportportal.service.Launch;
import com.epam.reportportal.service.ReportPortal;
import com.epam.reportportal.utils.properties.PropertiesLoader;
import com.epam.ta.reportportal.ws.model.FinishExecutionRQ;
import com.epam.ta.reportportal.ws.model.FinishTestItemRQ;
import com.epam.ta.reportportal.ws.model.StartTestItemRQ;
import com.epam.ta.reportportal.ws.model.attribute.ItemAttributesRQ;
import com.epam.ta.reportportal.ws.model.launch.StartLaunchRQ;
import com.epam.ta.reportportal.ws.model.log.SaveLogRQ;
import io.reactivex.Maybe;
import io.reactivex.exceptions.UndeliverableException;
import io.reactivex.plugins.RxJavaPlugins;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static convenience wrapper for ReportPortal client-java 5.4.8 APIs.
 *
 * - Loads configuration from reportportal.properties automatically
 * - No-ops when rp.enable=false or client can't be built
 * - Maintains per-thread item stack
 */
public final class ReportPortalBridge {

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized;
    private static volatile boolean enabled;

    private static volatile ReportPortal rp;
    private static volatile Launch launch;

    /** launch UUID promise (Maybe<String>) */
    private static volatile Maybe<String> launchUuid = Maybe.empty();

    /** Each thread has its own "current item" stack (store promises, not resolved strings) */
    private static final ThreadLocal<Deque<Maybe<String>>> ITEM_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final AtomicReference<Throwable> ASYNC_FAILURE = new AtomicReference<>();
    private static volatile boolean rxErrorHandlerInstalled;

    private ReportPortalBridge() {}

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

    // -----------------------------
    // Init / config
    // -----------------------------

    public static void initIfNeeded() {
        installRxErrorHandlerIfNeeded();
        if (initialized) return;

        synchronized (INIT_LOCK) {
            if (initialized) return;

            ListenerParameters params = new ListenerParameters(PropertiesLoader.load());
            rp = ReportPortal.builder()
                    .withParameters(params)
                    .build();

            enabled = Optional.ofNullable(rp.getParameters().getEnable()).orElse(false);
            if (!enabled) {
                launch = Launch.NOOP_LAUNCH;
                launchUuid = Maybe.empty();
            }

            initialized = true;
        }
    }

    public static boolean isEnabled() {
        initIfNeeded();
        return enabled && launch != null && launch != Launch.NOOP_LAUNCH;
    }

    // -----------------------------
    // Launch lifecycle
    // -----------------------------

    /**
     * Start launch if needed. Returns a launch UUID promise (Maybe<String>).
     * Idempotent.
     */
    public static Maybe<String> startLaunchIfNeeded(String launchNameOverride, Set<ItemAttributesRQ> attributes) {
        initIfNeeded();
        if (!enabled) return Maybe.empty();
        if (launch != null && launch != Launch.NOOP_LAUNCH) return launchUuid;

        StartLaunchRQ rq = new StartLaunchRQ();
        rq.setName(Optional.ofNullable(blankToNull(launchNameOverride))
                .orElseGet(() -> fallback(rp.getParameters().getLaunchName(), "Launch")));
        rq.setStartTime(Date.from(Instant.now()));
        if (attributes != null && !attributes.isEmpty()) rq.setAttributes(attributes);

        launch = rp.newLaunch(rq);
        launchUuid = launch.start();
        return launchUuid;
    }

    public static void finishLaunch(String status) {
        initIfNeeded();
        if (!enabled || launch == null || launch == Launch.NOOP_LAUNCH) return;

        ITEM_STACK.remove();

        FinishExecutionRQ rq = new FinishExecutionRQ();
        rq.setEndTime(Date.from(Instant.now()));
        rq.setStatus(Optional.ofNullable(blankToNull(status)).orElse("PASSED"));

        launch.finish(rq);

        launch = null;
        launchUuid = Maybe.empty();
    }

    // -----------------------------
    // Item lifecycle
    // -----------------------------

    public static Maybe<String> startItem(String name, String type, Set<ItemAttributesRQ> attributes) {
        return startItem(name, type, attributes, null);
    }

    public static Maybe<String> startItem(String name,
                                          String type,
                                          Set<ItemAttributesRQ> attributes,
                                          Instant startTime) {
        initIfNeeded();
        if (!enabled) return Maybe.empty();

        if (launch == null || launch == Launch.NOOP_LAUNCH) {
            startLaunchIfNeeded(null, null);
        }

        StartTestItemRQ rq = new StartTestItemRQ();
        rq.setName(fallback(name, "Unnamed"));
        rq.setType(fallback(type, "STEP"));
        rq.setStartTime(toDate(startTime));
        if (attributes != null && !attributes.isEmpty()) rq.setAttributes(attributes);

        Deque<Maybe<String>> stack = ITEM_STACK.get();
        Maybe<String> parent = stack.peekLast();

        Maybe<String> item = (parent == null)
                ? launch.startTestItem(rq)
                : launch.startTestItem(parent, rq);

        stack.addLast(item);
        return item;
    }

    public static void finishCurrentItem(String status) {
        finishCurrentItem(status, null);
    }

    public static void finishCurrentItem(String status, Instant endTime) {
        initIfNeeded();
        if (!enabled || launch == null || launch == Launch.NOOP_LAUNCH) return;

        Deque<Maybe<String>> stack = ITEM_STACK.get();
        Maybe<String> item = stack.pollLast();
        if (item == null) return;

        FinishTestItemRQ rq = new FinishTestItemRQ();
        rq.setEndTime(toDate(endTime));
        rq.setStatus(Optional.ofNullable(blankToNull(status)).orElse("PASSED"));

        launch.finishTestItem(item, rq);
    }

    public static void finishAllOpenItems(String status) {
        initIfNeeded();
        if (!enabled) return;

        while (ITEM_STACK.get().peekLast() != null) {
            finishCurrentItem(status);
        }
    }

    // -----------------------------
    // Logging
    // -----------------------------

    public static void log(String level, String message) {
        log(level, message, null);
    }

    /** Log at current item level if an item exists, otherwise launch level. */
    public static void log(String level, String message, Instant logTime) {
        initIfNeeded();
        if (!enabled) return;

        String lvl = Optional.ofNullable(blankToNull(level)).orElse("INFO");
        String msg = fallback(message, "");

        Maybe<String> currentItem = ITEM_STACK.get().peekLast();
        Date when = toDate(logTime);

        if (currentItem == null) {
            launch.log(launchId -> {
                SaveLogRQ rq = new SaveLogRQ();
                rq.setLaunchUuid(launchId);
                rq.setLevel(lvl);
                rq.setLogTime(when);
                rq.setMessage(msg);
                return rq;
            });
        } else {
            launch.log(currentItem, launchId -> {
                SaveLogRQ rq = new SaveLogRQ();
                rq.setLaunchUuid(launchId);
                rq.setLevel(lvl);
                rq.setLogTime(when);
                rq.setMessage(msg);
                return rq;
            });
        }
    }

    public static void logAttachment(String level, String message, byte[] bytes, String filenameHint) {
        logAttachment(level, message, bytes, filenameHint, null);
    }

    public static void logAttachment(String level,
                                     String message,
                                     byte[] bytes,
                                     String filenameHint,
                                     Instant logTime) {
        initIfNeeded();
        if (!enabled || launch == null || launch == Launch.NOOP_LAUNCH) return;

        String lvl = Optional.ofNullable(blankToNull(level)).orElse("INFO");
        String msg = Optional.ofNullable(blankToNull(message)).orElse("attachment");
        byte[] data = Objects.requireNonNullElseGet(bytes, () -> new byte[0]);

        Maybe<String> currentItem = ITEM_STACK.get().peekLast();
        Date when = toDate(logTime);

        Path tmp = null;
        try {
            String safeName = Optional.ofNullable(blankToNull(filenameHint)).orElse("attachment.bin");
            String suffix = safeName.contains(".")
                    ? safeName.substring(safeName.lastIndexOf('.'))
                    : ".bin";

            tmp = Files.createTempFile("rp-", suffix);
            Files.write(tmp, data);
            File file = tmp.toFile();

            if (currentItem == null) {
                launch.log(launchId -> {
                    try {
                        return ReportPortal.toSaveLogRQ(
                                launchId, null, lvl, when, new ReportPortalMessage(file, msg)
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to build ReportPortal launch attachment log", e);
                    }
                });
            } else {
                launch.log(currentItem, launchId -> {
                    try {
                        return ReportPortal.toSaveLogRQ(
                                launchId, null, lvl, when, new ReportPortalMessage(file, msg)
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to build ReportPortal item attachment log", e);
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write ReportPortal attachment temp file", e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // ignore cleanup failure
                }
            }
        }
    }

    // -----------------------------
    // Attributes / tags
    // -----------------------------

    public static void addAttributesToCurrent(Set<ItemAttributesRQ> attributes) {
        // Not supported by Launch API you provided. Apply attributes when starting the item.
    }

    // -----------------------------
    // Helpers
    // -----------------------------

    private static Date toDate(Instant instant) {
        return Date.from(instant != null ? instant : Instant.now());
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