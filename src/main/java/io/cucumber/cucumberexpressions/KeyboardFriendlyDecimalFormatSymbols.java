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

import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * A set of localized decimal symbols that can be written on a regular keyboard.
 * <p>
 * Note quite complete, feel free to make a suggestion.
 */
class KeyboardFriendlyDecimalFormatSymbols {

    static DecimalFormatSymbols getInstance(Locale locale) {
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(locale);

        // Replace the minus sign with minus-hyphen as available on most keyboards.
        if (symbols.getMinusSign() == '\u2212') {
            symbols.setMinusSign('-');
        }

        if (symbols.getDecimalSeparator() == '.') {
            // For locales that use the period as the decimal separator
            // always use the comma for thousands. The alternatives are
            // not available on a keyboard
            symbols.setGroupingSeparator(',');
        } else if (symbols.getDecimalSeparator() == ',') {
            // For locales that use the comma as the decimal separator
            // always use the period for thousands. The alternatives are
            // not available on a keyboard
            symbols.setGroupingSeparator('.');
        }
        return symbols;
    }
}
