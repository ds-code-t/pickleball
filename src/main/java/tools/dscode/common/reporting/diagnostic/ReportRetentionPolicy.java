package tools.dscode.common.reporting.diagnostic;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReportRetentionPolicy {
    public enum Mode { ALL, FAILED, NONE }

    private static volatile Mode mode = Mode.ALL;
    private static final AtomicInteger passed = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
    private static final AtomicInteger interrupted = new AtomicInteger();

    private ReportRetentionPolicy() {
    }

    public static void configure(String value) {
        mode = parse(value);
        resetRun();
    }

    public static Mode mode() {
        return mode;
    }

    public static String configuredValue() {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    public static void resetRun() {
        passed.set(0);
        failed.set(0);
        interrupted.set(0);
    }

    public static void recordScenario(boolean scenarioFailed, boolean scenarioInterrupted) {
        if (scenarioInterrupted) interrupted.incrementAndGet();
        if (scenarioFailed) failed.incrementAndGet();
        else if (!scenarioInterrupted) passed.incrementAndGet();
    }

    public static boolean keepScenarioDetails(boolean scenarioFailed, boolean scenarioInterrupted) {
        return switch (mode) {
            case ALL -> true;
            case FAILED -> scenarioFailed || scenarioInterrupted;
            case NONE -> false;
        };
    }

    public static boolean writeAutomaticScenarioFiles(boolean scenarioFailed, boolean scenarioInterrupted) {
        return keepScenarioDetails(scenarioFailed, scenarioInterrupted);
    }

    public static boolean writeAutomaticRunFiles() {
        return switch (mode) {
            case ALL -> true;
            case FAILED -> failed.get() > 0 || interrupted.get() > 0;
            case NONE -> false;
        };
    }

    public static boolean hasProblemScenario() {
        return failed.get() > 0 || interrupted.get() > 0;
    }

    public static int passedCount() { return passed.get(); }
    public static int failedCount() { return failed.get(); }
    public static int interruptedCount() { return interrupted.get(); }

    static Mode parse(String value) {
        if (value == null || value.isBlank()) return Mode.ALL;
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            System.err.println("[Pickleball] Unknown pkb_reportretention='" + value + "'; using 'all'.");
            return Mode.ALL;
        }
    }
}
