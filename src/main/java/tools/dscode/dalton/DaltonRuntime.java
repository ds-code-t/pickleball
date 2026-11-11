package tools.dscode.dalton;

/**
 * Auto-hydrate hook used by feature loading. {@code FeaturePathLoadCache}
 * calls this from the CLI feature supplier and from the JUnit feature parser.
 * When Dalton is off this returns without opening a database, so the file-only
 * path stays the file-only path.
 */
public final class DaltonRuntime {
    private static volatile boolean attempted;

    private DaltonRuntime() {
    }

    public static synchronized void beforeFeatureLoad() {
        if (!DaltonConfig.enabled()) return;
        if (attempted) return;
        attempted = true;
        try {
            DaltonHydrator.Result result = DaltonHydrator.hydrate(DaltonConfig.project(), DaltonHydrator.Options.full());
            DaltonLog.log(DaltonConfig.project(), "hydrate", "files", "auto " + result.line());
        } catch (Exception failure) {
            System.err.println("Dalton auto-hydrate skipped: " + failure.getMessage());
        }
    }

    /** Tests only. */
    public static synchronized void reset() {
        attempted = false;
    }
}
