package tools.dscode.common.dataelements;

public record TabularCell(Object value, boolean missing) {
    private static final TabularCell MISSING = new TabularCell(null, true);

    public static TabularCell of(Object value) {
        return new TabularCell(value, false);
    }

    public static TabularCell missingCell() {
        return MISSING;
    }

    public Object externalValue() {
        return value;
    }

    public boolean explicitNull() {
        return !missing && value == null;
    }
}
