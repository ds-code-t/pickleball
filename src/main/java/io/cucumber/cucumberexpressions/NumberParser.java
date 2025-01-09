/*
 * This file incorporates work covered by the following copyright and permission notice:
 *
 * Copyright (c) Cucumber Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.cucumber.cucumberexpressions;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.text.ParseException;
import java.util.Locale;

final class NumberParser {
    private final NumberFormat numberFormat;

    NumberParser(Locale locale) {
        numberFormat = DecimalFormat.getNumberInstance(locale);
        if (numberFormat instanceof DecimalFormat) {
            DecimalFormat decimalFormat = (DecimalFormat) numberFormat;
            decimalFormat.setParseBigDecimal(true);
            DecimalFormatSymbols symbols = KeyboardFriendlyDecimalFormatSymbols.getInstance(locale);
            decimalFormat.setDecimalFormatSymbols(symbols);
        }
    }

    double parseDouble(String s) {
        return parse(s).doubleValue();
    }

    float parseFloat(String s) {
        return parse(s).floatValue();
    }

    BigDecimal parseBigDecimal(String s) {
        if (numberFormat instanceof DecimalFormat) {
            return (BigDecimal) parse(s);
        }
        // Fall back to default big decimal format
        // if the locale does not have a DecimalFormat
        return new BigDecimal(s);
    }

    private Number parse(String s) {
        try {
            return numberFormat.parse(s);
        } catch (ParseException e) {
            throw new CucumberExpressionException("Failed to parse number", e);
        }
    }
}
