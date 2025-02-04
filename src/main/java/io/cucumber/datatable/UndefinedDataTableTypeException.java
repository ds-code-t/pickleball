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

import java.lang.reflect.Type;
import java.util.List;

import static io.cucumber.datatable.TypeFactory.typeName;
import static java.lang.String.format;
import static java.util.stream.Collectors.joining;

@API(status = API.Status.STABLE)
public final class UndefinedDataTableTypeException extends CucumberDataTableException {
    private UndefinedDataTableTypeException(String message) {
        super(message);
    }

    static String problemNoDefaultTableCellTransformer(Type valueType) {
        return problem(valueType,
            "There was no default table cell transformer registered to transform %s.",
            "Please consider registering a default table cell transformer.");
    }

    static String problemNoTableCellTransformer(Type itemType) {
        return problem(itemType,
            "There was no table cell transformer registered for %s.",
            "Please consider registering a table cell transformer.");
    }

    static String problemNoTableEntryOrTableRowTransformer(Type itemType) {
        return problem(itemType,
            "There was no table entry or table row transformer registered for %s.",
            "Please consider registering a table entry or row transformer.");
    }

    static String problemNoTableEntryTransformer(Type valueType) {
        return problem(valueType,
            "There was no table entry transformer registered for %s.",
            "Please consider registering a table entry transformer.");
    }

    static String problemNoDefaultTableEntryTransformer(Type valueType) {
        return problem(valueType,
            "There was no default table entry transformer registered to transform %s.",
            "Please consider registering a default table entry transformer.");
    }

    static String problemTableTooShortForDefaultTableEntry(Type itemType) {
        return problem(itemType,
            "There was a default table entry transformer that could be used but the table was too short use it.",
            "Please increase the table height to use this converter.");
    }

    static String problemTableTooWideForDefaultTableCell(Type itemType) {
        return problem(itemType,
            "There was a default table cell transformer that could be used but the table was too wide to use it.",
            "Please reduce the table width to use this converter.");
    }

    static String problemTableTooWideForTableCellTransformer(Type itemType) {
        return problem(itemType,
            "There was a table cell transformer for %s but the table was too wide to use it.",
            "Please reduce the table width to use this converter.");
    }

    private static String problem(Type itemType, String problem, String solution) {
        return format(" - " + problem + "\n   " + solution, typeName(itemType));
    }

    private static String prettyProblemList(List<String> problems) {
        return "Please review these problems:\n" +
                problems.stream().collect(joining("" +

                        "\n" +
                        "\n",
                    "\n", "\n" +
                            "\n" +
                            "Note: Usually solving one is enough"));
    }

    static UndefinedDataTableTypeException singletonNoConverterDefined(Type type, List<String> problems) {
        return new UndefinedDataTableTypeException(
            format("Can't convert DataTable to %s.\n%s",
                typeName(type), prettyProblemList(problems)));

    }

    static UndefinedDataTableTypeException mapNoConverterDefined(Type keyType, Type valueType, List<String> problems) {
        return new UndefinedDataTableTypeException(
            format("Can't convert DataTable to Map<%s, %s>.\n%s",
                typeName(keyType), typeName(valueType), prettyProblemList(problems)));
    }

    static UndefinedDataTableTypeException mapsNoConverterDefined(Type keyType, Type valueType, List<String> problems) {
        return new UndefinedDataTableTypeException(
            format("Can't convert DataTable to List<Map<%s, %s>>.\n%s",
                typeName(keyType), typeName(valueType), prettyProblemList(problems)));
    }

    static CucumberDataTableException listNoConverterDefined(Type itemType, List<String> problems) {
        return new UndefinedDataTableTypeException(
            format("Can't convert DataTable to List<%s>.\n%s",
                typeName(itemType), prettyProblemList(problems)));
    }

    static CucumberDataTableException listsNoConverterDefined(Type itemType, List<String> problems) {
        return new UndefinedDataTableTypeException(
            format("Can't convert DataTable to List<List<%s>>.\n%s",
                typeName(itemType), prettyProblemList(problems)));
    }
}
