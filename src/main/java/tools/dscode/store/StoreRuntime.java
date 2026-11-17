package tools.dscode.store;

/**
 * Auto-hydrate hook used by feature loading. {@code FeaturePathLoadCache}
 * calls this from the CLI feature supplier and from the JUnit feature parser.
 * When Store is off this returns without opening a database, so the file-only
 * path stays the file-only path.
 */
public final class StoreRuntime {
    private static volatile boolean attempted;

    private StoreRuntime() {
    }

    public static synchronized void beforeFeatureLoad() {
        if (!StoreConfig.enabled()) return;
        if (!StoreDatabase.driverPresent()) {
            throw new IllegalStateException(StoreDatabase.MISSING_DRIVER);
        }
        if (attempted) return;
        try {
            StoreHydrator.Result result = StoreHydrator.hydrate(StoreConfig.project(), StoreHydrator.Options.full());
            attempted = true;
            StoreLog.log(StoreConfig.project(), "hydrate", "files", "auto " + result.line());
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            String error = "error: Store auto-hydrate failed: " + message;
            String fix = "fix: store hydrate";
            System.err.println(error);
            System.err.println(fix);
            throw new IllegalStateException(error + "\n" + fix, failure);
        }
    }

    /** Tests only. */
    public static synchronized void reset() {
        attempted = false;
    }
}
