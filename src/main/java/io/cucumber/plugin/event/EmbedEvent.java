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

package io.cucumber.plugin.event;

import org.apiguardian.api.API;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

@API(status = API.Status.STABLE)
public final class EmbedEvent extends TestCaseEvent {

    public final String name;
    private final byte[] data;
    private final String mediaType;

    public EmbedEvent(Instant timeInstant, TestCase testCase, byte[] data, String mediaType) {
        this(timeInstant, testCase, data, mediaType, null);
    }

    public EmbedEvent(Instant timeInstant, TestCase testCase, byte[] data, String mediaType, String name) {
        super(timeInstant, testCase);
        this.data = requireNonNull(data);
        this.mediaType = requireNonNull(mediaType);
        this.name = name;
    }

    public byte[] getData() {
        return data;
    }

    public String getMediaType() {
        return mediaType;
    }

    /**
     * @return     media type of the embedding.
     * @deprecated use {@link #getMediaType()}
     */
    @Deprecated
    public String getMimeType() {
        return mediaType;
    }

    public String getName() {
        return name;
    }

}
