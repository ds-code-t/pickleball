package tools.dscode.store.sql;

import tools.dscode.store.StorePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Read-only opening of the existing store files. No host, no second database,
 * and no file is created when one is missing.
 */
public final class DashboardAccess {
    private DashboardAccess() {
    }

    public record Open(Connection connection, String runs, boolean entities) implements AutoCloseable {
        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    /** Empty when neither database file exists. */
    public static Open open(Path project) throws SQLException {
        if (project == null) return null;
        Path root = project.toAbsolutePath().normalize();
        Path main = StorePaths.mainDatabase(root);
        Path runs = StorePaths.runState(root);
        boolean hasMain = Files.isRegularFile(main);
        boolean hasRuns = Files.isRegularFile(runs);
        if (!hasMain && !hasRuns) return null;
        Path primary = hasMain ? main : runs;
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + primary.toAbsolutePath());
        String qualifier = "";
        if (hasMain && hasRuns) {
            try (Statement statement = connection.createStatement()) {
                String path = runs.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
                statement.execute("ATTACH DATABASE '" + path + "' AS rs");
            }
            qualifier = "rs.";
        }
        return new Open(connection, qualifier, hasMain);
    }
}
