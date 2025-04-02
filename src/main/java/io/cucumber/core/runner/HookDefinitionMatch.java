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

package io.cucumber.core.runner;

import io.cucumber.core.backend.CucumberBackendException;
import io.cucumber.core.backend.CucumberInvocationTargetException;
import io.cucumber.core.backend.TestCaseState;
import io.cucumber.core.exception.CucumberException;
import io.cucumber.java.JavaStepDefinition;


import static io.cucumber.core.runner.StackManipulation.removeFrameworkFrames;
import static io.pickleball.cucumberutilities.AccessFunctions.safeCallMethod;

final class HookDefinitionMatch implements StepDefinitionMatch {

    private final CoreHookDefinition hookDefinition;

    HookDefinitionMatch(CoreHookDefinition hookDefinition) {
        this.hookDefinition = hookDefinition;
    }

    @Override
    public void runStep(TestCaseState state) throws Throwable {
        try {
            hookDefinition.execute(state);
        } catch (CucumberBackendException e) {
            throw couldNotInvokeHook(e);
        } catch (CucumberInvocationTargetException e) {
            throw removeFrameworkFrames(e);
        }
    }

    private Throwable couldNotInvokeHook(CucumberBackendException e) {
        return new CucumberException(String.format("" +
                "Could not invoke hook defined at '%s'.\n" +
                // TODO: Add doc URL
                "It appears there was a problem with the hook definition.",
            hookDefinition.getLocation()), e);
    }

    @Override
    public void dryRunStep(TestCaseState state) {
        // Do nothing
    }

    @Override
    public String getCodeLocation() {
        return hookDefinition.getLocation();
    }

    CoreHookDefinition getHookDefinition() {
        return hookDefinition;
    }

}
