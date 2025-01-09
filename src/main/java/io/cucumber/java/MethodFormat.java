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

package io.cucumber.java;

import io.cucumber.core.backend.CucumberBackendException;

import java.lang.reflect.Method;
import java.text.MessageFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper class for formatting a method signature to a shorter form.
 */
final class MethodFormat {

    static final MethodFormat FULL = new MethodFormat("%qc.%m(%qa)");
    private static final Pattern METHOD_PATTERN = Pattern
            .compile("((?:static\\s|public\\s)+)([^\\s]*)\\s\\.?(.*)\\.([^\\(]*)\\(([^\\)]*)\\)(?: throws )?(.*)");
    private final MessageFormat format;

    /**
     * @param format the format string to use. There are several pattern tokens
     *               that can be used:
     *               <ul>
     *               <li><strong>%M</strong>: Modifiers</li>
     *               <li><strong>%qr</strong>: Qualified return type</li>
     *               <li><strong>%r</strong>: Unqualified return type</li>
     *               <li><strong>%qc</strong>: Qualified class</li>
     *               <li><strong>%c</strong>: Unqualified class</li>
     *               <li><strong>%m</strong>: Method name</li>
     *               <li><strong>%qa</strong>: Qualified arguments</li>
     *               <li><strong>%a</strong>: Unqualified arguments</li>
     *               <li><strong>%qe</strong>: Qualified exceptions</li>
     *               <li><strong>%e</strong>: Unqualified exceptions</li>
     *               <li><strong>%s</strong>: Code source</li>
     *               </ul>
     */
    private MethodFormat(String format) {
        String pattern = format
                .replaceAll("%qc", "{0}")
                .replaceAll("%m", "{1}")
                .replaceAll("%qa", "{2}");
        this.format = new MessageFormat(pattern);
    }

    String format(Method method) {
        String signature = method.toGenericString();
        Matcher matcher = METHOD_PATTERN.matcher(signature);
        if (matcher.find()) {
            String qc = matcher.group(3);
            String m = matcher.group(4);
            String qa = matcher.group(5);

            return format.format(new Object[] {
                    qc,
                    m,
                    qa,
            });
        } else {
            throw new CucumberBackendException("Cucumber bug: Couldn't format " + signature);
        }
    }

}
