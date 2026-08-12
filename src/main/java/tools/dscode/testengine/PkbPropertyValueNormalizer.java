package tools.dscode.testengine;

final class PkbPropertyValueNormalizer {
    private PkbPropertyValueNormalizer() {
    }

    static String normalizeSystemProperty(String key, String value) {
        if (value == null || !isPkbKey(key)) {
            return value;
        }

        String trimmed = value.trim();
        if (trimmed.length() < 2) {
            return value;
        }

        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return value;
    }

    private static boolean isPkbKey(String key) {
        return key != null
                && key.regionMatches(true, 0, PKB_props.PKB_PREFIX, 0, PKB_props.PKB_PREFIX.length());
    }
}
