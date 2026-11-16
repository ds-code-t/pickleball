package tools.dscode.dolt.store;

import java.nio.file.Path;

final class DoltStoreFixture {
    private DoltStoreFixture() {
    }

    static void preferBinary() {
        // The store opens SQLite. A missing binary is not consulted.
    }

    static void stop(Path project) throws Exception {
        DoltConnections.closeAll();
        if (project != null) tools.dscode.dolt.store.StoreFiles.releaseProject(project, StoreFiles.RELEASE_MS);
        System.clearProperty("pkb_dolt_log");
        DoltDialect.forcedConflictRemaining = 0;
    }
}