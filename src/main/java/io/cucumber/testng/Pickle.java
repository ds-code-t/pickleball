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

package io.cucumber.testng;

import io.pickleball.executions.ExecutionConfig;
import org.apiguardian.api.API;

import java.net.URI;
import java.util.List;

/**
 * Wraps CucumberPickle to avoid exposing it as part of the public api.
 */
@API(status = API.Status.STABLE)
public final class Pickle {

    private final io.cucumber.core.gherkin.Pickle pickle;

    Pickle(io.cucumber.core.gherkin.Pickle pickle) {
        this.pickle = pickle;
    }

    public io.cucumber.core.gherkin.Pickle getPickle() {
        return pickle;
    }

    public String getName() {
        return pickle.getName();
    }

    public int getScenarioLine() {
        return pickle.getScenarioLocation().getLine();
    }

    public int getLine() {
        return pickle.getLocation().getLine();
    }

    public List<String> getTags() {
        return pickle.getTags();
    }

    public int getPriority () {
        return ExecutionConfig.getPriority(pickle.getTags(), String.valueOf(getUri()) + " Line: " + getLine());
    }


    public URI getUri() {
        return pickle.getUri();
    }

}
