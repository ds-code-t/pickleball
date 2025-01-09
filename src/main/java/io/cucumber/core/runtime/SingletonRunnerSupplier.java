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

import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.Options;
import io.cucumber.core.runner.Runner;

/**
 * Returns a single unique runner.
 * <p>
 * Not thread safe.
 */
public final class SingletonRunnerSupplier implements RunnerSupplier {

    private final BackendSupplier backendSupplier;
    private final Options runnerOptions;
    private final EventBus eventBus;
    private final ObjectFactorySupplier objectFactorySupplier;
    private Runner runner;

    public SingletonRunnerSupplier(
            Options runnerOptions,
            EventBus eventBus,
            BackendSupplier backendSupplier,
            ObjectFactorySupplier objectFactorySupplier
    ) {
        this.backendSupplier = backendSupplier;
        this.runnerOptions = runnerOptions;
        this.eventBus = eventBus;
        this.objectFactorySupplier = objectFactorySupplier;
    }

    @Override
    public Runner get() {
        if (runner == null) {
            runner = createRunner();
        }
        return runner;
    }

    private Runner createRunner() {
        return new Runner(
            eventBus,
            backendSupplier.get(),
            objectFactorySupplier.get(),
            runnerOptions);
    }

}
