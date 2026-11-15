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
        if (!DaltonDatabase.driverPresent()) {
            throw new IllegalStateException(DaltonDatabase.MISSING_DRIVER);
        }
        if (attempted) return;
        try {
            DaltonHydrator.Result result = DaltonHydrator.hydrate(DaltonConfig.project(), DaltonHydrator.Options.full());
            attempted = true;
            DaltonLog.log(DaltonConfig.project(), "hydrate", "files", "auto " + result.line());
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            String error = "error: Dalton auto-hydrate failed: " + message;
            String fix = "fix: dalton hydrate";
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
