package tools.dscode.timing;

import tools.dscode.control.protocol.WorkbenchFeatureIndex;

import java.nio.file.Path;

/**
 * Prints a whole-project {@link WorkbenchFeatureIndex} scan.
 * Cold ignores the cache. Warm reads the cache just written.
 * No tags, so every indexed scenario is a hit. Cucumber is not started.
 */
public final class FeatureScanTiming {
    private FeatureScanTiming() {
    }

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("Usage: FeatureScanTiming [project]");
            System.exit(2);
        }
        Path project = Path.of(args.length == 0 ? "maven-consumer-project" : args[0]).toAbsolutePath().normalize();
        WorkbenchFeatureIndex.Selection cold = WorkbenchFeatureIndex.select(request(project, true));
        WorkbenchFeatureIndex.Selection warm = WorkbenchFeatureIndex.select(request(project, false));
        if (cold.fullScanFallback() || warm.fullScanFallback()) {
            System.err.println("full-scan fallback: " + cold.fallbackReason() + " / " + warm.fallbackReason());
            System.exit(1);
        }
        if (cold.filesIndexed() != warm.filesIndexed() || cold.hits().size() != warm.hits().size()) {
            System.err.println("cold and warm disagree: files " + cold.filesIndexed() + " vs " + warm.filesIndexed()
                    + ", scenarios " + cold.hits().size() + " vs " + warm.hits().size());
            System.exit(1);
        }
        System.out.println("project=" + project);
        System.out.println("files=" + cold.filesIndexed());
        System.out.println("hits=" + cold.hits().size());
        System.out.println("coldMs=" + cold.elapsedMillis());
        System.out.println("warmMs=" + warm.elapsedMillis());
        System.out.println("coldReparsed=" + cold.filesReparsed());
        System.out.println("warmCacheHits=" + warm.cacheHits());
        System.out.println("implementation=" + WorkbenchFeatureIndex.IMPLEMENTATION);
    }

    private static WorkbenchFeatureIndex.Request request(Path project, boolean refresh) {
        return new WorkbenchFeatureIndex.Request(project, null, null, null, null, null, refresh);
    }
}
