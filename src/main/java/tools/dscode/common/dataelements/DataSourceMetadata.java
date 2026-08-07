package tools.dscode.common.dataelements;

public record DataSourceMetadata(
        String sourceType,
        String description
) {
    public static DataSourceMetadata of(Object source) {
        if (source == null) {
            return new DataSourceMetadata("null", "null source");
        }

        String type = source.getClass().getName();
        return new DataSourceMetadata(type, source.getClass().getSimpleName());
    }
}
