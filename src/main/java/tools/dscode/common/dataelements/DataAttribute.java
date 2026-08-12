package tools.dscode.common.dataelements;

import java.util.Locale;
import java.util.Optional;

public enum DataAttribute {
    VALUE,
    STRING,
    KEY,
    VALUES,
    SIZE,
    COUNT,
    FIRST,
    LAST,
    TYPE;

    public static Optional<DataAttribute> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }

        String normalized = name.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);

        try {
            return Optional.of(valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
