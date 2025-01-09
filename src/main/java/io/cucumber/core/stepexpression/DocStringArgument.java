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

package io.cucumber.core.stepexpression;

import io.cucumber.docstring.DocString;

import static java.util.Objects.requireNonNull;

public final class DocStringArgument implements Argument {

    private final DocStringTransformer<?> docStringType;
    private final String content;
    private final String contentType;

    public DocStringArgument(DocStringTransformer<?> docStringType, String content, String contentType) {
        this.docStringType = requireNonNull(docStringType);
        this.content = requireNonNull(content);
        this.contentType = contentType;
    }

    @Override
    public Object getValue() {
        return docStringType.transform(content, contentType);
    }

    @Override
    public String toString() {
        return "DocString:\n" + DocString.create(content, contentType);
    }

}
