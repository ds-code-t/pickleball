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
 * Register default data table entry transformer.
 * <p>
 * Valid method signatures are:
 * <ul>
 * <li>{@code Map<String,String>, Type -> Object}</li>
 * <li>{@code Object, Type -> Object}</li>
 * <li>{@code Map<String,String>, Type, TableCellByTypeTransformer -> Object}</li>
 * <li>{@code Object, Type, TableCellByTypeTransformer -> Object}</li>
 * </ul>
 *
 * @see io.cucumber.datatable.TableEntryByTypeTransformer
 * @see io.cucumber.datatable.DataTableType
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@API(status = API.Status.STABLE)
public @interface DefaultDataTableEntryTransformer {

    /**
     * Converts a data tables header headers to property names.
     * <p>
     * E.g. {@code Xml Http request} becomes {@code xmlHttpRequest}.
     *
     * @return true if conversion should be be applied, true by default.
     */
    boolean headersToProperties() default true;

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
