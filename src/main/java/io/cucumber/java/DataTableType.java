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
 * Register a data table type.
 * <p>
 * The signature of the method is used to determine which data table type is
 * registered:
 * <ul>
 * <li>{@code String -> Author} will register a
 * {@link io.cucumber.datatable.TableCellTransformer}</li>
 * <li>{@code Map<String, String> -> Author} will register a
 * {@link io.cucumber.datatable.TableEntryTransformer}</li>
 * <li>{@code List<String> -> Author} will register a
 * {@link io.cucumber.datatable.TableRowTransformer}</li>
 * <li>{@code DataTable -> Author} will register a
 * {@link io.cucumber.datatable.TableTransformer}</li>
 * </ul>
 * NOTE: {@code Author} is an example of the class you want to convert the table
 * to.
 *
 * @see io.cucumber.datatable.DataTableType
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@API(status = API.Status.STABLE)
public @interface DataTableType {

    /**
     * Replace these strings in the Datatable with empty strings.
     * <p>
     * A data table can only represent absent and non-empty strings. By
     * replacing a known value (for example [empty]) a data table can also
     * represent empty strings.
     * <p>
     * It is not recommended to use multiple replacements in the same table.
     *
     * @return strings to be replaced with empty strings.
     */
    String[] replaceWithEmptyString() default {};

}
