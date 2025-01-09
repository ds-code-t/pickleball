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

package io.cucumber.plugin;

import io.cucumber.plugin.event.EventPublisher;
import org.apiguardian.api.API;

/**
 * Listens to pickle execution events. Can be used to implement reporters.
 * <p>
 * When cucumber executes test in parallel or in a framework that supports
 * parallel execution (e.g. JUnit or TestNG)
 * {@link io.cucumber.plugin.event.TestCase} events from different pickles may
 * interleave.
 * <p>
 * This interface marks an {@link EventListener} as capable of understanding
 * interleaved pickle events.
 * <p>
 * While running tests in parallel cucumber makes the following guarantees:
 * <ol>
 * <li>The event publisher is synchronized. Events are not published
 * concurrently.</li>
 * <li>For test cases executed on different threads a callback registered on the
 * event publisher will be called by different threads. I.e.
 * {@code Thread.currentThread()} will return a different thread for two test
 * cases executed on a different thread (but not necessarily the executing
 * thread).</li>
 * </ol>
 *
 * @see io.cucumber.plugin.event.Event
 */
@API(status = API.Status.STABLE)
public interface ConcurrentEventListener extends Plugin {

    /**
     * Set the event publisher. The plugin can register event listeners with the
     * publisher.
     *
     * @param publisher the event publisher
     */
    void setEventPublisher(EventPublisher publisher);

}
