// file: tools/dscode/common/reporting/logging/Entry.java
package tools.dscode.common.reporting.logging;

import org.openqa.selenium.WebDriver;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static tools.dscode.coredefinitions.GeneralSteps.getDefaultDriver;

public class Entry {

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

    public Entry child(String text) {
        Entry e = new Entry(text, this, seqGen);
        if (sharedChildren) synchronized (children) { children.add(e); }
        else children.add(e);
        return e;
    }

    public Entry event(String text) {
        return child(text).timestamp();
    }

    public Entry span(String text) {
        return child(text).start();
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
           this.child("Failed to take Screenshot due to '" + t.getMessage() + "'").event(t.getMessage()).timestamp();
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
        if (extraText != null && !extraText.isBlank()) child(extraText).timestamp();
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
