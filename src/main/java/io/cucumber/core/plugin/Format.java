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

interface Format {

    String text(String text);

    static Format color(AnsiEscapes... escapes) {
        return new Color(escapes);
    }

    static Format monochrome() {
        return new Monochrome();
    }

    final class Color implements Format {

        private final AnsiEscapes[] escapes;

        private Color(AnsiEscapes... escapes) {
            this.escapes = escapes;
        }

        public String text(String text) {
            StringBuilder sb = new StringBuilder();
            for (AnsiEscapes escape : escapes) {
                escape.appendTo(sb);
            }
            sb.append(text);
            if (escapes.length > 0) {
                AnsiEscapes.RESET.appendTo(sb);
            }
            return sb.toString();
        }

    }

    class Monochrome implements Format {

        private Monochrome() {

        }

        @Override
        public String text(String text) {
            return text;
        }

    }

}
