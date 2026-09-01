package tools.dscode.parallelutilities;

import tools.dscode.common.reporting.diagnostic.AgentBrowserLadder;

import java.nio.file.Path;

/**
 * Conservative high parallel-count estimate from JVM-visible CPU and heap.
 *
 * <p>Chrome workers are RAM-heavy, so this never blindly equals core count on a
 * large box. Formula:</p>
 *
 * <pre>
 * max(2, min(availableProcessors, floor(maxMemoryMB / 512), 24))
 * </pre>
 *
 * <p>{@code pkb_parallel=auto} resolves to this estimate at run start. An
 * explicit positive integer is left unchanged. Omitting {@code pkb_parallel}
 * does not enable parallel execution.</p>
 */
public final class ParallelCountEstimator {
    public static final String AUTO_VALUE = "auto";
    public static final int MIN_WORKERS = 2;
    public static final int MAX_WORKERS = 24;
    public static final int MEMORY_MB_PER_WORKER = 512;

    private ParallelCountEstimator() {
    }

    public static int estimate() {
        Runtime runtime = Runtime.getRuntime();
        return estimate(runtime.availableProcessors(), runtime.maxMemory());
    }

    public static int estimate(int availableProcessors, long maxMemoryBytes) {
        int cores = Math.max(1, availableProcessors);
        long maxMemoryMb = Math.max(0L, maxMemoryBytes / (1024L * 1024L));
        int fromMemory = (int) Math.min(Integer.MAX_VALUE, maxMemoryMb / MEMORY_MB_PER_WORKER);
        int capped = Math.min(cores, Math.min(fromMemory, MAX_WORKERS));
        return Math.max(MIN_WORKERS, capped);
    }

    public static boolean isAuto(String value) {
        return value != null && AUTO_VALUE.equalsIgnoreCase(value.trim());
    }

    /** Resolve {@code auto} or return the explicit positive integer. */
    public static int resolve(String configured) {
        if (configured == null || configured.isBlank() || isAuto(configured)) {
            return estimate();
        }
        try {
            int parsed = Integer.parseInt(configured.trim());
            if (parsed < 1) {
                throw new IllegalArgumentException(
                        "pkb_parallel must be a positive integer or 'auto', but was: " + configured);
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "pkb_parallel must be a positive integer or 'auto', but was: " + configured,
                    exception);
        }
    }

    public static String recommendedDiscoverRunVars() {
        return recommendedDiscoverRunVars(Path.of("").toAbsolutePath().normalize());
    }

    /** Browser comes from {@link AgentBrowserLadder}, not a hardcoded headless Chrome. */
    public static String recommendedDiscoverRunVars(Path projectRoot) {
        return recommendedDiscoverRunVars(AgentBrowserLadder.select(projectRoot).browser());
    }

    public static String recommendedDiscoverRunVars(String browser) {
        String selected = browser == null || browser.isBlank() ? AgentBrowserLadder.CHROME_HEADLESS : browser.trim();
        return "pkb_browser=" + selected + ", pkb_parallel=" + estimate()
                + ", pkb_reportingmode=diagnostic, pkb_loglevel=warn, pkb_reportretention=failed";
    }
}
