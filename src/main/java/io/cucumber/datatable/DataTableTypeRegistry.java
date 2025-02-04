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

import io.cucumber.datatable.TypeFactory.JavaType;
import io.cucumber.datatable.TypeFactory.OptionalType;
import org.apiguardian.api.API;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static io.cucumber.datatable.TypeFactory.aListOf;
import static io.cucumber.datatable.TypeFactory.constructType;
import static java.lang.String.format;

@API(status = API.Status.STABLE)
public final class DataTableTypeRegistry {

    private final DataTableCellByTypeTransformer tableCellByTypeTransformer = new DataTableCellByTypeTransformer(this);
    private final Map<JavaType, DataTableType> tableTypeByType = new HashMap<>();
    private TableEntryByTypeTransformer defaultDataTableEntryTransformer;
    private TableCellByTypeTransformer defaultDataTableCellTransformer;

    public DataTableTypeRegistry(Locale locale) {
        final NumberParser numberParser = new NumberParser(locale);

        TableCellTransformer<Object> objectTableCellTransformer = applyIfPresent(s -> s);
        defineDataTableType(new DataTableType(Object.class, objectTableCellTransformer, true));

        TableCellTransformer<Object> stringTableCellTransformer = applyIfPresent(s -> s);
        defineDataTableType(new DataTableType(String.class, stringTableCellTransformer, true));

        TableCellTransformer<BigInteger> bigIntegerTableCellTransformer = applyIfPresent(BigInteger::new);
        defineDataTableType(new DataTableType(BigInteger.class, bigIntegerTableCellTransformer));

        TableCellTransformer<BigDecimal> bigDecimalTableCellTransformer = applyIfPresent(numberParser::parseBigDecimal);
        defineDataTableType(new DataTableType(BigDecimal.class, bigDecimalTableCellTransformer));

        TableCellTransformer<Byte> byteTableCellTransformer = applyIfPresent(Byte::decode);
        defineDataTableType(new DataTableType(Byte.class, byteTableCellTransformer));
        defineDataTableType(new DataTableType(byte.class, byteTableCellTransformer));

        TableCellTransformer<Short> shortTableCellTransformer = applyIfPresent(Short::decode);
        defineDataTableType(new DataTableType(Short.class, shortTableCellTransformer));
        defineDataTableType(new DataTableType(short.class, shortTableCellTransformer));

        TableCellTransformer<Integer> integerTableCellTransformer = applyIfPresent(Integer::decode);
        defineDataTableType(new DataTableType(Integer.class, integerTableCellTransformer));
        defineDataTableType(new DataTableType(int.class, integerTableCellTransformer));

        TableCellTransformer<Long> longTableCellTransformer = applyIfPresent(Long::decode);
        defineDataTableType(new DataTableType(Long.class, longTableCellTransformer));
        defineDataTableType(new DataTableType(long.class, longTableCellTransformer));

        TableCellTransformer<Float> floatTableCellTransformer = applyIfPresent(numberParser::parseFloat);
        defineDataTableType(new DataTableType(Float.class, floatTableCellTransformer));
        defineDataTableType(new DataTableType(float.class, floatTableCellTransformer));

        TableCellTransformer<Double> doubleTableCellTransformer = applyIfPresent(numberParser::parseDouble);
        defineDataTableType(new DataTableType(Double.class, doubleTableCellTransformer));
        defineDataTableType(new DataTableType(double.class, doubleTableCellTransformer));

        TableCellTransformer<Boolean> booleanTableCellTransformer = applyIfPresent(Boolean::parseBoolean);
        defineDataTableType(new DataTableType(Boolean.class, booleanTableCellTransformer, true));
        defineDataTableType(new DataTableType(boolean.class, booleanTableCellTransformer, true));
    }

    private static <R> TableCellTransformer<R> applyIfPresent(Function<String, R> f) {
        return s -> s == null ? null : f.apply(s);
    }

    public void defineDataTableType(DataTableType dataTableType) {
        DataTableType existing = tableTypeByType.get(dataTableType.getTargetType());
        if (existing != null && !existing.isReplaceable()) {
            throw new DuplicateTypeException(format("" +
                    "There already is a data table type registered that can supply %s.\n" +
                    "You are trying to register a %s for %s.\n" +
                    "The existing data table type registered a %s for %s.\n",
                dataTableType.getElementType(),
                dataTableType.getTransformerType().getSimpleName(),
                dataTableType.getElementType(),
                existing.getTransformerType().getSimpleName(),
                existing.getElementType()));
        }
        tableTypeByType.put(dataTableType.getTargetType(), dataTableType);
    }

    DataTableType lookupCellTypeByType(Type type) {
        return lookupTableTypeByType(type, javaType -> aListOf(aListOf(javaType)));
    }

    DataTableType lookupRowTypeByType(Type type) {
        return lookupTableTypeByType(type, TypeFactory::aListOf);
    }

    DataTableType lookupTableTypeByType(Type type) {
        return lookupTableTypeByType(type, Function.identity());
    }

    private DataTableType lookupTableTypeByType(Type type, Function<JavaType, JavaType> toTableType) {
        JavaType elementType = constructType(type);
        JavaType tableType = toTableType.apply(elementType);
        DataTableType dataTableType = tableTypeByType.get(tableType);
        if (dataTableType != null) {
            return dataTableType;
        }
        if (elementType instanceof OptionalType) {
            OptionalType optionalType = (OptionalType) elementType;
            return lookupTableTypeAsOptionalByType(optionalType, toTableType);
        }
        return null;
    }

    private DataTableType lookupTableTypeAsOptionalByType(
            OptionalType elementType, Function<JavaType, JavaType> toTableType
    ) {
        JavaType requiredType = elementType.getElementType();
        DataTableType dataTableType = tableTypeByType.get(toTableType.apply(requiredType));
        if (dataTableType == null) {
            return null;
        }
        Class<?> transformerType = dataTableType.getTransformerType();
        if (TableCellTransformer.class.equals(transformerType)) {
            return dataTableType.asOptional();
        }
        return null;
    }

    DataTableType getDefaultTableCellTransformer(Type tableType) {
        if (defaultDataTableCellTransformer == null) {
            return null;
        }

        if (tableType instanceof JavaType) {
            JavaType javaType = (JavaType) tableType;
            tableType = javaType.getOriginal();
        }

        return DataTableType.defaultCell(
            tableType,
            defaultDataTableCellTransformer);
    }

    DataTableType getDefaultTableEntryTransformer(Type tableType) {
        if (defaultDataTableEntryTransformer == null) {
            return null;
        }

        if (tableType instanceof JavaType) {
            JavaType javaType = (JavaType) tableType;
            tableType = javaType.getOriginal();
        }

        return DataTableType.defaultEntry(
            tableType,
            defaultDataTableEntryTransformer,
            tableCellByTypeTransformer);
    }

    public void setDefaultDataTableEntryTransformer(TableEntryByTypeTransformer defaultDataTableEntryTransformer) {
        this.defaultDataTableEntryTransformer = defaultDataTableEntryTransformer;
    }

    public void setDefaultDataTableCellTransformer(TableCellByTypeTransformer defaultDataTableCellTransformer) {
        this.defaultDataTableCellTransformer = defaultDataTableCellTransformer;
    }

}
