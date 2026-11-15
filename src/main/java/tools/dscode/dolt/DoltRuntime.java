package tools.dscode.dolt;

/**
 * Auto-hydrate hook used by feature loading. {@code FeaturePathLoadCache}
 * calls this from the CLI feature supplier and from the JUnit feature parser.
 * When Dolt is off this returns without opening a database, so the file-only
 * path stays the file-only path.
 */
public final class DoltRuntime {
    private static volatile boolean attempted;

    private DoltRuntime() {
    }

    public static synchronized void beforeFeatureLoad() {
        if (!DoltConfig.enabled()) return;
        if (!DoltDatabase.driverPresent()) {
            throw new IllegalStateException(DoltDatabase.MISSING_DRIVER);
        }
        if (attempted) return;
        try {
            DoltHydrator.Result result = DoltHydrator.hydrate(DoltConfig.project(), DoltHydrator.Options.full());
            attempted = true;
            DoltLog.log(DoltConfig.project(), "hydrate", "files", "auto " + result.line());
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            String error = "error: Dolt auto-hydrate failed: " + message;
            String fix = "fix: dolt hydrate";
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
