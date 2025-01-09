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

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

/**
 * A "good enough" PrintWriter implementation that writes UTF-8 and rethrows all
 * exceptions as runtime exceptions.
 */
final class UTF8PrintWriter implements Appendable, Closeable, Flushable {

    private final OutputStreamWriter out;

    UTF8PrintWriter(OutputStream out) {
        this.out = new UTF8OutputStreamWriter(out);
    }

    public void println() {
        try {
            out.write(System.lineSeparator());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void println(String s) {
        try {
            out.write(s);
            out.write(System.lineSeparator());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        try {
            out.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Appendable append(CharSequence csq) {
        try {
            return out.append(csq);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Appendable append(CharSequence csq, int start, int end) {
        try {
            return out.append(csq, start, end);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Appendable append(char c) {
        try {
            return out.append(c);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
