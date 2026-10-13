package tools.dscode;

/**
 * Value returned by a custom reference prefix.
 * {@link #toString()} is the value, unless {@link #secret()} is true.
 * A secret prints as {@code ******}.
 */
public record PrefixValue(Object value, boolean secret) {
    public static final String MASK = "******";

    public static PrefixValue of(Object value) {
        return new PrefixValue(value, false);
    }

    public static PrefixValue secret(Object value) {
        return new PrefixValue(value, true);
    }

    @Override
    public String toString() {
        if (secret) {
            return MASK;
        }
        return value == null ? "null" : String.valueOf(value);
    }
}
