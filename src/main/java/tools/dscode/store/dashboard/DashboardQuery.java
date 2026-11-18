package tools.dscode.store.dashboard;

/** Filters applied by {@link DashboardSource}. Empty means no filter. */
public record DashboardQuery(
        String from,
        String to,
        String status,
        String feature,
        String runId,
        String entity,
        String text
) {
    public DashboardQuery {
        from = blank(from);
        to = blank(to);
        status = blank(status);
        feature = blank(feature);
        runId = blank(runId);
        entity = blank(entity);
        text = blank(text);
    }

    public static DashboardQuery all() {
        return new DashboardQuery("", "", "", "", "", "", "");
    }

    private static String blank(String value) {
        return value == null ? "" : value.trim();
    }
}
