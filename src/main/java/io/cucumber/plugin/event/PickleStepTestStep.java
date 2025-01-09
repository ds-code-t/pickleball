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

import java.net.URI;
import java.util.List;

/**
 * A pickle test step matches a line in a Gherkin scenario or background.
 */
@API(status = API.Status.STABLE)
public interface PickleStepTestStep {

    /**
     * The pattern or expression used to match the glue code to the Gherkin
     * step.
     *
     * @return a pattern or expression
     */
    String getPattern();

    /**
     * The matched Gherkin step
     *
     * @return the matched step
     */
    Step getStep();

    /**
     * Returns the arguments provided to the step definition.
     * <p>
     * For example the step definition <code>Given (.*) pickles</code> when
     * matched with <code>Given 15 pickles</code> will receive as argument
     * <code>"15"</code>.
     *
     * @return argument provided to the step definition
     */
    List<Argument> getDefinitionArgument();

    /**
     * Returns arguments provided to the Gherkin step. E.g: a data table or doc
     * string.
     *
     * @return     arguments provided to the gherkin step.
     * @deprecated use {@link #getStep()}
     */
    @Deprecated
    StepArgument getStepArgument();

    /**
     * The line in the feature file defining this step.
     *
     * @return     a line number
     * @deprecated use {@link #getStep()}
     */
    @Deprecated
    int getStepLine();

    /**
     * A uri to to the feature of this step.
     *
     * @return a uri
     */
    URI getUri();

    /**
     * The full text of the Gherkin step.
     *
     * @return     the step text
     * @deprecated use {@code #getStep()}
     */
    @Deprecated
    String getStepText();

    String getCodeLocation();
}
