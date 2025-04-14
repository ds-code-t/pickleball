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


package cucumber.api;


import java.util.Collection;
import java.util.List;

/**
 * Before or After Hooks that declare a parameter of this type will receive an instance of this class.
 * It allows writing text and embedding media into reports, as well as inspecting results (in an After block).
 */
public interface Scenario {
    /**
     * @return source_tag_names. Needed for compatibility with Capybara.
     */
    Collection<String> getSourceTagNames();

    /**
     * @return the <em>most severe</em> status of the Scenario's Steps.
     */
    Result.Type getStatus();

    /**
     * @return true if and only if {@link #getStatus()} returns "failed"
     */
    boolean isFailed();

    /**
     * Embeds data into the report(s). Some reporters (such as the progress one) don't embed data, but others do (html and json).
     * Example:
     *
     * <pre>
     * {@code
     * // Embed a screenshot. See your UI automation tool's docs for
     * // details about how to take a screenshot.
     * scenario.embed(pngBytes, "image/png");
     * }
     * </pre>
     *
     * @param data     what to embed, for example an image.
     * @param mimeType what is the data?
     */
    void embed(byte[] data, String mimeType);

    /**
     * Outputs some text into the report.
     *
     * @param text what to put in the report.
     */
    void write(String text);

    /**
     *
     * @return the name of the Scenario
     */
    String getName();

    /**
     * @return the id of the Scenario.
     */
    String getId();

    /**
     * @return the uri of the feature file of the Scenario.
     */
    String getUri();

    /**
     * @return the line(s) in the feature file of the Scenario. Scenarios from Scenario Outlines
     * return both the line of the example row the the line of the scenario outline.
     */
    List<Integer> getLines();
}
