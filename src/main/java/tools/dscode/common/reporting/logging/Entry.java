// file: tools/dscode/common/reporting/logging/Entry.java
package tools.dscode.common.reporting.logging;

import org.openqa.selenium.WebDriver;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static tools.dscode.coredefinitions.GeneralSteps.getDefaultDriver;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class Entry {

    // Track counts per type for THIS Entry instance
    private final Map<String, AtomicInteger> typeCounts = new ConcurrentHashMap<>();


    public final String id = UUID.randomUUID().toString();
    public final Entry parent;

    public String text;
    public long seq;

    public Instant startedAt;     // spans
    public Instant stoppedAt;     // spans
    public Instant timestampedAt; // instant events (or "last timestamp")

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

    /** Allows concurrent child creation under THIS node only (Option 1). */
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

    // ---- hierarchy ----


    public Entry logWithType(String type, String message) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be null or blank");
        }

        if (message == null) {
            message = "";
        }

        // Normalize type if desired (optional)
        String normalizedType = type.trim();

        // Get or create counter for this type
        AtomicInteger counter = typeCounts.computeIfAbsent(
                normalizedType,
                t -> new AtomicInteger(0)
        );

        int count = counter.incrementAndGet();

        String formatted = normalizedType + " " + count + ": " + message;

        return logInfo(formatted);
    }

    private Entry logEntry(String text) {
        Entry e = new Entry(text, this, seqGen);
        if (sharedChildren) synchronized (children) { children.add(e); }
        else children.add(e);
        return e;
    }

    // ---- child creators (NO emission / NOT timestamped) ----

    /** Creates a child entry with INFO level (not timestamped / no converter emission). */
    public Entry logInfo(String message) {
        return logEntry(message).level(Level.INFO);
    }

    /** Creates a child entry with WARN level (not timestamped / no converter emission). */
    public Entry logWarning(String message) {
        return logEntry(message).level(Level.WARN);
    }

    /** Creates a child entry with ERROR level (not timestamped / no converter emission). */
    public Entry logError(String message) {
        return logEntry(message).level(Level.ERROR);
    }



    public Entry event(String text) {
        return logEntry(text).timestamp();
    }

    public Entry span(String text) {
        return logEntry(text).start();
    }

    // ---- fluent convenience (explicit semantics) ----

    /** Creates a child entry with INFO semantics and timestamps it. */
    public Entry info(String message) {
        return logEntry(message).level(Level.INFO).status(Status.INFO).timestamp();
    }

    /** Creates a child entry with WARN semantics and timestamps it. */
    public Entry warn(String message) {
        return logEntry(message).level(Level.WARN).status(Status.WARN).timestamp();
    }

    /** Creates a child entry with ERROR semantics and timestamps it. */
    public Entry error(String message) {
        return logEntry(message).level(Level.ERROR).status(Status.FAIL).timestamp();
    }

    /** Applies PASS semantics to the current entry (no emission; call stop() explicitly for spans). */
    public Entry pass() {
        return level(Level.INFO).status(Status.PASS);
    }

    /** Applies PASS semantics to the current entry and (optionally) updates its text (no emission). */
    public Entry pass(String message) {
        if (message != null && !message.isBlank()) this.text = message;
        return pass();
    }

    /** Applies FAIL semantics to the current entry (no emission; call stop() explicitly for spans). */
    public Entry fail() {
        return level(Level.ERROR).status(Status.FAIL);
    }

    /** Applies FAIL semantics to the current entry and (optionally) updates its text (no emission). */
    public Entry fail(String message) {
        if (message != null && !message.isBlank()) this.text = message;
        return fail();
    }

    /** Applies SKIP semantics to the current entry (no emission; call stop() explicitly for spans). */
    public Entry skip() {
        return level(Level.WARN).status(Status.SKIP);
    }

    /** Applies SKIP semantics to the current entry and (optionally) updates its text (no emission). */
    public Entry skip(String message) {
        if (message != null && !message.isBlank()) this.text = message;
        return skip();
    }

    public Entry up() {
        return parent != null ? parent : this;
    }

    // ---- metadata (no emission) ----

    public Entry tag(String tag) { tags.add(tag); return this; }
    public Entry tags(String... tags) { this.tags.addAll(List.of(tags)); return this; }

    public Entry field(String key, Object value) { fields.put(key, value); return this; }

    public Entry level(Level level) { this.level = level; return this; }
    public Entry status(Status status) { this.status = status; return this; }

    public Entry attach(String name, String mime, String data) {
        attachments.add(new Attachment(name, mime, data));
        return this;
    }

    public Entry attachScreenshot(String name, String path) {
        return attach(name, "image/png", path);
    }

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
        }
        catch (Throwable t)
        {
            this.logEntry("Failed to take Screenshot due to '" + t.getMessage() + "'")
                    .event(t.getMessage())
                    .timestamp();
        }
        return this;
    }

    // ---- emission: ONLY here ----

    public Entry start() { startedAt = Instant.now(); emit((s,c)->c.onStart(s,this)); return this; }
    public Entry start(Instant at) { startedAt = at; emit((s,c)->c.onStart(s,this)); return this; }

    public Entry timestamp() { timestampedAt = Instant.now(); emit((s,c)->c.onTimestamp(s,this)); return this; }
    public Entry timestamp(Instant at) { timestampedAt = at; emit((s,c)->c.onTimestamp(s,this)); return this; }

    public Entry stop() { stoppedAt = Instant.now(); emit((s,c)->c.onStop(s,this)); return this; }

    public Entry stop(Status status) {
        this.status = status;
        stoppedAt = Instant.now();
        emit((s,c)->c.onStop(s,this));
        return this;
    }

    public Entry stop(Status status, String extraText) {
        this.status = status;
        stoppedAt = Instant.now();
        emit((s,c)->c.onStop(s,this));
        if (extraText != null && !extraText.isBlank()) logEntry(extraText).timestamp();
        return this;
    }

    // ---- closing converters (entry-level only) ----

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

    // ---- bubbling ----

    private void emit(EmitCall call) {
        for (Entry n = this; n != null; n = n.parent) {
            for (BaseConverter c : n.converters) call.apply(n, c); // scope = n
        }
    }

    @FunctionalInterface
    private interface EmitCall {
        void apply(Entry scope, BaseConverter converter);
    }
}
