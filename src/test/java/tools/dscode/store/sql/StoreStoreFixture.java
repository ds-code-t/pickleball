package tools.dscode.store.sql;

import java.nio.file.Path;

final class StoreStoreFixture {
    private StoreStoreFixture() {
    }

    static void preferBinary() {
        // The store opens SQLite. A missing binary is not consulted.
    }

    static void stop(Path project) throws Exception {
        SqlConnections.closeAll();
        if (project != null) tools.dscode.store.sql.StoreFiles.releaseProject(project, StoreFiles.RELEASE_MS);
        System.clearProperty("pkb_store_log");
        SqlDialect.forcedConflictRemaining = 0;
    }
}