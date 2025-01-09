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
 * <p>
 * This annotation can be specified on step definition method parameters to give
 * Cucumber a hint to transpose a DataTable.
 * <p>
 * For example, if you have the following Gherkin step with a table
 * 
 * <pre>
 * Given the user is
 *    | firstname	| Roberto	|
 *    | lastname	| Lo Giacco |
 *    | nationality	| Italian	|
 * </pre>
 * <p>
 * And a data table type to create a User
 *
 * <pre>
 * {@code
 * &#64;DataTableType
 * public User convert(Map<String, String> entry){
 *    return new User(
 *        entry.get("firstname"),
 *        entry.get("lastname")
 *        entry.get("nationality")
 *   );
 * }
 * }
 * </pre>
 * 
 * Then the following Java Step Definition would convert that into an User
 * object:
 * 
 * <pre>
 * &#064;Given("^the user is$")
 * public void the_user_is(&#064;Transpose User user) {
 *     this.user = user;
 * }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.PARAMETER })
@API(status = API.Status.STABLE)
public @interface Transpose {

    boolean value() default true;

}
