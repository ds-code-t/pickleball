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

package io.cucumber.java;

import org.apiguardian.api.API;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Register doc string type.
 * <p>
 * The method must have this signature:
 * <ul>
 * <li>{@code String -> Author}</li>
 * </ul>
 * NOTE: {@code Author} is an example of the type of the parameter type.
 * <p>
 * Each docstring has a content type (text, json, ect) and type. The When not
 * provided in the annotation the content type is the name of the annotated
 * method. The type is the return type of the annotated. method.
 * <p>
 * Cucumber selects the doc string type to convert a docstring to the target
 * used in a step definition by:
 * <ol>
 * <li>Searching for an exact match of content type and target type</li>
 * <li>Searching for a unique match for target type</li>
 * <li>Throw an exception of zero or more then one docstring type was found</li>
 * </ol>
 * By default, Cucumber registers a docstring type for the anonymous content
 * type (i.e. no content type) and type {@link java.lang.String}.
 *
 * @see io.cucumber.docstring.DocStringType
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@API(status = API.Status.STABLE)
public @interface DocStringType {

    /**
     * Name of the content type.
     * <p>
     * When not provided this will default to the name of the annotated method.
     *
     * @return content type
     */
    String contentType() default "";

}
