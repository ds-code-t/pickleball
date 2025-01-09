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

package io.cucumber.core.eventbus;

import io.cucumber.plugin.event.Event;
import io.cucumber.plugin.event.EventHandler;
import io.cucumber.plugin.event.EventPublisher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractEventPublisher implements EventPublisher {

    protected final Map<Class<?>, List<EventHandler>> handlers = new HashMap<>();

    @Override
    public final <T> void registerHandlerFor(Class<T> eventType, EventHandler<T> handler) {
        if (handlers.containsKey(eventType)) {
            handlers.get(eventType).add(handler);
        } else {
            List<EventHandler> list = new ArrayList<>();
            list.add(handler);
            handlers.put(eventType, list);
        }
    }

    @Override
    public final <T> void removeHandlerFor(Class<T> eventType, EventHandler<T> handler) {
        if (handlers.containsKey(eventType)) {
            handlers.get(eventType).remove(handler);
        }
    }

    protected <T> void sendAll(Iterable<T> events) {
        for (T event : events) {
            send(event);
        }
    }

    protected <T> void send(T event) {
        if (handlers.containsKey(Event.class) && event instanceof Event) {
            for (EventHandler handler : handlers.get(Event.class)) {
                // noinspection unchecked: protected by registerHandlerFor
                handler.receive(event);
            }
        }

        if (handlers.containsKey(event.getClass())) {
            for (EventHandler handler : handlers.get(event.getClass())) {
                // noinspection unchecked: protected by registerHandlerFor
                handler.receive(event);
            }
        }
    }

}
