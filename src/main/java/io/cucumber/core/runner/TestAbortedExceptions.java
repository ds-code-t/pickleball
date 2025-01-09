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

package io.cucumber.core.runner;

import io.cucumber.core.logging.Logger;
import io.cucumber.core.logging.LoggerFactory;
import io.cucumber.core.resource.ClassLoaders;

import java.util.Arrays;
import java.util.function.Predicate;

import static io.cucumber.core.exception.UnrecoverableExceptions.rethrowIfUnrecoverable;

/**
 * Identifies which exceptions signal that a test has been aborted.
 */
final class TestAbortedExceptions {

    private static final Logger log = LoggerFactory.getLogger(TestAbortedExceptions.class);

    private static final String[] TEST_ABORTED_EXCEPTIONS = {
            "org.junit.AssumptionViolatedException",
            "org.junit.internal.AssumptionViolatedException",
            "org.opentest4j.TestAbortedException",
            "org.testng.SkipException",
    };

    static Predicate<Throwable> createIsTestAbortedExceptionPredicate() {
        ClassLoader defaultClassLoader = ClassLoaders.getDefaultClassLoader();
        return throwable -> Arrays.stream(TEST_ABORTED_EXCEPTIONS)
                .anyMatch(s -> {
                    try {
                        Class<?> aClass = defaultClassLoader.loadClass(s);
                        return aClass.isInstance(throwable);
                    } catch (Throwable t) {
                        rethrowIfUnrecoverable(t);
                        log.debug(t,
                            () -> String.format(
                                "Failed to load class %s: will not be supported for aborted executions.", s));
                    }
                    return false;
                });
    }

    private TestAbortedExceptions() {

    }

}
