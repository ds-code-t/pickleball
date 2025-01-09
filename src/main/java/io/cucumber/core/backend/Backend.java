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
import java.util.List;

@API(status = API.Status.STABLE)
public interface Backend {

    /**
     * Invoked once before all features. This is where steps and hooks should be
     * loaded.
     *
     * @param glue      Glue that provides the steps to be executed.
     * @param gluePaths The locations for the glue to be loaded.
     */
    void loadGlue(Glue glue, List<URI> gluePaths);

    /**
     * Invoked before a new scenario starts. Implementations should do any
     * necessary setup of new, isolated state here. Additional scenario scoped
     * step definitions can be loaded here. These step definitions should
     * implement {@link ScenarioScoped}
     */
    void buildWorld();

    /**
     * Invoked at the end of a scenario, after hooks
     */
    void disposeWorld();

    Snippet getSnippet();

}
