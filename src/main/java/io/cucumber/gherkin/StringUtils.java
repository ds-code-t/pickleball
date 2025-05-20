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

package io.cucumber.gherkin;

import java.util.regex.Pattern;

import static io.pickleball.stringutilities.Constants.sFlag2;

public class StringUtils {

    public static final Pattern LTRIM = Pattern.compile("^[ \\t\\n\\x0B\\f\\r\\x85\\xA0]+");
    public static final Pattern LTRIM_KEEP_NEW_LINES = Pattern.compile("^[ \\t\\x0B\\f\\r\\x85\\xA0]+");
    public static final Pattern RTRIM_KEEP_NEW_LINES = Pattern.compile("[ \\t\\x0B\\f\\r\\x85\\xA0]+$");
    public static final Pattern RTRIM = Pattern.compile("[ \\t\\n\\x0B\\f\\r\\x85\\xA0]+$");
    public static final Pattern TRIM = Pattern.compile("^[ \\t\\n\\x0B\\f\\r\\x85\\xA0]+|[ \\t\\n\\x0B\\f\\r\\x85\\xA0]+$");

    static String ltrim(String s) {
        // https://stackoverflow.com/questions/1060570/why-is-non-breaking-space-not-a-whitespace-character-in-java
        return LTRIM.matcher(s).replaceAll("");
    }

    /**
     * Trims whitespace on the left-hand side up to the first 
     * non-whitespace character and exclude new lines from the 
     * usual definition of whitespace.
     */
    static String ltrimKeepNewLines(String s) {
        // https://stackoverflow.com/questions/1060570/why-is-non-breaking-space-not-a-whitespace-character-in-java
        return LTRIM_KEEP_NEW_LINES.matcher(s).replaceAll("");
    }

    /**
     * Trims whitespace on the right-hand side up to the first 
     * non-whitespace character and exclude new lines from the 
     * usual definition of whitespace.
     */
    static String rtrimKeepNewLines(String s) {
        // https://stackoverflow.com/questions/1060570/why-is-non-breaking-space-not-a-whitespace-character-in-java
        return RTRIM_KEEP_NEW_LINES.matcher(s).replaceAll("").replaceAll(sFlag2,"");
    }

    static String rtrim(String s) {
        return RTRIM.matcher(s).replaceAll("").replaceAll(sFlag2,"");
    }

    static String trim(String s) {
        return TRIM.matcher(s).replaceAll("").replace(sFlag2, "");
    }

    static int symbolCount(String string) {
        // http://rosettacode.org/wiki/String_length#Java
        return string.codePointCount(0, string.length());
    }
}
