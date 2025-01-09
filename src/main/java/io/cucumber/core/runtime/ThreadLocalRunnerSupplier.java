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

import io.cucumber.core.eventbus.AbstractEventBus;
import io.cucumber.core.eventbus.EventBus;
import io.cucumber.core.runner.Options;
import io.cucumber.core.runner.Runner;

import java.time.Instant;
import java.util.UUID;

/**
 * Creates a distinct runner for each calling thread. Each runner has its own
 * bus, backend- and glue-suppliers.
 * <p>
 * Each runners bus passes all events to the event bus of this supplier.
 */
public final class ThreadLocalRunnerSupplier implements RunnerSupplier {

    private final BackendSupplier backendSupplier;
    private final io.cucumber.core.runner.Options runnerOptions;
    private final SynchronizedEventBus sharedEventBus;
    private final ObjectFactorySupplier objectFactorySupplier;

    private final ThreadLocal<Runner> runners = ThreadLocal.withInitial(this::createRunner);

    public ThreadLocalRunnerSupplier(
            Options runnerOptions,
            EventBus sharedEventBus,
            BackendSupplier backendSupplier,
            ObjectFactorySupplier objectFactorySupplier
    ) {
        this.runnerOptions = runnerOptions;
        this.sharedEventBus = SynchronizedEventBus.synchronize(sharedEventBus);
        this.backendSupplier = backendSupplier;
        this.objectFactorySupplier = objectFactorySupplier;
    }

    @Override
    public Runner get() {
        return runners.get();
    }

    private Runner createRunner() {
        return new Runner(
            new LocalEventBus(sharedEventBus),
            backendSupplier.get(),
            objectFactorySupplier.get(),
            runnerOptions);
    }

    private static final class LocalEventBus extends AbstractEventBus {

        private final SynchronizedEventBus parent;

        LocalEventBus(final SynchronizedEventBus parent) {
            this.parent = parent;
        }

        @Override
        public <T> void send(final T event) {
            super.send(event);
            parent.send(event);
        }

        @Override
        public Instant getInstant() {
            return parent.getInstant();
        }

        @Override
        public UUID generateId() {
            return parent.generateId();
        }

    }

}
