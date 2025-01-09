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

/**
 * Represents a step in a scenario.
 */
@API(status = API.Status.STABLE)
public interface Step {

    /**
     * Returns this Gherkin step argument. Can be either a data table or doc
     * string.
     *
     * @return a step argument, null if absent
     */
    StepArgument getArgument();

    /**
     * Returns this steps keyword. I.e. Given, When, Then.
     *
     * @return     step key word
     * @deprecated use {@link #getKeyword()} instead
     */
    @Deprecated
    default String getKeyWord() {
        return getKeyword();
    }

    /**
     * Returns this steps keyword. I.e. Given, When, Then.
     *
     * @return step key word
     */
    String getKeyword();

    /**
     * Returns this steps text.
     *
     * @return this steps text
     */
    String getText();

    /**
     * Line in the source this step is located in.
     *
     * @return step line number
     */
    int getLine();

    /**
     * Location of this step in in the source.
     *
     * @return location in the source
     */
    Location getLocation();

}
