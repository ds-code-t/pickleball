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

import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.Map;

@API(status = API.Status.STABLE)
public interface Snippet {

    /**
     * @return a {@link java.text.MessageFormat} template used to generate a
     *         snippet. The template can access the following variables:
     *         <ul>
     *         <li>{0} : Step Keyword</li>
     *         <li>{1} : Value of {@link #escapePattern(String)}</li>
     *         <li>{2} : Function name</li>
     *         <li>{3} : Value of {@link #arguments(Map)}</li>
     *         <li>{4} : Regexp hint comment</li>
     *         <li>{5} : value of {@link #tableHint()} if the step has a
     *         table</li>
     *         </ul>
     */
    MessageFormat template();

    /**
     * @return a hint about alternative ways to declare a table argument
     */
    String tableHint();

    /**
     * Constructs a string representation of the arguments a step definition
     * should accept. The arguments are provided a map of (suggested) names and
     * types. The arguments are ordered by their position.
     *
     * @param  arguments ordered pairs of names and types
     * @return           a string representation of the arguments
     */
    String arguments(Map<String, Type> arguments);

    /**
     * @param  pattern the computed pattern that will match an undefined step
     * @return         an escaped representation of the pattern, if escaping is
     *                 necessary.
     */
    String escapePattern(String pattern);

}
