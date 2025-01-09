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

import java.lang.reflect.InvocationTargetException;

/**
 * Thrown when an exception was thrown by glue code. Not to be confused with
 * {@link CucumberBackendException} which is thrown when the backend failed to
 * invoke the glue.
 */
@API(status = API.Status.STABLE)
public final class CucumberInvocationTargetException extends RuntimeException {

    private final Located located;
    private final InvocationTargetException invocationTargetException;

    public CucumberInvocationTargetException(Located located, InvocationTargetException invocationTargetException) {
        this.located = located;
        this.invocationTargetException = invocationTargetException;
    }

    public Throwable getInvocationTargetExceptionCause() {
        return invocationTargetException.getCause();
    }

    public Located getLocated() {
        return located;
    }

}
