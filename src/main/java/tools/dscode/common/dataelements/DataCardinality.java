package tools.dscode.common.dataelements;

public enum DataCardinality {
    REQUIRED_ONE(true, false),
    REQUIRED_MANY(true, true),
    OPTIONAL_MANY(false, true);

    private final boolean required;
    private final boolean many;

    DataCardinality(boolean required, boolean many) {
        this.required = required;
        this.many = many;
    }

    public boolean required() {
        return required;
    }

    public boolean many() {
        return many;
    }
}
