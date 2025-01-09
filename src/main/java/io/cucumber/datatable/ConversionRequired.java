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

import io.cucumber.datatable.DataTable.TableConverter;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

final class ConversionRequired implements TableConverter {

    @Override
    public <T> T convert(DataTable dataTable, Type type) {
        return convert(dataTable, type, false);
    }

    @Override
    public <T> T convert(DataTable dataTable, Type type, boolean transposed) {
        throw new CucumberDataTableException(String
                .format("Can't convert DataTable to %s. You have to write the conversion for it in this method", type));
    }

    @Override
    public <T> List<T> toList(DataTable dataTable, Type itemType) {
        throw new CucumberDataTableException(String.format(
            "Can't convert DataTable to List<%s>. You have to write the conversion for it in this method", itemType));
    }

    @Override
    public <T> List<List<T>> toLists(DataTable dataTable, Type itemType) {
        throw new CucumberDataTableException(String.format(
            "Can't convert DataTable to List<List<%s>>. You have to write the conversion for it in this method",
            itemType));
    }

    @Override
    public <K, V> Map<K, V> toMap(DataTable dataTable, Type keyType, Type valueType) {
        throw new CucumberDataTableException(String.format(
            "Can't convert DataTable to Map<%s,%s>. You have to write the conversion for it in this method", keyType,
            valueType));
    }

    @Override
    public <K, V> List<Map<K, V>> toMaps(DataTable dataTable, Type keyType, Type valueType) {
        throw new CucumberDataTableException(String.format(
            "Can't convert DataTable to List<Map<%s,%s>>. You have to write the conversion for it in this method",
            keyType, valueType));
    }

}
