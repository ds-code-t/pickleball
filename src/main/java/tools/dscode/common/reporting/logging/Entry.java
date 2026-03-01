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

import static tools.dscode.coredefinitions.GeneralSteps.getDefaultDriver;

public class Entry {

    // Track counts per type for THIS Entry instance
    private final Map<String, AtomicInteger> typeCounts = new ConcurrentHashMap<>();

    public final String id = UUID.randomUUID().toString();
    public final Entry parent;

    public String text;
    public long seq;

    public Instant startedAt;     // spans
    public Instant stoppedAt;     // spans
    public Instant timestampedAt; // instant events

    public Status status;
    public Level level;

    public final Map<String, Object> fields = new LinkedHashMap<>();
    public final List<String> tags = new ArrayList<>();
    public final List<Attachment> attachments = new ArrayList<>();
    public final List<Entry> children = new ArrayList<>();

    private final List<BaseConverter> converters = new CopyOnWriteArrayList<>();
    private final AtomicLong seqGen;
    private volatile boolean sharedChildren;

    private Entry(String text, Entry parent, AtomicLong seqGen) {
        this.text = text;
        this.parent = parent;
        this.seqGen = seqGen;
        this.seq = seqGen.incrementAndGet();
    }

    public static Entry of(String text) {
        return new Entry(text, null, new AtomicLong());
    }

    /** Allows concurrent child creation under THIS node only. */
    public Entry sharedChildren() {
        sharedChildren = true;
        return this;
    }

    /** Register converter for this entry + descendants (also registers globally). */
    public Entry on(BaseConverter converter) {
        converters.add(converter);
        Log.global().register(converter);
        return this;
    }

    // ---------------------------------------------------------
    // HIERARCHY
    // ---------------------------------------------------------

    private Entry createChild(String text) {
        Entry e = new Entry(text, this, seqGen);
        if (sharedChildren) synchronized (children) { children.add(e); }
        else children.add(e);
        return e;
    }

    /**
     * Structural child Entry only.
     * No timestamp, no emission.
     */
    public Entry child(String text) {
        return createChild(text);
    }

    /**
     * Convenience: emits an INFO event with an incrementing counter for the given type.
     * Example: STEP 1: click link
     */
    public Entry logWithType(String type, String message) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be null or blank");
        }
        if (message == null) message = "";

        String normalizedType = type.trim();

        AtomicInteger counter = typeCounts.computeIfAbsent(normalizedType, t -> new AtomicInteger(0));
        int count = counter.incrementAndGet();

        String formatted = normalizedType + " " + count + ": " + message;
        return info(formatted); // FIX: uses new API (timestamped + emitted)
    }

    // ---------------------------------------------------------
    // LOGGING (timestamped + emitted)
    // ---------------------------------------------------------

    private Entry log(Level level, Status status, String message) {
        return createChild(message)
                .level(level)
                .status(status)
                .timestamp();
    }

    public Entry trace(String message) {
        return log(Level.TRACE, Status.INFO, message);
    }

    public Entry debug(String message) {
        return log(Level.DEBUG, Status.INFO, message);
    }

    public Entry info(String message) {
        return log(Level.INFO, Status.INFO, message);
    }

    public Entry warn(String message) {
        return log(Level.WARN, Status.WARN, message);
    }

    public Entry error(String message) {
        return log(Level.ERROR, Status.FAIL, message);
    }

    /**
     * Generic instant event (no level/status implied).
     */
    public Entry event(String text) {
        return createChild(text).timestamp();
    }

    // ---------------------------------------------------------
    // SPANS
    // ---------------------------------------------------------

    public Entry span(String text) {
        return createChild(text).start();
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
        if (message != null && !message.isBlank()) {
            error(message); // creates child ERROR event with FAIL + timestamp + emission
        }
        stop(Status.FAIL); // sets THIS entry to FAIL and emits onStop
        return this;
    }

    public Entry up() {
        return parent != null ? parent : this;
    }

    // ---------------------------------------------------------
    // METADATA (no emission)
    // ---------------------------------------------------------

    public Entry tag(String tag) { tags.add(tag); return this; }
    public Entry tags(String... tags) { this.tags.addAll(List.of(tags)); return this; }
    public Entry field(String key, Object value) { fields.put(key, value); return this; }

    public Entry level(Level level) { this.level = level; return this; }
    public Entry status(Status status) { this.status = status; return this; }

    // ---------------------------------------------------------
    // ATTACHMENTS (no emission)
    // ---------------------------------------------------------

    public Entry attach(String name, String mime, String data) {
        attachments.add(new Attachment(name, mime, data));
        return this;
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
        try {
            emit((scope, converter) -> converter.screenshot(this, driver, name));
        } catch (Throwable t) {
            // keep it safe: log an error event and continue
            error("Failed to take Screenshot due to '" + t.getMessage() + "'");
        }
        return this;
    }

    // ---------------------------------------------------------
    // LIFECYCLE EMISSION
    // ---------------------------------------------------------

    public Entry start() {
        startedAt = Instant.now();
        emit((s, c) -> c.onStart(s, this));
        return this;
    }

    public Entry start(Instant at) {
        startedAt = at;
        emit((s, c) -> c.onStart(s, this));
        return this;
    }

    public Entry timestamp() {
        timestampedAt = Instant.now();
        emit((s, c) -> c.onTimestamp(s, this));
        return this;
    }

    public Entry timestamp(Instant at) {
        timestampedAt = at;
        emit((s, c) -> c.onTimestamp(s, this));
        return this;
    }

    public Entry stop() {
        stoppedAt = Instant.now();
        emit((s, c) -> c.onStop(s, this));
        return this;
    }

    public Entry stop(Status status) {
        this.status = status;
        stoppedAt = Instant.now();
        emit((s, c) -> c.onStop(s, this));
        return this;
    }

    public Entry stop(Status status, String extraText) {
        this.status = status;
        stoppedAt = Instant.now();
        emit((s, c) -> c.onStop(s, this));
        if (extraText != null && !extraText.isBlank()) {
            createChild(extraText).timestamp();
        }
        return this;
    }

    // ---------------------------------------------------------
    // CONVERTER CLOSE
    // ---------------------------------------------------------

    /** Closes converters attached to THIS entry (does not touch ancestors/descendants). */
    public Entry close() {
        for (BaseConverter c : converters) c.close();
        return this;
    }

    /** Closes converters of the given type attached to THIS entry. */
    public Entry close(Class<? extends BaseConverter> type) {
        for (BaseConverter c : converters) if (type.isInstance(c)) c.close();
        return this;
    }

    // ---------------------------------------------------------
    // EMISSION BUBBLING
    // ---------------------------------------------------------

    private void emit(EmitCall call) {
        for (Entry n = this; n != null; n = n.parent) {
            for (BaseConverter c : n.converters) call.apply(n, c); // scope = n
        }
    }

    @FunctionalInterface
    private interface EmitCall {
        void apply(Entry scope, BaseConverter converter);
    }


    public Duration duration() {
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


}