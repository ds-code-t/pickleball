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

package io.cucumber.datatable;

import java.lang.reflect.Type;
import java.util.List;

import static java.util.Collections.singletonList;

final class DataTableCellByTypeTransformer implements TableCellByTypeTransformer {

    private final DataTableTypeRegistry dataTableTypeRegistry;

    DataTableCellByTypeTransformer(DataTableTypeRegistry dataTableTypeRegistry) {
        this.dataTableTypeRegistry = dataTableTypeRegistry;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object transform(String cellValue, Type toValueType) {
        DataTableType typeByType = dataTableTypeRegistry.lookupCellTypeByType(toValueType);
        if (typeByType == null) {
            throw new CucumberDataTableException("There is no DataTableType registered for cell type " + toValueType);
        }
        List<List<String>> rawTable = singletonList(singletonList(cellValue));
        List<List<Object>> transformed = (List<List<Object>>) typeByType.transform(rawTable);
        return transformed.get(0).get(0);
    }
}
