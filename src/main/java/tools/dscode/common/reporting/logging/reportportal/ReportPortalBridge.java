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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static convenience wrapper for ReportPortal client-java 5.4.8 APIs.
 *
 * - Loads configuration from reportportal.properties automatically
 * - No-ops when rp.enable=false or client can't be built
 * - Maintains per-thread item stack
 * - Supports named shared root items (e.g. SUITE) per launch
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

    /**
     * Shared named root items for the active launch.
     * Example key: "SUITE\u0000Scenarios_root"
     */
    private static final ConcurrentHashMap<String, Maybe<String>> NAMED_ROOT_ITEMS =
            new ConcurrentHashMap<>();

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
                NAMED_ROOT_ITEMS.clear();
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
        NAMED_ROOT_ITEMS.clear();
        return launchUuid;
    }

    public static void finishLaunch(String status) {
        initIfNeeded();
        if (!enabled || launch == null || launch == Launch.NOOP_LAUNCH) return;

        // Named shared roots are not on any one thread's stack, so close them explicitly.
        finishNamedRootItems(status, Instant.now());

        ITEM_STACK.remove();

        FinishExecutionRQ rq = new FinishExecutionRQ();
        rq.setEndTime(Date.from(Instant.now()));
        rq.setStatus(Optional.ofNullable(blankToNull(status)).orElse("PASSED"));

        launch.finish(rq);

        launch = null;
        launchUuid = Maybe.empty();
        NAMED_ROOT_ITEMS.clear();
    }

    // -----------------------------
    // Named shared root items
    // -----------------------------

    public static Maybe<String> startNamedRootIfNeeded(String name,
                                                       String type,
                                                       Set<ItemAttributesRQ> attributes) {
        return startNamedRootIfNeeded(name, type, attributes, null);
    }

    public static Maybe<String> startNamedRootIfNeeded(String name,
                                                       String type,
                                                       Set<ItemAttributesRQ> attributes,
                                                       Instant startTime) {
        initIfNeeded();
        if (!enabled) return Maybe.empty();

        if (launch == null || launch == Launch.NOOP_LAUNCH) {
            startLaunchIfNeeded(null, null);
        }

        String resolvedName = fallback(name, "Root");
        String resolvedType = fallback(type, "SUITE");
        String key = rootKey(resolvedType, resolvedName);

        Maybe<String> existing = NAMED_ROOT_ITEMS.get(key);
        if (existing != null) return existing;

        synchronized (INIT_LOCK) {
            existing = NAMED_ROOT_ITEMS.get(key);
            if (existing != null) return existing;

            StartTestItemRQ rq = new StartTestItemRQ();
            rq.setName(resolvedName);
            rq.setType(resolvedType);
            rq.setStartTime(toDate(startTime));
            if (attributes != null && !attributes.isEmpty()) rq.setAttributes(attributes);

            Maybe<String> item = launch.startTestItem(rq);
            NAMED_ROOT_ITEMS.put(key, item);
            return item;
        }
    }

    /**
     * Push an already-open RP item handle into the current thread's context stack.
     * This is how parallel threads can share the same suite root while keeping their
     * own per-thread parent chains.
     */
    public static void pushParentContext(Maybe<String> parent) {
        initIfNeeded();
        if (!enabled || parent == null) return;

        Deque<Maybe<String>> stack = ITEM_STACK.get();
        Maybe<String> current = stack.peekLast();
        if (sameMaybe(current, parent)) return;

        stack.addLast(parent);
    }

    /**
     * Pop the current thread's context if it matches the expected handle.
     */
    public static void popParentContext(Maybe<String> expected) {
        initIfNeeded();
        if (!enabled) return;

        Deque<Maybe<String>> stack = ITEM_STACK.get();
        Maybe<String> current = stack.peekLast();
        if (current == null) return;

        if (expected == null || sameMaybe(current, expected)) {
            stack.pollLast();
        }
    }

    private static void finishNamedRootItems(String status, Instant endTime) {
        synchronized (INIT_LOCK) {
            if (launch == null || launch == Launch.NOOP_LAUNCH || NAMED_ROOT_ITEMS.isEmpty()) {
                NAMED_ROOT_ITEMS.clear();
                return;
            }

            FinishTestItemRQ rq = new FinishTestItemRQ();
            rq.setEndTime(toDate(endTime));
            rq.setStatus(Optional.ofNullable(blankToNull(status)).orElse("PASSED"));

            for (Maybe<String> item : new LinkedHashSet<>(NAMED_ROOT_ITEMS.values())) {
                launch.finishTestItem(item, rq);
            }

            NAMED_ROOT_ITEMS.clear();
        }
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
        log(level, message, Instant.now());
    }

    public static void log(String level, String message, Instant logTime) {
        initIfNeeded();
        if (!enabled) return;

        String lvl = Optional.ofNullable(blankToNull(level)).orElse("INFO");
        String msg = fallback(message, "");

        Maybe<String> currentItem = ITEM_STACK.get().peekLast();
        Date when = Date.from(logTime != null ? logTime : Instant.now());

        if (currentItem == null) {
            // Launch-level log
            launch.log(launchUuid -> {
                SaveLogRQ rq = new SaveLogRQ();
                rq.setLaunchUuid(launchUuid);
                rq.setLevel(lvl);
                rq.setLogTime(when);
                rq.setMessage(msg);
                return rq;
            });
        } else {
            // Item-level log
            launch.log(currentItem, itemUuid -> {
                SaveLogRQ rq = new SaveLogRQ();
                rq.setItemUuid(itemUuid);
                rq.setLevel(lvl);
                rq.setLogTime(when);
                rq.setMessage(msg);
                return rq;
            });
        }
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
        if (!enabled || launch == null || launch == Launch.NOOP_LAUNCH) return;

        String lvl = Optional.ofNullable(blankToNull(level)).orElse("INFO");
        String msg = Optional.ofNullable(blankToNull(message)).orElse("attachment");
        byte[] data = Objects.requireNonNullElseGet(bytes, () -> new byte[0]);

        Maybe<String> currentItem = ITEM_STACK.get().peekLast();
        Date when = Date.from(logTime != null ? logTime : Instant.now());

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
                launch.log(launchUuid -> {
                    try {
                        return ReportPortal.toSaveLogRQ(
                                launchUuid,
                                null,
                                lvl,
                                when,
                                new ReportPortalMessage(file, msg)
                        );
                    } catch (IOException e) {
                        throw new UncheckedIOException("Failed to build ReportPortal launch attachment log", e);
                    }
                });
            } else {
                launch.log(currentItem, itemUuid -> {
                    try {
                        return ReportPortal.toSaveLogRQ(
                                null,
                                itemUuid,
                                lvl,
                                when,
                                new ReportPortalMessage(file, msg)
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
                } catch (Exception ignored) { }
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

    private static String rootKey(String type, String name) {
        return type + '\u0000' + name;
    }

    private static boolean sameMaybe(Maybe<String> a, Maybe<String> b) {
        return a == b || Objects.equals(a, b);
    }

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