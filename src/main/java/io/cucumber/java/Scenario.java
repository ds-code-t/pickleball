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

package io.cucumber.java;

import io.cucumber.core.backend.TestCaseState;
import org.apiguardian.api.API;

import java.net.URI;
import java.util.Collection;

/**
 * Before or After Hooks that declare a parameter of this type will receive an
 * instance of this class. It allows writing text and embedding media into
 * reports, as well as inspecting results (in an After block).
 * <p>
 * Note: This class is not intended to be used to create reports. To create
 * custom reports use the {@code io.cucumber.plugin.Plugin} class. The plugin
 * system provides a much richer access to Cucumbers then hooks after could
 * provide. For an example see {@code io.cucumber.core.plugin.PrettyFormatter}.
 */
@API(status = API.Status.STABLE)
public final class Scenario {

    private final TestCaseState delegate;

    Scenario(TestCaseState delegate) {
        this.delegate = delegate;
    }

    /**
     * @return tags of this scenario.
     */
    public Collection<String> getSourceTagNames() {
        return delegate.getSourceTagNames();
    }

    /**
     * Returns the current status of this scenario.
     * <p>
     * The scenario status is calculate as the most severe status of the
     * executed steps in the scenario so far.
     *
     * @return the current status of this scenario
     */
    public Status getStatus() {
        return Status.valueOf(delegate.getStatus().name());
    }

    /**
     * @return true if and only if {@link #getStatus()} returns "failed"
     */
    public boolean isFailed() {
        return delegate.isFailed();
    }

    /**
     * Attach data to the report(s).
     * 
     * <pre>
     * {@code
     * // Attach a screenshot. See your UI automation tool's docs for
     * // details about how to take a screenshot.
     * scenario.attach(pngBytes, "image/png", "Bartholomew and the Bytes of the Oobleck");
     * }
     * </pre>
     * <p>
     * To ensure reporting tools can understand what the data is a
     * {@code mediaType} must be provided. For example: {@code text/plain},
     * {@code image/png}, {@code text/html;charset=utf-8}.
     * <p>
     * Media types are defined in <a href=
     * https://tools.ietf.org/html/rfc7231#section-3.1.1.1>RFC 7231 Section
     * 3.1.1.1</a>.
     *
     * @param data      what to attach, for example an image.
     * @param mediaType what is the data?
     * @param name      attachment name
     */
    public void attach(byte[] data, String mediaType, String name) {
        delegate.attach(data, mediaType, name);
    }

    /**
     * Attaches some text based data to the report.
     *
     * @param data      what to attach, for example html.
     * @param mediaType what is the data?
     * @param name      attachment name
     * @see             #attach(byte[], String, String)
     */
    public void attach(String data, String mediaType, String name) {
        delegate.attach(data, mediaType, name);
    }

    /**
     * Outputs some text into the report.
     *
     * @param text what to put in the report.
     * @see        #attach(byte[], String, String)
     */
    public void log(String text) {
        delegate.log(text);
    }

    /**
     * @return the name of the Scenario
     */
    public String getName() {
        return delegate.getName();
    }

    /**
     * Returns the unique identifier for this scenario.
     * <p>
     * If this is a Scenario from Scenario Outlines this will return the id of
     * the example row in the Scenario Outline.
     * <p>
     * The id is not stable across multiple executions of Cucumber but does
     * correlate with ids used in messages output. Use the uri + line number to
     * obtain a somewhat stable identifier of a scenario.
     *
     * @return the id of the Scenario.
     */
    public String getId() {
        return delegate.getId();
    }

    /**
     * @return the uri of the Scenario.
     */
    public URI getUri() {
        return delegate.getUri();
    }

    /**
     * Returns the line in the feature file of the Scenario.
     * <p>
     * If this is a Scenario from Scenario Outlines this will return the line of
     * the example row in the Scenario Outline.
     *
     * @return the line in the feature file of the Scenario
     */
    public Integer getLine() {
        return delegate.getLine();
    }

}
