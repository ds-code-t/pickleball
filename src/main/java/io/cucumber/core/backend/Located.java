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

import java.util.Optional;

@API(status = API.Status.STABLE)
public interface Located {

    /**
     * @param  stackTraceElement The location of the step.
     * @return                   Return true if this matches the location. This
     *                           is used to filter stack traces.
     */
    boolean isDefinedAt(StackTraceElement stackTraceElement);

    /**
     * Location of step definition. Can either be a a method or stack trace
     * style location.
     * <p>
     * Examples:
     * <ul>
     * <li>
     * {@code com.example.StepDefinitions.given_an_example(io.cucumber.datatable.DataTable) }
     * </li>
     * <li>{@code com.example.StepDefinitions.<init>(StepDefinitions.java:9)}
     * </li>
     * </ul>
     *
     * @return The source line of the step definition.
     */
    String getLocation();

    default Optional<SourceReference> getSourceReference() {
        return Optional.empty();
    }
}
