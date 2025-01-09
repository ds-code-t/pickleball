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

import java.util.List;

@API(status = API.Status.STABLE)
public interface StepDefinition extends Located {

    /**
     * Invokes the step definition. The method should raise a Throwable if the
     * invocation fails, which will cause the step to fail.
     *
     * @param  args                              The arguments for the step
     * @throws CucumberBackendException          of a failure to invoke the step
     * @throws CucumberInvocationTargetException in case of a failure in the
     *                                           step.
     */
    void execute(Object[] args) throws CucumberBackendException, CucumberInvocationTargetException;

    /**
     * @return parameter information, may not return null
     */
    List<ParameterInfo> parameterInfos();

    /**
     * @return the pattern associated with this instance. Used for error
     *         reporting only.
     */
    String getPattern();

}
