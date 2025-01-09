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

import java.util.function.Predicate;

import static io.cucumber.core.exception.UnrecoverableExceptions.rethrowIfUnrecoverable;
import static io.cucumber.core.runner.TestAbortedExceptions.createIsTestAbortedExceptionPredicate;

/**
 * Collects thrown exceptions.
 * <p>
 * When multiple exceptions are thrown, the worst exception is shown first.
 * Other exceptions are suppressed.
 */
final class ThrowableCollector {

    private Throwable throwable;
    private final Predicate<Throwable> isTestAbortedException = createIsTestAbortedExceptionPredicate();

    void execute(Runnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            rethrowIfUnrecoverable(t);
            add(t);
        }
    }

    private void add(Throwable throwable) {
        if (this.throwable == null) {
            this.throwable = throwable;
        } else if (isTestAbortedException(this.throwable) && !isTestAbortedException(throwable)) {
            throwable.addSuppressed(this.throwable);
            this.throwable = throwable;
        } else if (this.throwable != throwable) {
            this.throwable.addSuppressed(throwable);
        }
    }

    private boolean isTestAbortedException(Throwable throwable) {
        return isTestAbortedException.test(throwable);
    }

    Throwable getThrowable() {
        return throwable;
    }

}
