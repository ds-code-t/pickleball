package tools.dscode.dolt.store;

import java.nio.file.Path;

/** Writable SQLite URL for migration fixtures. Production migration stays read-only. */
public final class LegacySqliteFixture {
    private LegacySqliteFixture() {
    }

    public static String jdbcUrl(Path database) {
        String path = database.toAbsolutePath().normalize().toString().replace('\\', '/');
        return "jdbc:sqlite:" + path;
    }
}
