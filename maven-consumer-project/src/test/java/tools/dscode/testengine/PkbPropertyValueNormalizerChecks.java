package tools.dscode.testengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class PkbPropertyValueNormalizerChecks {
    @Test
    void stripsOnePairOfDoubleQuotesFromPkbValues() {
        assertEquals(
                "INFO",
                PkbPropertyValueNormalizer.normalizeSystemProperty("pkb_loglevel", "\"INFO\"")
        );
    }

    @Test
    void stripsOnePairOfSingleQuotesFromPkbValues() {
        assertEquals(
                "all",
                PkbPropertyValueNormalizer.normalizeSystemProperty("PKB_REPORTRETENTION", "'all'")
        );
    }

    @Test
    void preservesEmbeddedQuotes() {
        assertEquals(
                "name=\"A B\"",
                PkbPropertyValueNormalizer.normalizeSystemProperty("pkb_options", "'name=\"A B\"'")
        );
    }

    @Test
    void leavesNonPkbPropertiesUntouched() {
        assertEquals(
                "\"INFO\"",
                PkbPropertyValueNormalizer.normalizeSystemProperty("other_loglevel", "\"INFO\"")
        );
    }
}
