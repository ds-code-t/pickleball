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

final class AnsiEscapes {
    static final AnsiEscapes RESET = color(0);
    static final AnsiEscapes BLACK = color(30);
    static final AnsiEscapes RED = color(31);
    static final AnsiEscapes GREEN = color(32);
    static final AnsiEscapes YELLOW = color(33);
    static final AnsiEscapes BLUE = color(34);
    static final AnsiEscapes MAGENTA = color(35);
    static final AnsiEscapes CYAN = color(36);
    static final AnsiEscapes WHITE = color(37);
    static final AnsiEscapes DEFAULT = color(9);
    static final AnsiEscapes GREY = color(90);
    static final AnsiEscapes INTENSITY_BOLD = color(1);
    static final AnsiEscapes UNDERLINE = color(4);
    private static final char ESC = 27;
    private static final char BRACKET = '[';
    private final String value;

    private AnsiEscapes(String value) {
        this.value = value;
    }

    private static AnsiEscapes color(int code) {
        return new AnsiEscapes(code + "m");
    }

    static AnsiEscapes up(int count) {
        return new AnsiEscapes(count + "A");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        appendTo(sb);
        return sb.toString();
    }

    void appendTo(StringBuilder a) {
        a.append(ESC).append(BRACKET).append(value);
    }

}
