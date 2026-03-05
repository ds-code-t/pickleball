// file: tools/dscode/common/reporting/logging/Entry.java
package tools.dscode.common.reporting.logging;

import org.openqa.selenium.WebDriver;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static tools.dscode.common.reporting.WorkBookConsolePrinter.printDebug;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.printError;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.printInfo;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.printTrace;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.printWarn;
import static tools.dscode.coredefinitions.ObjectRegistrationSteps.getDefaultDriver;

/**
 * Entry is NOT inherently thread-safe by default.
 *
 * Opt-in thread-safety is provided by calling {@link #threadSafe()} on the root Entry
 * (or any Entry before concurrent use). When enabled, all public operations that mutate state
 * and/or emit to converters are serialized under a single global lock.
 *
 * IMPORTANT LIMITATION:
 * - Because fields/tags/attachments/children are public and mutable, callers can bypass locking
 *   by mutating them directly (e.g., entry.children.add(...)).
 * - If you enable threadSafe(), treat those collections as read-only and only mutate via Entry methods.
 */
public class Entry {

    // inside tools.dscode.common.reporting.logging.Entry

    // 1) add this field near other flags
    private volatile boolean includeInPassFailSummary = true;

    /**
     * Marks THIS Entry (typically a top-level root scope) as included/excluded from
     * the composite pass/fail percentage in report converters that support it.
     *
     * Default is TRUE to preserve existing behavior.
     */
    public Entry includeInSummary(boolean include) {
        return guarded(() -> {
            this.includeInPassFailSummary = include;
            return this;
        });
    }

    /** Convenience: exclude this scope from pass/fail composite metrics. */
    public Entry excludeFromSummary() {
        return includeInSummary(false);
    }

    /** Read-only: converters can use this on the scope/root. */
    public boolean isIncludedInSummary() {
        return includeInPassFailSummary;
    }

    /**
     * Global lock used when threadSafe is enabled.
     *
     * Why global?
     * - Avoids deadlocks when emit() walks up ancestor chain.
     * - Ensures converter callbacks are not invoked concurrently.
     *
     * Tradeoff: reduces parallelism while enabled (intended “safety mode”).
     */
    private static final Object THREAD_SAFE_LOCK = new Object();

    // Track counts per type for THIS Entry instance
    private final Map<String, AtomicInteger> typeCounts = new ConcurrentHashMap<>();

    public final String id = UUID.randomUUID().toString();
    public final Entry parent;

    // These are read/written by multiple methods; in threadSafe mode we guard access under lock.
    // volatile improves visibility for occasional reads outside guarded methods (best-effort).
    public volatile String text;
    public final long seq;

    public volatile Instant startedAt;     // spans
    public volatile Instant stoppedAt;     // spans
    public volatile Instant timestampedAt; // instant events

    public volatile Status status;
    public volatile Level level;

    // NOTE: still public for minimal breakage. See class-level note.
    public final Map<String, Object> fields = new LinkedHashMap<>();
    public final List<String> tags = new ArrayList<>();
    public final List<Attachment> attachments = new ArrayList<>();
    public final List<Entry> children = new ArrayList<>();

    private final List<BaseConverter> converters = new CopyOnWriteArrayList<>();
    private final AtomicLong seqGen;

    // When true, public ops are serialized using THREAD_SAFE_LOCK
    private volatile boolean threadSafe;

    // Existing flag: allows synchronized children-add only; preserved.
    private volatile boolean sharedChildren;

    private Entry(String text, Entry parent, AtomicLong seqGen, boolean threadSafe) {
        this.text = text;
        this.parent = parent;
        this.seqGen = seqGen;
        this.threadSafe = threadSafe;
        this.seq = seqGen.incrementAndGet();
    }

    public static Entry of(String text) {
        return new Entry(text, null, new AtomicLong(), false);
    }

    /**
     * Enables "safety mode" on THIS entry.
     *
     * All descendants created from this node inherit threadSafe = true.
     * Call this BEFORE using this Entry concurrently from multiple threads.
     */
    public Entry threadSafe() {
        this.threadSafe = true;
        return this;
    }

    /** Allows concurrent child creation under THIS node only (legacy behavior). */
    public Entry sharedChildren() {
        return guarded(() -> {
            sharedChildren = true;
            return this;
        });
    }

    /** Register converter for this entry + descendants (also registers globally). */
    public Entry on(BaseConverter converter) {
        Objects.requireNonNull(converter, "converter");
        return guarded(() -> {
            converters.add(converter);
            Log.global().register(converter);
            return this;
        });
    }

    // ---------------------------------------------------------
    // HIERARCHY
    // ---------------------------------------------------------

    private Entry createChild(String text) {
        // This method is called from guarded public methods.
        // It should still be safe even if some internal call reaches it without a guard.
        Entry e = new Entry(text, this, seqGen, this.threadSafe);

        if (threadSafe || sharedChildren) {
            synchronized (THREAD_SAFE_LOCK) {
                children.add(e);
            }
        } else {
            children.add(e);
        }
        return e;
    }

    /**
     * Structural child Entry only.
     * No timestamp, no emission.
     */
    public Entry child(String text) {
        return guarded(() -> createChild(text));
    }

    /**
     * Convenience: emits an INFO event with an incrementing counter for the given type.
     * Example: STEP 1: click link
     */
    public Entry logWithType(String type, String message) {

        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be null or blank");
        }

        final String normalizedType = type.trim();
        final String msg = (message == null) ? "" : message;

        return guarded(() -> {
            AtomicInteger counter = typeCounts.computeIfAbsent(normalizedType, t -> new AtomicInteger(0));
            int count = counter.incrementAndGet();

            String formatted = normalizedType + " " + count + ": " + msg;
            return info(formatted);
        });
    }


    // ---------------------------------------------------------
    // LOGGING (timestamped + emitted)
    // ---------------------------------------------------------

    private Entry log(Level level, Status status, String message) {
        // Called by guarded public methods
        return createChild(message)
                .level(level)
                .status(status)
                .timestamp();
    }

    public Entry trace(String message) {
        return guarded(() -> {
            printTrace(message);
            return log(Level.TRACE, Status.INFO, message);
        });
    }

    public Entry debug(String message) {
        return guarded(() -> {
            printDebug(message);
            return log(Level.DEBUG, Status.INFO, message);
        });
    }

    public Entry info(String message) {
        return guarded(() -> {
            printInfo(message);
            return log(Level.INFO, Status.INFO, message);
        });
    }

    public Entry warn(String message) {
        return guarded(() -> {
            printWarn(message);
            return log(Level.WARN, Status.WARN, message);
        });
    }

    public Entry error(String message) {
        return guarded(() -> {
            printError(message);
            return log(Level.ERROR, Status.FAIL, message);
        });
    }

    /**
     * Generic instant event (no level/status implied).
     */
    public Entry event(String text) {
        return guarded(() -> createChild(text).timestamp());
    }

    // ---------------------------------------------------------
    // SPANS
    // ---------------------------------------------------------

    public Entry span(String text) {
        return guarded(() -> createChild(text).start());
    }

    // ---------------------------------------------------------
    // FAIL CONVENIENCE (for spans)
    // ---------------------------------------------------------

    /**
     * Convenience for span entries:
     * - If message is non-blank, create an ERROR child event (FAIL).
     * - Stop THIS entry with FAIL status.
     * - Returns THIS entry (fluent).
     */
    public Entry fail(String message) {
        return guarded(() -> {
            printError("FAIL");
            if (message != null && !message.isBlank()) {
                error(message); // creates child ERROR event with FAIL + timestamp + emission
            }
            stop(Status.FAIL); // sets THIS entry to FAIL and emits onStop
            return this;
        });
    }

    public Entry up() {
        // Read-only; no need for locking (best-effort)
        return parent != null ? parent : this;
    }

    // ---------------------------------------------------------
    // METADATA (no emission)
    // ---------------------------------------------------------

    public Entry tag(String tag) {
        return guarded(() -> {
            tags.add(tag);
            return this;
        });
    }

    public Entry tags(String... tags) {
        return guarded(() -> {
            this.tags.addAll(List.of(tags));
            return this;
        });
    }

    public Entry field(String key, Object value) {
        return guarded(() -> {
            fields.put(key, value);
            return this;
        });
    }

    public Entry level(Level level) {
        return guarded(() -> {
            this.level = level;
            return this;
        });
    }

    public Entry status(Status status) {
        return guarded(() -> {
            this.status = status;
            return this;
        });
    }

    // ---------------------------------------------------------
    // ATTACHMENTS (no emission)
    // ---------------------------------------------------------

    public Entry attach(String name, String mime, String data) {
        return guarded(() -> {
            attachments.add(new Attachment(name, mime, data));
            return this;
        });
    }

    /** Matches BaseConverter.screenshot(...) behavior (base64 already provided). */
    public Entry attachScreenshot(String name, String base64) {
        return attach(name, "image/png;base64", base64);
    }

    // ---------------------------------------------------------
    // SCREENSHOT (emits; converter decides how to attach)
    // ---------------------------------------------------------

    public Entry screenshot() {
        return screenshot(getDefaultDriver(), null);
    }

    public Entry screenshot(String name) {
        return screenshot(getDefaultDriver(), name);
    }

    public Entry screenshot(WebDriver driver) {
        return screenshot(driver, null);
    }

    public Entry screenshot(WebDriver driver, String name) {
        return guarded(() -> {
            try {
                emit((scope, converter) -> converter.screenshot(this, driver, name));
                printInfo("Screenshot attached");
            } catch (Throwable t) {
                // keep it safe: log an error event and continue
                error("Failed to take Screenshot due to '" + t.getMessage() + "'");
            }
            return this;
        });
    }

    // ---------------------------------------------------------
    // LIFECYCLE EMISSION
    // ---------------------------------------------------------

    public Entry start() {
        return guarded(() -> {
            printInfo("STARTED: " + text);
            startedAt = Instant.now();
            emit((s, c) -> c.onStart(s, this));
            return this;
        });
    }

    public Entry start(Instant at) {
        return guarded(() -> {
            printInfo("STARTED: " + text);
            startedAt = at;
            emit((s, c) -> c.onStart(s, this));
            return this;
        });
    }

    public Entry timestamp() {
        return guarded(() -> {
            timestampedAt = Instant.now();
            emit((s, c) -> c.onTimestamp(s, this));
            return this;
        });
    }

    public Entry timestamp(Instant at) {
        return guarded(() -> {
            timestampedAt = at;
            emit((s, c) -> c.onTimestamp(s, this));
            return this;
        });
    }

    public Entry stop() {
        return guarded(() -> {
            printInfo("STOPPED: " + text);
            stoppedAt = Instant.now();
            emit((s, c) -> c.onStop(s, this));
            return this;
        });
    }

    public Entry stop(Status status) {
        return guarded(() -> {
            printInfo("STOPPED: " + text);
            this.status = status;
            stoppedAt = Instant.now();
            emit((s, c) -> c.onStop(s, this));
            return this;
        });
    }

    public Entry stop(Status status, String extraText) {
        return guarded(() -> {
            printInfo("STOPPED: " + text);
            this.status = status;
            stoppedAt = Instant.now();
            emit((s, c) -> c.onStop(s, this));
            if (extraText != null && !extraText.isBlank()) {
                createChild(extraText).timestamp();
            }
            return this;
        });
    }

    // ---------------------------------------------------------
    // CONVERTER CLOSE
    // ---------------------------------------------------------

    /** Closes converters attached to THIS entry (does not touch ancestors/descendants). */
    public Entry close() {
        return guarded(() -> {
            for (BaseConverter c : converters) c.close();
            return this;
        });
    }

    /** Closes converters of the given type attached to THIS entry. */
    public Entry close(Class<? extends BaseConverter> type) {
        return guarded(() -> {
            for (BaseConverter c : converters) if (type.isInstance(c)) c.close();
            return this;
        });
    }

    // ---------------------------------------------------------
    // EMISSION BUBBLING
    // ---------------------------------------------------------

    private void emit(EmitCall call) {
        // If threadSafe is enabled, ensure converter callbacks do not run concurrently.
        if (threadSafe) {
            synchronized (THREAD_SAFE_LOCK) {
                emitUnsafe(call);
            }
        } else {
            emitUnsafe(call);
        }
    }

    private void emitUnsafe(EmitCall call) {
        for (Entry n = this; n != null; n = n.parent) {
            for (BaseConverter c : n.converters) call.apply(n, c); // scope = n
        }
    }

    @FunctionalInterface
    private interface EmitCall {
        void apply(Entry scope, BaseConverter converter);
    }

    // ---------------------------------------------------------
    // DURATION HELPERS
    // ---------------------------------------------------------

    public Duration duration() {
        // Read-only helper; uses volatile fields for best-effort visibility
        if (startedAt == null) return Duration.ZERO;
        Instant end = (stoppedAt != null) ? stoppedAt : Instant.now();
        return Duration.between(startedAt, end);
    }

    public String durationFormatted() {
        Duration d = duration();

        long millis = d.toMillis();
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        long seconds = (millis % 60_000) / 1_000;
        long ms = millis % 1_000;

        return String.format("%02d:%02d:%02d.%03d",
                hours, minutes, seconds, ms);
    }

    // ---------------------------------------------------------
    // INTERNAL GUARD HELPERS
    // ---------------------------------------------------------

    private void guarded(Runnable r) {
        if (threadSafe) {
            synchronized (THREAD_SAFE_LOCK) {
                r.run();
            }
        } else {
            r.run();
        }
    }

    private <T> T guarded(Supplier<T> s) {
        if (threadSafe) {
            synchronized (THREAD_SAFE_LOCK) {
                return s.get();
            }
        }
        return s.get();
    }
}