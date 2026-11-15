package tools.dscode.dolt.store;

import tools.dscode.dolt.server.DoltServer;

import java.nio.file.Files;
import java.nio.file.Path;

final class DoltStoreFixture {
    private DoltStoreFixture() {
    }

    static void preferBinary() {
        Path cached = Path.of(System.getProperty("user.home"), ".pickleball", "tools", "dolt", "2.7.3",
                "linux-amd64", "dolt");
        if (Files.isRegularFile(cached)) System.setProperty("pkb_dolt_bin", cached.toString());
    }

    static void stop(Path project) throws Exception {
        DoltConnections.closeAll();
        if (project != null) DoltServer.stop(project);
        System.clearProperty("pkb_dolt_bin");
        System.clearProperty("pkb_dolt_log");
        DoltDialect.forcedConflictRemaining = 0;
    }
}
