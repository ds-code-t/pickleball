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

import io.cucumber.plugin.event.Status;

public enum ExecutionMode {

    RUN {
        @Override
        Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) throws Throwable {
            stepDefinitionMatch.runStep(state);
            return Status.PASSED;
        }

    },
    DRY_RUN {
        @Override
        Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) throws Throwable {
            stepDefinitionMatch.dryRunStep(state);
            return Status.PASSED;
        }
    },
    SKIP {
        @Override
        Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) {
            return Status.SKIPPED;
        }
    },
    END_SCENARIO {
        @Override
        Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) {
            return Status.SKIPPED;
        }
    };

    abstract Status execute(StepDefinitionMatch stepDefinitionMatch, TestCaseState state) throws Throwable;

    public ExecutionMode next(ExecutionMode current) {
        return current == SKIP ? current : this;
    }
}
