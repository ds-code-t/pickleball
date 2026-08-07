package tools.dscode.common.dataelements;

public record DataEntryValue(
        Object key,
        Object value,
        DataCoordinate coordinate,
        boolean missing
) {
}
