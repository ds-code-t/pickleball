package tools.dscode.store.dashboard;

import java.sql.SQLException;

/**
 * Read-only view of run history. A later central backend can implement this
 * the same way. There is no host and no second database.
 */
public interface DashboardSource {
    DashboardSnapshot read(DashboardQuery query) throws SQLException;
}
