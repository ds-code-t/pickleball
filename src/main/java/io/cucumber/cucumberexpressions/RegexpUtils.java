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

class RegexpUtils {
    /**
     * List of characters to be escaped.
     * The last char is '}' with index 125, so we need only 126 characters.
     */
    private static final boolean[] CHAR_TO_ESCAPE = new boolean[126];

    static {
        CHAR_TO_ESCAPE['^'] = true;
        CHAR_TO_ESCAPE['$'] = true;
        CHAR_TO_ESCAPE['['] = true;
        CHAR_TO_ESCAPE[']'] = true;
        CHAR_TO_ESCAPE['('] = true;
        CHAR_TO_ESCAPE[')'] = true;
        CHAR_TO_ESCAPE['{'] = true;
        CHAR_TO_ESCAPE['}'] = true;
        CHAR_TO_ESCAPE['.'] = true;
        CHAR_TO_ESCAPE['|'] = true;
        CHAR_TO_ESCAPE['?'] = true;
        CHAR_TO_ESCAPE['*'] = true;
        CHAR_TO_ESCAPE['+'] = true;
        CHAR_TO_ESCAPE['\\'] = true;
    }

    /**
     * Escapes the regexp characters (the ones from "^$(){}[].+*?\")
     * from the given text, so that they are not considered as regexp
     * characters.
     *
     * @param text the non-null input text
     * @return the input text with escaped regexp characters
     */
    public static String escapeRegex(String text) {
        /*
        Note on performance: this code has been benchmarked for
        escaping frequencies of 100%, 50%, 20%, 10%, 1%, 0.1%.
        Amongst 4 other variants (including Pattern matching),
        this variant is the faster on all escaping frequencies.
        */
        int length = text.length();
        StringBuilder sb = null; // lazy initialization
        int blockStart = 0;
        int maxChar = CHAR_TO_ESCAPE.length;
        for (int i = 0; i < length; i++) {
            char currentChar = text.charAt(i);
            if (currentChar < maxChar && CHAR_TO_ESCAPE[currentChar]) {
                if (sb == null) {
                    sb = new StringBuilder(length * 2);
                }
                if (blockStart < i) {
                    // flush previous block
                    sb.append(text, blockStart, i);
                }
                sb.append('\\');
                sb.append(currentChar);
                blockStart = i + 1;
            }
        }
        if (sb != null) {
            // finalizing character escaping
            if (blockStart < length) {
                // flush remaining characters
                sb.append(text, blockStart, length);
            }
            return sb.toString();
        }
        return text;
    }

}
