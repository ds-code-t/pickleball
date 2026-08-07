package tools.dscode.common.dataelements;

public record DataCoordinate(int row, int column) {
    public DataCoordinate {
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException(
                    "Data coordinates cannot be negative: row=" + row
                            + ", column=" + column
            );
        }
    }
}
