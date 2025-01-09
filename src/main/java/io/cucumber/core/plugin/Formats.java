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

package io.cucumber.core.plugin;

import java.util.HashMap;
import java.util.Map;

import static io.cucumber.core.plugin.Format.color;

interface Formats {

    Format get(String key);

    String up(int n);

    static Formats monochrome() {
        return new Monochrome();
    }

    static Formats ansi() {
        return new Ansi();
    }

    final class Monochrome implements Formats {

        private Monochrome() {

        }

        public Format get(String key) {
            return text -> text;
        }

        public String up(int n) {
            return "";
        }

    }

    final class Ansi implements Formats {

        private Ansi() {

        }

        private static final Map<String, Format> formats = new HashMap<String, Format>() {
            {
                // Never used, but avoids NPE in formatters.
                put("undefined", color(AnsiEscapes.YELLOW));
                put("undefined_arg", color(AnsiEscapes.YELLOW, AnsiEscapes.INTENSITY_BOLD));
                put("unused", color(AnsiEscapes.YELLOW));
                put("unused_arg", color(AnsiEscapes.YELLOW, AnsiEscapes.INTENSITY_BOLD));
                put("pending", color(AnsiEscapes.YELLOW));
                put("pending_arg", color(AnsiEscapes.YELLOW, AnsiEscapes.INTENSITY_BOLD));
                put("executing", color(AnsiEscapes.GREY));
                put("executing_arg", color(AnsiEscapes.GREY, AnsiEscapes.INTENSITY_BOLD));
                put("failed", color(AnsiEscapes.RED));
                put("failed_arg", color(AnsiEscapes.RED, AnsiEscapes.INTENSITY_BOLD));
                put("soft_failed", color(AnsiEscapes.RED));
                put("soft_failed_arg", color(AnsiEscapes.RED, AnsiEscapes.INTENSITY_BOLD));
                put("ambiguous", color(AnsiEscapes.RED));
                put("ambiguous_arg", color(AnsiEscapes.RED, AnsiEscapes.INTENSITY_BOLD));
                put("passed", color(AnsiEscapes.GREEN));
                put("passed_arg", color(AnsiEscapes.GREEN, AnsiEscapes.INTENSITY_BOLD));
                put("outline", color(AnsiEscapes.CYAN));
                put("outline_arg", color(AnsiEscapes.CYAN, AnsiEscapes.INTENSITY_BOLD));
                put("skipped", color(AnsiEscapes.CYAN));
                put("skipped_arg", color(AnsiEscapes.CYAN, AnsiEscapes.INTENSITY_BOLD));
                put("comment", color(AnsiEscapes.GREY));
                put("tag", color(AnsiEscapes.CYAN));
                put("output", color(AnsiEscapes.BLUE));
            }
        };

        public Format get(String key) {
            Format format = formats.get(key);
            if (format == null)
                throw new NullPointerException("No format for key " + key);
            return format;
        }

        public String up(int n) {
            return AnsiEscapes.up(n).toString();
        }

    }

}
