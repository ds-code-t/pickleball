package tools.dscode.store;

import java.nio.file.Path;
import java.sql.SQLException;

/**
 * Auto-hydrate hook used by feature loading. {@code FeaturePathLoadCache}
 * calls this from the CLI feature supplier and from the JUnit feature parser.
 * When Store is off this returns without opening a database, so the file-only
 * path stays the file-only path. A copy-on-write sandbox is not hydrated here:
 * the child has no jar hash and does not hold parent file bytes, so a full
 * hydrate would delete the child's edits and parse the on-disk file.
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
        Path project = StoreConfig.project();
        try {
            if (cowChild(project)) {
                attempted = true;
                StoreLog.log(project, "hydrate", "files",
                        "auto skipped cow sandbox " + StoreScope.label(StoreScope.current()));
                return;
            }
            StoreHydrator.Result result = StoreHydrator.hydrate(project, StoreHydrator.Options.full());
            attempted = true;
            StoreLog.log(project, "hydrate", "files", "auto " + result.line());
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
            String error = "error: Store auto-hydrate failed: " + message;
            String fix = "fix: store hydrate";
            System.err.println(error);
            System.err.println(fix);
            throw new IllegalStateException(error + "\n" + fix, failure);
        }
    }

    /**
     * A cow child keeps parent bytes on the parent. Auto-hydrate would treat the
     * missing jar hash as a jar change, wipe the child's feature put, and copy
     * the working tree in. An explicit {@code store hydrate} is unchanged.
     */
    private static boolean cowChild(Path project) {
        if (StoreScope.current().primary()) return false;
        if (!StorePaths.ready(project)) return false;
        try (StoreDatabase database = StoreDatabase.open(project)) {
            return CowSandbox.isCow(database.connection());
        } catch (SQLException failure) {
            return false;
        }
    }

    /** Tests only. */
    public static synchronized void reset() {
        attempted = false;
    }
}
