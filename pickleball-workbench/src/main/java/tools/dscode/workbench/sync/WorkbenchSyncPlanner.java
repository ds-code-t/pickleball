package tools.dscode.workbench.sync;

/**
 * Chooses skip / resources-only / full compile from input fingerprints.
 *
 * <p>{@link WorkbenchManifest#fingerprint()} is output provenance and is never
 * used as the skip key.</p>
 */
public final class WorkbenchSyncPlanner {
    private WorkbenchSyncPlanner() {
    }

    public static WorkbenchSyncMode decide(
            WorkbenchManifest previous,
            WorkbenchSyncInputs current,
            boolean snapshotReady
    ) {
        if (previous == null || current == null || !snapshotReady) {
            return WorkbenchSyncMode.FULL;
        }
        if (!previous.hasInputFingerprints()) {
            return WorkbenchSyncMode.FULL;
        }
        if (!current.javaFingerprint().equals(previous.javaInputFingerprint())
                || !current.buildFingerprint().equals(previous.buildInputFingerprint())
                || !current.dependencyFingerprint().equals(previous.dependencyInputFingerprint())) {
            return WorkbenchSyncMode.FULL;
        }
        if (!current.resourceFingerprint().equals(previous.resourceInputFingerprint())) {
            return WorkbenchSyncMode.RESOURCES_ONLY;
        }
        return WorkbenchSyncMode.SKIPPED;
    }
}
