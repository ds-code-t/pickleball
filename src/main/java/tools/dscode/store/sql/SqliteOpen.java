package tools.dscode.store.sql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/** Opens one SQLite file. JDBC URLs stay in this package. The driver is runtime-only. */
public final class SqliteOpen {
    public static final String DRIVER = "org.sqlite.JDBC";
    public static final String MISSING_DRIVER =
            "Store is enabled but org.xerial:sqlite-jdbc is not on the classpath. "
                    + "Store is on by default; add org.xerial:sqlite-jdbc or set pkb_store=false to run file-only.";

    private SqliteOpen() {
    }

    public static boolean driverPresent() {
        try {
            Class.forName(DRIVER);
            return true;
        } catch (ClassNotFoundException failure) {
            return false;
        }
    }

    public static Connection open(Path file) throws SQLException {
        if (!driverPresent()) throw new SQLException(MISSING_DRIVER);
        try {
            Files.createDirectories(file.getParent());
        } catch (java.io.IOException failure) {
            throw new SQLException(failure.getMessage() == null ? "could not create the store directory" : failure.getMessage(), failure);
        }
        Properties properties = new Properties();
        properties.setProperty("journal_mode", "WAL");
        properties.setProperty("busy_timeout", "5000");
        properties.setProperty("foreign_keys", "true");
        properties.setProperty("synchronous", "NORMAL");
        properties.setProperty("transaction_mode", "IMMEDIATE");
        return DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath(), properties);
    }
}
