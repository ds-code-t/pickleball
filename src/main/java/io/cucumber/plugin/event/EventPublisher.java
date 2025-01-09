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

package io.cucumber.plugin.event;

import org.apiguardian.api.API;

@API(status = API.Status.STABLE)
public interface EventPublisher {

    /**
     * Registers an event handler for a specific event.
     * <p>
     * The available events types are:
     * <ul>
     * <li>{@link Event} - all events.
     * <li>{@link TestRunStarted} - the first event sent.
     * <li>{@link TestSourceRead} - sent for each feature file read, contains
     * the feature file source.
     * <li>{@link SnippetsSuggestedEvent} - sent for each step that could not be
     * matched to a step definition, contains the raw snippets for the step.
     * <li>{@link StepDefinedEvent} - sent for each step definition as it is
     * loaded, contains the StepDefinition
     * <li>{@link TestCaseStarted} - sent before starting the execution of a
     * Test Case(/Pickle/Scenario), contains the Test Case
     * <li>{@link TestStepStarted} - sent before starting the execution of a
     * Test Step, contains the Test Step
     * <li>{@link EmbedEvent} - calling scenario.attach in a hook triggers this
     * event.
     * <li>{@link WriteEvent} - calling scenario.log in a hook triggers this
     * event.
     * <li>{@link TestStepFinished} - sent after the execution of a Test Step,
     * contains the Test Step and its Result.
     * <li>{@link TestCaseFinished} - sent after the execution of a Test
     * Case(/Pickle/Scenario), contains the Test Case and its Result.
     * <li>{@link TestRunFinished} - the last event sent.
     * </ul>
     *
     * @param eventType the event type for which the handler is being registered
     * @param handler   the event handler
     * @param <T>       the event type
     * @see             Event
     */
    <T> void registerHandlerFor(Class<T> eventType, EventHandler<T> handler);

    /**
     * Unregister an event handler for a specific event
     *
     * @param eventType the event type for which the handler is being registered
     * @param handler   the event handler
     * @param <T>       the event type
     */
    <T> void removeHandlerFor(Class<T> eventType, EventHandler<T> handler);

}
