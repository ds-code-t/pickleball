package tools.dscode.engine.runtime;

import tools.dscode.engine.EngineLog;
import tools.dscode.store.StoreRun;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Heap gate for a new snapshot context. Defaults live in config, not in a branch.
 * Below {@code pkb_heap_subprocess_percent} the context stays in-process.
 * Between that line and {@code pkb_heap_refuse_percent}, a free pool slot starts a cold JVM.
 * At or above the refuse line the warm spine still does not take the work in-process.
 * A full pool fails with {@code error: heap gate refused a new context} and {@code fix: spine sample}.
 */
public final class ResourceGate {
    public static final String REFUSED = "error: heap gate refused a new context\nfix: spine sample";

    private static volatile Supplier<Reading> injected;
    private static volatile Decision injectedDecision;

    private ResourceGate() {
    }

    public enum Decision {
        ALLOW, SUBPROCESS, REFUSE;

        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Reading(long heapUsed, long heapMax, long nonHeapUsed, long gcTimeMs, int threadCount) {
    }

    public record Report(Reading reading, Decision decision) {
    }

    /** Tests supply the next memory reading. Production leaves this unset. */
    public static void inject(Supplier<Reading> reading) {
        injected = reading;
    }

    public static void clearInjected() {
        injected = null;
        injectedDecision = null;
    }

    /** Tests force the next warm-CDP decision. Production leaves this unset. */
    public static void injectDecision(Decision decision) {
        injectedDecision = decision;
    }

    /**
     * Decision for the warm CDP hold. An injected decision wins. Otherwise this is
     * {@link #report(Path, String)} for snapshot {@code warm-cdp}.
     */
    public static Decision decisionNow(Path project) {
        Decision forced = injectedDecision;
        if (forced != null) return forced;
        return report(project, "warm-cdp").decision();
    }

    /**
     * True when storing {@code additionalBytes} stays under {@code pkb_heap_refuse_percent}.
     * A tree at or over that line is not cached.
     */
    public static boolean allowCache(long additionalBytes) {
        Reading reading = sample();
        long extra = additionalBytes < 0 ? 0 : additionalBytes;
        if (reading.heapMax() <= 0) return true;
        if (reading.heapUsed() > Long.MAX_VALUE - extra) return false;
        int refuse = integer("pkb_heap_refuse_percent", "PKB_HEAP_REFUSE_PERCENT", 85);
        long percent = ((reading.heapUsed() + extra) * 100L) / reading.heapMax();
        return percent < refuse;
    }

    public static Reading sample() {
        Supplier<Reading> override = injected;
        if (override != null) return override.get();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long gc = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long time = bean.getCollectionTime();
            if (time > 0) gc += time;
        }
        return new Reading(
                heap.getUsed(),
                heap.getMax(),
                nonHeap.getUsed(),
                gc,
                ManagementFactory.getThreadMXBean().getThreadCount());
    }

    public static int percent(Reading reading) {
        if (reading == null || reading.heapMax() <= 0) return 0;
        long value = (reading.heapUsed() * 100L) / reading.heapMax();
        if (value < 0) return 0;
        return (int) Math.min(100, value);
    }

    public static Decision decide(Reading reading, int liveChildren) {
        int subprocess = integer("pkb_heap_subprocess_percent", "PKB_HEAP_SUBPROCESS_PERCENT", 70);
        int refuse = integer("pkb_heap_refuse_percent", "PKB_HEAP_REFUSE_PERCENT", 85);
        int pool = integer("pkb_worker_pool", "PKB_WORKER_POOL", 1);
        int used = percent(reading);
        boolean free = liveChildren < pool;
        if (used < subprocess) return Decision.ALLOW;
        if (free) return Decision.SUBPROCESS;
        return Decision.REFUSE;
    }

    public static Report report(Path project, String snapshotId) {
        Reading reading = sample();
        Decision decision = decide(reading, WarmSpine.shared().liveChildren());
        String entity = snapshotId == null || snapshotId.isBlank() ? "sample" : snapshotId;
        EngineLog.log(project, "gate", entity,
                "sample heapUsed=" + reading.heapUsed()
                        + " heapMax=" + reading.heapMax()
                        + " nonHeapUsed=" + reading.nonHeapUsed()
                        + " gcTimeMs=" + reading.gcTimeMs()
                        + " threadCount=" + reading.threadCount()
                        + " decision=" + decision.wire());
        return new Report(reading, decision);
    }

    /**
     * Accepts one new snapshot context. {@code mainClass} is the cold JVM entry when the
     * decision is {@code subprocess}. The child is not given the warm loader.
     */
    public static Process admit(Path project, String snapshotId, String mainClass) throws Exception {
        Report report = report(project, snapshotId);
        if (report.decision() == Decision.REFUSE) throw new IllegalStateException(REFUSED);
        if (report.decision() == Decision.ALLOW) return null;
        String classpath = StoreRun.wrapperClasspath(project);
        if (classpath.isBlank()) {
            throw new IllegalStateException(
                    "error: subprocess classpath is empty\nfix: run the project wrapper once so the classpath cache exists");
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path args = argumentFile(classpath, mainClass);
        ProcessBuilder builder = new ProcessBuilder(List.of(java, "@" + args.toAbsolutePath().normalize()));
        builder.directory((project == null ? Path.of("") : project).toAbsolutePath().normalize().toFile());
        return WarmSpine.shared().track(builder.start());
    }

    /**
     * Windows rejects a classpath that does not fit on the command line. The child is
     * {@code java @argfile} with {@code -Dpkb_warm=off}. Stdout stays a pipe so the
     * caller can read a child that prints and exits. The step channel is not attached here.
     */
    private static Path argumentFile(String classpath, String mainClass) throws IOException {
        Path file = Files.createTempFile("pkb-cold-", ".args");
        file.toFile().deleteOnExit();
        List<String> lines = new ArrayList<>();
        lines.add("-Dfile.encoding=UTF-8");
        lines.add("-Dstdout.encoding=UTF-8");
        lines.add("-Dstderr.encoding=UTF-8");
        lines.add("-Dpkb_warm=off");
        String store = System.getProperty("pkb_store");
        if (store != null && !store.isBlank() && store.indexOf('\n') < 0 && store.indexOf('\r') < 0 && store.indexOf('"') < 0) {
            lines.add("-Dpkb_store=" + store.trim());
        }
        lines.add("-cp");
        lines.add(quote(classpath));
        lines.add(mainClass);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    /**
     * Inside a quoted argument file token, a backslash starts an escape, so a Windows
     * path is stored with each backslash doubled.
     */
    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' || ch == '"') quoted.append('\\');
            quoted.append(ch);
        }
        return quoted.append('"').toString();
    }

    public static String json(Report report) {
        Reading reading = report.reading();
        return "{\"heapUsed\":" + reading.heapUsed()
                + ",\"heapMax\":" + reading.heapMax()
                + ",\"nonHeapUsed\":" + reading.nonHeapUsed()
                + ",\"gcTimeMs\":" + reading.gcTimeMs()
                + ",\"threadCount\":" + reading.threadCount()
                + ",\"decision\":\"" + report.decision().wire() + "\"}";
    }

    private static int integer(String property, String env, int fallback) {
        String raw = WarmFlags.setting(property, env);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException failure) {
            return fallback;
        }
    }
}
