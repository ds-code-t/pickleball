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

package io.cucumber.java.en;

import io.cucumber.java.StepDefinitionAnnotations;
import io.cucumber.java.StepDefinitionAnnotation;

import org.apiguardian.api.API;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * To execute steps in a feature file the steps must be
 * connected to executable code. This can be done by annotating
 * a method with a cucumber or regular expression.
 * <p>
 * The parameters extracted from the step by the expression
 * along with the data table or doc string argument are provided as
 * arguments to the method.
 * <p>
 * The types of the parameters are determined by the cucumber or
 * regular expression.
 * <p>
 * The type of the data table or doc string argument is determined
 * by the argument name value. When none is provided cucumber will
 * attempt to transform the data table or doc string to the type
 * of the last argument.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@StepDefinitionAnnotation
@Documented
@Repeatable(Then.Thens.class)
@API(status = API.Status.STABLE)
public @interface Then {
    /**
     * A cucumber or regular expression.
     *
     * @return a cucumber or regular expression
     */
    String value();

    /**
     * Allows the use of multiple 'Then's on a single method.
     */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @StepDefinitionAnnotations
    @Documented
    @interface Thens {
        Then[] value();
    }
}
