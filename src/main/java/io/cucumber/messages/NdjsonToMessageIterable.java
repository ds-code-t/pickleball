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

package io.cucumber.messages;

import io.cucumber.messages.types.Envelope;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import static java.util.Objects.requireNonNull;

/**
 * Iterates over messages read from a stream. Client code should not depend on this class
 * directly, but rather on a {@code Iterable<Envelope>} object.
 * Tests can then use a {@code new ArrayList<Envelope>} which implements the same interface.
 */
public final class NdjsonToMessageIterable implements Iterable<Envelope>, AutoCloseable {
    private final BufferedReader reader;
    private final Deserializer deserializer;

    public NdjsonToMessageIterable(InputStream inputStream, Deserializer deserializer) {
        this(
                new InputStreamReader(
                        requireNonNull(inputStream),
                        StandardCharsets.UTF_8),
                requireNonNull(deserializer)
        );
    }

    private NdjsonToMessageIterable(Reader reader, Deserializer deserializer) {
        this(new BufferedReader(reader), deserializer);
    }

    private NdjsonToMessageIterable(BufferedReader reader, Deserializer deserializer) {
        this.reader = reader;
        this.deserializer = deserializer;
    }

    @Override
    public Iterator<Envelope> iterator() {
        return new Iterator<Envelope>() {
            private Envelope next;

            @Override
            public boolean hasNext() {
                try {
                    String line = reader.readLine();
                    if (line == null)
                        return false;
                    if (line.trim().equals("")) {
                        return hasNext();
                    }
                    try {
                        next = deserializer.readValue(line);
                    } catch (IOException e) {
                        throw new RuntimeException(String.format("Could not parse JSON: %s", line), e);
                    }
                    return true;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public Envelope next() {
                if (next == null) {
                    throw new IllegalStateException(
                            "next() should only be called after a call to hasNext() that returns true");
                }
                return next;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    @FunctionalInterface
    public interface Deserializer {

        Envelope readValue(String json) throws IOException;

    }

}
