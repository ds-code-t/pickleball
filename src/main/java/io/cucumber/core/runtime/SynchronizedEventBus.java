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
import io.cucumber.plugin.event.EventHandler;

import java.time.Instant;
import java.util.UUID;

public final class SynchronizedEventBus implements EventBus {

    private final EventBus delegate;

    private SynchronizedEventBus(final EventBus delegate) {
        this.delegate = delegate;
    }

    public static SynchronizedEventBus synchronize(EventBus eventBus) {
        if (eventBus instanceof SynchronizedEventBus) {
            return (SynchronizedEventBus) eventBus;
        }

        return new SynchronizedEventBus(eventBus);
    }

    @Override
    public synchronized <T> void registerHandlerFor(Class<T> eventType, EventHandler<T> handler) {
        delegate.registerHandlerFor(eventType, handler);
    }

    @Override
    public synchronized <T> void removeHandlerFor(Class<T> eventType, EventHandler<T> handler) {
        delegate.removeHandlerFor(eventType, handler);
    }

    @Override
    public Instant getInstant() {
        return delegate.getInstant();
    }

    @Override
    public UUID generateId() {
        return delegate.generateId();
    }

    @Override
    public synchronized <T> void send(final T event) {
        delegate.send(event);
    }

    @Override
    public synchronized <T> void sendAll(final Iterable<T> events) {
        delegate.sendAll(events);
    }

}
