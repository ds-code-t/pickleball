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

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import static java.util.Objects.requireNonNull;

public final class MessageToNdjsonWriter implements AutoCloseable {
    private final Writer writer;
    private final Serializer serializer;

    public MessageToNdjsonWriter(OutputStream outputStream, Serializer serializer) {
        this(
                new OutputStreamWriter(
                        requireNonNull(outputStream),
                        StandardCharsets.UTF_8),
                requireNonNull(serializer)
        );
    }

    private MessageToNdjsonWriter(Writer writer, Serializer serializer) {
        this.writer = writer;
        this.serializer = serializer;
    }

    public void write(Envelope message) throws IOException {
        requireNonNull(message);
        serializer.writeValue(writer, message);
        writer.write("\n");
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    @FunctionalInterface
    public interface Serializer {

        void writeValue(Writer writer, Envelope value) throws IOException;

    }
}
