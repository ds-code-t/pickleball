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

import org.apiguardian.api.API;

import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;

@API(status = API.Status.INTERNAL)
public final class DataTableDiff {

    private final List<List<String>> table;
    private final List<DiffType> diffTypes;

    static DataTableDiff create(List<SimpleEntry<List<String>, DiffType>> diffTableRows) {
        List<DiffType> diffTypes = new ArrayList<>(diffTableRows.size());
        List<List<String>> table = new ArrayList<>();

        for (SimpleEntry<List<String>, DiffType> row : diffTableRows) {
            table.add(row.getKey());
            diffTypes.add(row.getValue());
        }
        return new DataTableDiff(table, diffTypes);
    }

    private DataTableDiff(List<List<String>> table, List<DiffType> diffTypes) {
        this.table = table;
        this.diffTypes = diffTypes;
    }

    public boolean isEmpty() {
        return !diffTypes.contains(DiffType.DELETE) && !diffTypes.contains(DiffType.INSERT);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        DataTableFormatter.builder()
                .prefixRow(this::indentForRow)
                .build()
                .formatTo(DataTable.create(table), result);
        return result.toString();
    }

    private String indentForRow(Integer rowIndex) {
        switch (diffTypes.get(rowIndex)) {
            case DELETE:
                return "    - ";
            case INSERT:
                return "    + ";
            default:
                return "      ";
        }
    }

}
