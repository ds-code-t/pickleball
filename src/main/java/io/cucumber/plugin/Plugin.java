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

package io.cucumber.plugin;

import org.apiguardian.api.API;

import java.io.File;
import java.net.URI;
import java.net.URL;

/**
 * Marker interface for all plugins.
 * <p>
 * A plugin can be added to the runtime to listen in on step definition, summary
 * printing and test execution.
 * <p>
 * Plugins are added to the runtime from the command line or by annotating a
 * runner class with {@code @CucumberOptions} and may be provided with a
 * parameter using this syntax {@code com.example.MyPlugin:path/to/output.json}.
 * To accept this parameter the plugin must have a public constructor that
 * accepts one of the following arguments:
 * <ul>
 * <li>{@link String}</li>
 * <li>{@link Appendable}</li>
 * <li>{@link URI}</li>
 * <li>{@link URL}</li>
 * <li>{@link File}</li>
 * </ul>
 * <p>
 * To make the parameter optional the plugin must also have a public default
 * constructor.
 * <p>
 * Plugins may also implement one of these interfaces:
 * <ul>
 * <li>{@link ColorAware}</li>
 * <li>{@link StrictAware}</li>
 * <li>{@link EventListener}</li>
 * <li>{@link ConcurrentEventListener}</li>
 * </ul>
 */
@API(status = API.Status.STABLE)
public interface Plugin {

}
