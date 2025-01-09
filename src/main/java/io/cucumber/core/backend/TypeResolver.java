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

package io.cucumber.core.backend;

import org.apiguardian.api.API;

import java.lang.reflect.Type;

/**
 * Allows lazy resolution and validation of the type of a data table or doc
 * string argument.
 */
@API(status = API.Status.STABLE)
public interface TypeResolver {

    /**
     * A type to convert the data table or doc string to.
     * <p>
     * May throw an exception when the type could not adequately be determined
     * for instance due to a lack of generic information. If a value is return
     * it must be the same as {@link ParameterInfo#getType()}
     * <p>
     * When the {@link Object} type is returned no transform will be applied to
     * the data table or doc string.
     *
     * @return                  a type
     * @throws RuntimeException when the type could not adequately be determined
     */
    Type resolve() throws RuntimeException;

}
