package tools.dscode.dolt.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Read-only SQLite open for the legacy working-copy file. The Dolt core does not use it. */
public final class LegacySqlite {
    private LegacySqlite() {
    }

    public static Connection openReadOnly(Path database) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException failure) {
            throw new SQLException("sqlite-jdbc is not on the classpath", failure);
        }
        String path = database.toAbsolutePath().normalize().toString().replace('\\', '/');
        return DriverManager.getConnection("jdbc:sqlite:file:" + path + "?mode=ro");
    }
}
