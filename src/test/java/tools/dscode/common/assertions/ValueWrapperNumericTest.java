package tools.dscode.common.assertions;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tools.dscode.common.assertions.ValueWrapper.createValueWrapper;

class ValueWrapperNumericTest {

    @Test
    void qualifiedNumericAcceptsDigitsGroupingCurrencyAndExtraDots() {
        assertTrue(quoted("1").isQualifiedNumeric(false));
        assertTrue(quoted("1.00").isQualifiedNumeric(false));
        assertTrue(quoted("$1.00").isQualifiedNumeric(false));
        assertTrue(quoted("€1").isQualifiedNumeric(false));
        assertTrue(quoted("£1_000").isQualifiedNumeric(false));
        assertTrue(quoted("1,000").isQualifiedNumeric(false));
        assertTrue(quoted(" 1.2.3 ").isQualifiedNumeric(false));
        assertTrue(quoted("+1").isQualifiedNumeric(false));
        assertTrue(quoted("1.00").isQualifiedNumeric(true));
        assertTrue(quoted("1,000").isQualifiedNumeric(true));
        assertFalse(quoted("1.5").isQualifiedNumeric(true));
        assertFalse(quoted("1.50").isQualifiedNumeric(true));
    }

    @Test
    void qualifiedNumericRejectsBlankLettersAndOtherSymbols() {
        assertFalse(quoted("").isQualifiedNumeric(false));
        assertFalse(quoted("   ").isQualifiedNumeric(false));
        assertFalse(quoted("abc").isQualifiedNumeric(false));
        assertFalse(quoted("12abc").isQualifiedNumeric(false));
        assertFalse(quoted("12%").isQualifiedNumeric(false));
        assertFalse(quoted("1-2").isQualifiedNumeric(false));
        assertFalse(quoted("abc").isQualifiedNumeric(true));
        assertFalse(createValueWrapper("").isQualifiedNumeric(false));
    }

    @Test
    void numericEqualsHonoursScaleCurrencyAndMargin() {
        assertTrue(ValueWrapperComparisons.numericEquals(quoted("1"), quoted("1.00")));
        assertTrue(ValueWrapperComparisons.numericEquals(quoted("1"), quoted("$1.00")));
        assertTrue(ValueWrapperComparisons.numericEquals(quoted("1.5"), quoted("1.50")));
        assertTrue(ValueWrapperComparisons.numericEquals(quoted("1.2.3"), quoted("12.3")));
        assertFalse(ValueWrapperComparisons.equals(quoted("1"), quoted("1.00")));

        ValueWrapper margin = createValueWrapper("0.1");
        assertTrue(ValueWrapperComparisons.numericEquals(quoted("1.00"), quoted("1.05"), margin));
        assertFalse(ValueWrapperComparisons.numericEquals(quoted("1.00"), quoted("1.20"), margin));
        assertEquals(0, quoted("$1.00").asBigDecimal().compareTo(new BigDecimal("1.00")));
    }

    private static ValueWrapper quoted(String text) {
        return createValueWrapper("\"" + text + "\"");
    }
}
