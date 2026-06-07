package tools.dscode.common.reporting.logging;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static tools.dscode.common.GlobalConstants.BOOK_END;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.PrintStyle.DIM;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.PrintStyle.HEADER;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.PrintStyle.LEVEL;
import static tools.dscode.common.reporting.WorkBookConsolePrinter.print;
import static tools.dscode.common.reporting.logging.LogForwarder.getDefaultLoggingLevel;
import static tools.dscode.coredefinitions.BrowserSteps.getCurrentDriver;

public class Entry {
    public int count = 0;
    public int flatCount = 0;
    public String nestedCounts;
    public String normalizedType;
    private final Map<String, Object> defaultDescendantFields = new LinkedHashMap<>();
    private final List<String> defaultDescendantTags = new ArrayList<>();
    protected Map<String, AtomicInteger> typeCounts = new ConcurrentHashMap<>();
    protected Map<String, AtomicInteger> typeFlatCounts = new ConcurrentHashMap<>();;

    private static final Object THREAD_SAFE_LOCK = new Object();

    private volatile boolean inheritedDefaultsApplied;
    private volatile boolean includeInPassFailSummary = true;
    private volatile boolean threadSafe;
    private volatile boolean sharedChildren;

    public final String id = UUID.randomUUID().toString();
    public final Entry parent;
    public final long seq;
    public final int nestingLevel;

    public volatile String text;
    public volatile Instant startedAt;
    public volatile Instant stoppedAt;
    public volatile Instant timestampedAt;
    public volatile Status status;
    public volatile Level level;

    public final Map<String, Object> fields = new LinkedHashMap<>();
    public final List<String> tags = new ArrayList<>();
    public final List<Attachment> attachments = new ArrayList<>();
    public final List<Entry> children = new ArrayList<>();

    private final List<BaseConverter> converters = new CopyOnWriteArrayList<>();
    private final AtomicLong seqGen;

    private Entry(String text, Entry parent, AtomicLong seqGen, boolean threadSafe) {
        this.text = text.replaceAll(BOOK_END, "");
        this.parent = parent;
        this.seqGen = seqGen;
        this.threadSafe = threadSafe;
        this.seq = seqGen.incrementAndGet();
        this.nestingLevel = parent == null
                ? 0
                : parent.nestingLevel + 1;
    }

    public static Entry of(String text) {
        return new Entry(text, null, new AtomicLong(), false);
    }

    public Entry threadSafe() {
        this.threadSafe = true;
        return this;
    }

    public Entry sharedChildren() {
        return guarded(() -> {
            sharedChildren = true;
            return this;
        });
    }

    public Entry on(BaseConverter... convertersToAdd) {
        return guarded(() -> {
            if (convertersToAdd == null) {
                return this;
            }

            for (BaseConverter converter : convertersToAdd) {
                if (converter == null) {
                    continue;
                }

                if (!converters.contains(converter)) {
                    converters.add(converter);
                    Log.global().register(converter);
                }
            }

            return this;
        });
    }

    public Entry on(List<BaseConverter> convertersToAdd) {
        if (convertersToAdd == null) {
            return on((BaseConverter[]) null);
        }

        return on(convertersToAdd.toArray(BaseConverter[]::new));
    }

    public Entry includeInSummary(boolean include) {
        return guarded(() -> {
            includeInPassFailSummary = include;
            return this;
        });
    }

    public Entry excludeFromSummary() {
        return includeInSummary(false);
    }

    public boolean isIncludedInSummary() {
        return includeInPassFailSummary;
    }

    public Entry defaultDescendantFields(String... keyValuePairs) {
        return guarded(() -> {
            putKeyValuePairs(defaultDescendantFields, "defaultDescendantFields", keyValuePairs);
            return this;
        });
    }

    public Entry defaultDescendantTags(String... tags) {
        return guarded(() -> {
            if (tags != null) {
                for (String tag : tags) {
                    if (tag != null && !tag.isBlank()) {
                        defaultDescendantTags.add(tag.trim());
                    }
                }
            }

            return this;
        });
    }

    public Entry child(String text) {
        return guarded(() -> createChild(text));
    }

    public Entry logWithType(String type, String message) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be null or blank");
        }

        return guarded(() -> {
            normalizedType = type.trim();
            if(parent != null){
                typeFlatCounts = parent.typeFlatCounts;
                if(parent.normalizedType.equals(normalizedType))
                    typeCounts =  parent.typeCounts;
            }


            System.out.println("@@entry: " + text);
            System.out.println("@@typeCounts: " + typeCounts);
            count = typeCounts
                    .computeIfAbsent(normalizedType, ignored -> new AtomicInteger())
                    .incrementAndGet();
            System.out.println("@@count: " + count);
            System.out.println("- - - - - - - - - - -");
            flatCount = typeFlatCounts.computeIfAbsent(normalizedType, ignored -> new AtomicInteger())
                    .incrementAndGet();
            nestedCounts = parent == null || parent.nestedCounts == null || parent.nestedCounts.isBlank() ? String.valueOf(count) : parent.nestedCounts + "." + count;
            System.out.println("@@flatCount: " + flatCount);
            System.out.println("@@nestedCounts: " + nestedCounts);
            String nestingText = nestedCounts.contains(".") ? " (" + nestedCounts + ") " : "  ";
            return logHeader(flatCount + " " + normalizedType + nestingText + "\u201C" + safe(message) + "\u201D");
        });
    }

    private Entry logHeader(String message) {
        return print(log(Level.INFO, Status.PASS, message), HEADER);
    }

    Entry logSkipped(String message) {
        return print(log(getDefaultLoggingLevel(), Status.SKIP, message), DIM);
    }

    Entry log(Level level, Status status, String message) {
        return createChild(message)
                .level(level)
                .status(status)
                .timestamp();
    }

    public Entry trace(String message) {
        return logAndPrint(Level.TRACE, Status.PASS, message);
    }

    public Entry debug(String message) {
        return logAndPrint(Level.DEBUG, Status.PASS, message);
    }

    public Entry info(String message) {
        return logAndPrint(Level.INFO, Status.PASS, message);
    }

    public Entry warn(String message) {
        return logAndPrint(Level.WARN, Status.UNKNOWN, message);
    }

    public Entry error(String message) {
        return logAndPrint(Level.ERROR, Status.FAIL, message);
    }

    private Entry logAndPrint(Level level, Status status, String message) {
        return guarded(() -> print(log(level, status, message), LEVEL));
    }

    public Entry event(String text) {
        return guarded(() -> createChild(text).timestamp());
    }

    public Entry span(String text) {
        return guarded(() -> createChild(text).start());
    }

    public Entry fail(String message) {
        return guarded(() -> {
            print(Entry.of("FAIL").level(Level.ERROR), LEVEL);

            if (message != null && !message.isBlank()) {
                error(message);
            }

            return stop(Status.FAIL);
        });
    }

    public Entry skip(String message) {
        return guarded(() -> {
            print(Entry.of("SKIP"), DIM);

            if (message != null && !message.isBlank()) {
                logSkipped(message);
            }

            return stop(Status.SKIP);
        });
    }

    public Entry up() {
        return parent == null ? this : parent;
    }

    public Entry tag(String tag) {
        return guarded(() -> {
            tags.add(tag);
            return this;
        });
    }

    public Entry tags(String... tags) {
        return guarded(() -> {
            if (tags != null) {
                this.tags.addAll(List.of(tags));
            }

            return this;
        });
    }

    public boolean hasAnyTag(String... wantedTags) {
        return guarded(() -> hasAnyTagIn(tags, wantedTags));
    }

    public boolean hasAncestorWithAnyTag(String... wantedTags) {
        return guarded(() -> {
            for (Entry n = parent; n != null; n = n.parent) {
                if (hasAnyTagIn(n.tags, wantedTags)) return true;
            }

            return false;
        });
    }

    public Entry field(String... keyValuePairs) {
        return guarded(() -> {
            putKeyValuePairs(fields, "field", keyValuePairs);
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

    public Entry attach(String name, String mime, String data) {
        return attach(Attachment.of(name, mime, data));
    }

    public Entry attach(Attachment attachment) {
        return guarded(() -> {
            if (attachment != null) {
                attachments.add(attachment);
            }
            return this;
        });
    }

    public Entry attachScreenshot(String name, String base64) {
        String filename = name == null || name.isBlank()
                ? "screenshot.png"
                : name.endsWith(".png") ? name : name + ".png";
        return attach(Attachment.base64File(filename, "image/png", base64));
    }

    public Entry screenshot() {
        return screenshot(getCurrentDriver(), null);
    }

    public Entry screenshot(String name) {
        return screenshot(getCurrentDriver(), name);
    }

    public Entry screenshot(WebDriver driver) {
        return screenshot(driver, null);
    }

    public Entry screenshot(WebDriver driver, String name) {
        return guarded(() -> {
            try {
                String label = name == null || name.isBlank() ? "Screenshot" : name;
                String filename = name == null || name.isBlank()
                        ? "screenshot.png"
                        : name.endsWith(".png") ? name : name + ".png";

                createChild(label)
                        .level(Level.INFO)
                        .status(Status.PASS)
                        .attach(Attachment.base64File(
                                filename,
                                "image/png",
                                ((TakesScreenshot) driver).getScreenshotAs(OutputType.BASE64)))
                        .tags("Screenshot")
                        .timestamp();

//                info("Screenshot attached");
            } catch (Throwable t) {
                error("Failed to take Screenshot due to \"" + t.getMessage() + "\"");
            }

            return this;
        });
    }



    public Entry start(String ... texts) {
        return start(Instant.now(), texts);
    }

    public Entry start(Instant at, String ... texts) {
        return guarded(() -> {
            String appendedText =  texts.length == 0 ? "" : ": "+  String.join(", ", texts);
            print(Entry.of("STARTED: " + safe(text) + appendedText).level(Level.DEBUG), LEVEL);
            startedAt = at;
            emit((scope, converter) -> converter.onStart(scope, this));
            return this;
        });
    }

    public Entry timestamp() {
        return timestamp(Instant.now());
    }

    public Entry timestamp(Instant at) {
        return guarded(() -> {
            timestampedAt = at;
            emit((scope, converter) -> converter.onTimestamp(scope, this));
            return this;
        });
    }

    public Entry stop() {
        return guarded(() -> {
            print(Entry.of("STOPPED: " + safe(text)).level(Level.DEBUG), LEVEL);
            stoppedAt = Instant.now();
            emit((scope, converter) -> converter.onStop(scope, this));
            return this;
        });
    }

    public Entry stop(Status status) {
        return guarded(() -> {
            this.status = status;
            print(Entry.of("STOPPED: " + safe(text)).level(Level.INFO), LEVEL);
            stoppedAt = Instant.now();
            emit((scope, converter) -> converter.onStop(scope, this));
            return this;
        });
    }

    public Entry stop(Status status, String extraText) {
        return guarded(() -> {
            stop(status);

            if (extraText != null && !extraText.isBlank()) {
                createChild(extraText).timestamp();
            }

            return this;
        });
    }

    public Entry close() {
        return guarded(() -> {
            converters.forEach(BaseConverter::close);
            return this;
        });
    }

    public Entry close(Class<? extends BaseConverter> type) {
        return guarded(() -> {
            for (BaseConverter converter : converters) {
                if (type.isInstance(converter)) {
                    converter.close();
                }
            }

            return this;
        });
    }

    public Duration duration() {
        if (startedAt == null) return Duration.ZERO;

        Instant end = stoppedAt == null ? Instant.now() : stoppedAt;
        return Duration.between(startedAt, end);
    }

    public String durationFormatted() {
        long millis = duration().toMillis();
        long hours = millis / 3_600_000;
        long minutes = (millis % 3_600_000) / 60_000;
        long seconds = (millis % 60_000) / 1_000;
        long ms = millis % 1_000;

        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, ms);
    }

    public String flatten() {
        return guarded(() -> {
            StringBuilder sb = new StringBuilder(512);
            appendFlattened(sb, this, 0);
            return sb.toString();
        });
    }

    public String indentedText() {
        return indentedSpace() + safe(text);
    }

    public String indentedSpace() {
        return "  ".repeat(Math.max(0, nestingLevel));
    }

    private Entry createChild(String text) {
        Entry entry = new Entry(text, this, seqGen, threadSafe);

        if (threadSafe || sharedChildren) {
            synchronized (THREAD_SAFE_LOCK) {
                children.add(entry);
            }
        } else {
            children.add(entry);
        }

        return entry;
    }

    private void emit(EmitCall call) {
        if (threadSafe) {
            synchronized (THREAD_SAFE_LOCK) {
                applyInheritedDefaultsToThisEntry();
                emitUnsafe(call);
            }
        } else {
            applyInheritedDefaultsToThisEntry();
            emitUnsafe(call);
        }
    }

    private void emitUnsafe(EmitCall call) {
        IdentityHashMap<BaseConverter, Entry> seen = new IdentityHashMap<>();

        for (Entry n = this; n != null; n = n.parent) {
            for (BaseConverter converter : n.converters) {
                if (seen.putIfAbsent(converter, n) == null) {
                    call.apply(n, converter);
                }
            }
        }
    }

    private void applyInheritedDefaultsToThisEntry() {
        if (inheritedDefaultsApplied) return;

        LinkedHashMap<String, Object> inheritedFields = new LinkedHashMap<>();
        LinkedHashSet<String> inheritedTags = new LinkedHashSet<>();

        List<Entry> ancestors = new ArrayList<>();
        for (Entry n = parent; n != null; n = n.parent) {
            ancestors.add(n);
        }

        Collections.reverse(ancestors);

        for (Entry ancestor : ancestors) {
            inheritedFields.putAll(ancestor.defaultDescendantFields);
            inheritedTags.addAll(ancestor.defaultDescendantTags);
        }

        inheritedFields.forEach(fields::putIfAbsent);

        for (String tag : inheritedTags) {
            if (!tags.contains(tag)) {
                tags.add(tag);
            }
        }

        inheritedDefaultsApplied = true;
    }

    private static void putKeyValuePairs(
            Map<String, Object> target,
            String methodName,
            String... keyValuePairs
    ) {
        if (keyValuePairs == null) return;

        for (String pair : keyValuePairs) {
            if (pair == null || pair.isBlank()) continue;

            int colon = pair.indexOf(':');
            if (colon <= 0 || colon == pair.length() - 1) {
                throw new IllegalArgumentException(
                        methodName + " entries must use 'key:value' format: " + pair
                );
            }

            String key = pair.substring(0, colon).trim();
            String value = pair.substring(colon + 1).trim();

            if (key.isBlank()) {
                throw new IllegalArgumentException(methodName + " key must not be blank: " + pair);
            }

            target.put(key, value);
        }
    }

    private static boolean hasAnyTagIn(List<String> actualTags, String... wantedTags) {
        if (actualTags == null || wantedTags == null) return false;

        for (String wanted : wantedTags) {
            if (wanted == null || wanted.isBlank()) continue;

            for (String actual : actualTags) {
                if (wanted.trim().equals(actual == null ? "" : actual.trim())) {
                    return true;
                }
            }
        }

        return false;
    }

    private static void appendFlattened(StringBuilder sb, Entry entry, int depth) {
        String indent = "  ".repeat(Math.max(0, depth));

        sb.append(indent).append(safe(entry.text)).append('\n');

        if (entry.status != null) sb.append(indent).append("status: ").append(entry.status).append('\n');
        if (entry.level != null) sb.append(indent).append("level: ").append(entry.level).append('\n');
        if (entry.startedAt != null) sb.append(indent).append("started: ").append(entry.startedAt).append('\n');
        if (entry.stoppedAt != null) sb.append(indent).append("stopped: ").append(entry.stoppedAt).append('\n');

        if (entry.startedAt != null || entry.stoppedAt != null) {
            sb.append(indent).append("duration: ").append(entry.durationFormatted()).append('\n');
        } else if (entry.timestampedAt != null) {
            sb.append(indent).append("time: ").append(entry.timestampedAt).append('\n');
        }

        if (!entry.fields.isEmpty()) {
            sb.append(indent).append("fields:").append('\n');
            entry.fields.forEach((k, v) ->
                    sb.append(indent).append("- ").append(k).append(": ").append(v == null ? "" : v).append('\n'));
        }

        if (!entry.tags.isEmpty()) {
            sb.append(indent).append("tags:").append('\n');
            entry.tags.forEach(tag ->
                    sb.append(indent).append("- ").append(safe(tag)).append('\n'));
        }

        for (Entry child : entry.children) {
            sb.append('\n');
            appendFlattened(sb, child, depth + 1);
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private <T> T guarded(Supplier<T> supplier) {
        if (threadSafe) {
            synchronized (THREAD_SAFE_LOCK) {
                return supplier.get();
            }
        }

        return supplier.get();
    }

    @FunctionalInterface
    private interface EmitCall {
        void apply(Entry scope, BaseConverter converter);
    }
}