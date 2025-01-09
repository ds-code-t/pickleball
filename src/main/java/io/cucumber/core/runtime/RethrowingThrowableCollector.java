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

package io.cucumber.core.runtime;

import io.cucumber.core.exception.CompositeCucumberException;
import io.cucumber.core.exception.UnrecoverableExceptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static io.cucumber.core.exception.ExceptionUtils.throwAsUncheckedException;
import static io.cucumber.core.exception.UnrecoverableExceptions.rethrowIfUnrecoverable;

/**
 * Collects and rethrows thrown exceptions.
 */
final class RethrowingThrowableCollector {

    private final List<Throwable> thrown = Collections.synchronizedList(new ArrayList<>());

    void executeAndThrow(Runnable runnable) {
        try {
            runnable.run();
        } catch (TestCaseFailed e) {
            throwAsUncheckedException(e.getCause());
        } catch (Throwable t) {
            UnrecoverableExceptions.rethrowIfUnrecoverable(t);
            add(t);
            throwAsUncheckedException(t);
        }
    }

    <T> T executeAndThrow(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            rethrowIfUnrecoverable(t);
            thrown.add(t);
            throwAsUncheckedException(t);
            return null;
        }
    }

    void add(Throwable throwable) {
        thrown.add(throwable);
    }

    Throwable getThrowable() {
        // Don't try any tricks with `.addSuppressed`. Other frameworks are
        // already doing this.
        if (thrown.isEmpty()) {
            return null;
        }
        if (thrown.size() == 1) {
            return thrown.get(0);
        }
        return new CompositeCucumberException(thrown);
    }

}
