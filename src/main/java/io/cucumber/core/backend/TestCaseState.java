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

package io.cucumber.core.backend;

import org.apiguardian.api.API;

import java.net.URI;
import java.util.Collection;

@API(status = API.Status.STABLE)
public interface TestCaseState {

    /**
     * @return tags of this scenario.
     */
    Collection<String> getSourceTagNames();

    /**
     * Returns the current status of this test case.
     * <p>
     * The test case status is calculate as the most severe status of the
     * executed steps in the testcase so far.
     *
     * @return the current status of this test case
     */
    Status getStatus();

    /**
     * @return true if and only if {@link #getStatus()} returns "failed"
     */
    boolean isFailed();

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
    void attach(byte[] data, String mediaType, String name);

    /**
     * @param data      what to attach, for example html.
     * @param mediaType what is the data?
     * @param name      attachment name
     * @see             #attach(byte[], String, String)
     */
    void attach(String data, String mediaType, String name);

    /**
     * Outputs some text into the report.
     *
     * @param text what to put in the report.
     * @see        #attach(byte[], String, String)
     */
    void log(String text);

    /**
     * @return the name of the Scenario
     */
    String getName();

    /**
     * @return the id of the Scenario.
     */
    String getId();

    /**
     * @return the uri of the Scenario.
     */
    URI getUri();

    /**
     * @return the line in the feature file of the Scenario. If this is a
     *         Scenario from Scenario Outlines this will return the line of the
     *         example row in the Scenario Outline.
     */
    Integer getLine();

}
