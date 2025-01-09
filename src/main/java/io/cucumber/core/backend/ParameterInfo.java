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

@API(status = API.Status.STABLE)
public interface ParameterInfo {

    /**
     * Returns the type of this parameter. This type is used to provide a hint
     * to cucumber expressions to resolve anonymous parameter types.
     * <p>
     * Should always return the same value as {@link TypeResolver#resolve()} but
     * may not throw any exceptions. May return {@code Object.class} when no
     * information is available.
     *
     * @return the type of this parameter.
     */
    Type getType();

    /**
     * True if the data table should be transposed.
     *
     * @return true iff the data table should be transposed.
     */
    boolean isTransposed();

    /**
     * Returns a type resolver. The type provided by this resolver is used to
     * convert data table and doc string arguments to a java object.
     *
     * @return a type resolver
     */
    TypeResolver getTypeResolver();

}
