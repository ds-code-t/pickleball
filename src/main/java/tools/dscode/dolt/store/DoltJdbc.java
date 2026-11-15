package tools.dscode.dolt.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** MariaDB Connector/J access to the loopback Dolt sql-server. The only JDBC URL in the core. */
public final class DoltJdbc {
    public static final String DRIVER = "org.mariadb.jdbc.Driver";
    public static final String MISSING_DRIVER =
            "Dolt is enabled but org.mariadb.jdbc:mariadb-java-client is not on the classpath. "
                    + "Add it as testRuntimeOnly / test scope or use the Gradle dolt* tasks. "
                    + "Dolt is on by default; add org.mariadb.jdbc:mariadb-java-client or set pkb_dolt=false to run file-only.";

    private DoltJdbc() {
    }

    public static boolean driverPresent() {
        try {
            Class.forName(DRIVER);
            return true;
        } catch (ClassNotFoundException failure) {
            return false;
        }
    }

    public static String url(int port, String database, int timeoutMs) {
        String path = database == null || database.isBlank() ? "/" : "/" + database;
        int connect = Math.max(1, timeoutMs);
        int socket = Math.max(connect, 15_000);
        return "jdbc:mariadb://127.0.0.1:" + port + path + "?user=root&connectTimeout=" + connect
                + "&socketTimeout=" + socket;
    }

    public static Connection open(int port, String database, int timeoutMs) throws SQLException {
        if (!driverPresent()) throw new SQLException(MISSING_DRIVER);
        DriverManager.setLoginTimeout(Math.max(1, timeoutMs / 1000));
        return DriverManager.getConnection(url(port, database, timeoutMs));
    }

    public static boolean selectOne(int port, int timeoutMs) throws SQLException {
        try (Connection connection = open(port, "", timeoutMs);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT 1")) {
            return rows.next() && rows.getInt(1) == 1;
        }
    }
}
