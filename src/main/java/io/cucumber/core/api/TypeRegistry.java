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

package io.cucumber.core.api;

import io.cucumber.cucumberexpressions.ParameterByTypeTransformer;
import io.cucumber.cucumberexpressions.ParameterType;
import io.cucumber.datatable.DataTableType;
import io.cucumber.datatable.TableCellByTypeTransformer;
import io.cucumber.datatable.TableEntryByTypeTransformer;
import io.cucumber.docstring.DocStringType;
import org.apiguardian.api.API;
import org.apiguardian.api.API.Status;

/**
 * The type registry records defines parameter types, data table types and
 * docstring transformers.
 *
 * @deprecated use the dedicated type annotations to register data table and
 *             parameter types instead
 */
@API(status = Status.STABLE)
@Deprecated
public interface TypeRegistry {

    /**
     * Defines a new parameter type.
     *
     * @param parameterType The new parameter type.
     */
    void defineParameterType(ParameterType<?> parameterType);

    /**
     * Defines a new docstring type.
     *
     * @param docStringType The new docstring type.
     */
    void defineDocStringType(DocStringType docStringType);

    /**
     * Defines a new data table type.
     *
     * @param tableType The new table type.
     */
    void defineDataTableType(DataTableType tableType);

    /**
     * Set default transformer for parameters which are not defined by
     * {@code defineParameterType(ParameterType<?>))}
     *
     * @param defaultParameterByTypeTransformer default transformer
     */
    void setDefaultParameterTransformer(ParameterByTypeTransformer defaultParameterByTypeTransformer);

    /**
     * Set default transformer for entries which are not defined by
     * {@code defineDataTableType(new DataTableType(Class<T>,TableEntryTransformer<T>))}
     *
     * @param tableEntryByTypeTransformer default transformer
     */
    void setDefaultDataTableEntryTransformer(TableEntryByTypeTransformer tableEntryByTypeTransformer);

    /**
     * Set default transformer for cells which are not defined by
     * {@code defineDataTableType(new DataTableType(Class<T>,TableEntryTransformer<T>))}
     *
     * @param tableCellByTypeTransformer default transformer
     */
    void setDefaultDataTableCellTransformer(TableCellByTypeTransformer tableCellByTypeTransformer);

}
