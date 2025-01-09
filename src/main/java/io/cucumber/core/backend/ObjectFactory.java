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

/**
 * Instantiates glue classes. Loaded via SPI.
 * <p>
 * Cucumber scenarios are executed against a test context that consists of
 * multiple glue classes. These must be instantiated and may optionally be
 * injected with dependencies. The object factory facilitates the creation of
 * both the glue classes and dependencies.
 *
 * @see java.util.ServiceLoader
 * @see io.cucumber.core.runtime.ObjectFactoryServiceLoader
 */
@API(status = API.Status.STABLE)
public interface ObjectFactory extends Container, Lookup {

    /**
     * Start the object factory. Invoked once per scenario.
     * <p>
     * While started {@link Lookup#getInstance(Class)} may be invoked.
     */
    void start();

    /**
     * Stops the object factory. Called once per scenario.
     * <p>
     * When stopped the object factory should dispose of all glue instances.
     */
    void stop();

}
