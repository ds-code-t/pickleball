package tools.dscode.engine.model;

/**
 * Where a feature, scenario, or step was read from. {@code uri} may be a
 * file URI, {@code dalton:} row, {@code memory:} text, or empty when the
 * text was written at runtime and has no address.
 */
public record SourceLocation(String uri, int line, int column) {
    public SourceLocation {
        uri = uri == null ? "" : uri;
        if (line < 0) line = 0;
        if (column < 0) column = 0;
    }

    public static SourceLocation unknown() {
        return new SourceLocation("", 0, 0);
    }

    public static SourceLocation of(String uri, long line, Long column) {
        int col = column == null ? 0 : (int) column.longValue();
        return new SourceLocation(uri, (int) line, col);
    }
}
