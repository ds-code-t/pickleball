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

import static io.cucumber.datatable.TypeFactory.typeName;
import static java.lang.String.format;

@API(status = API.Status.STABLE)
public class CucumberDataTableException extends RuntimeException {
    CucumberDataTableException(String message) {
        super(message);
    }

    CucumberDataTableException(String s, Throwable throwable) {
        super(s, throwable);
    }

    static CucumberDataTableException cantConvertTo(Type type, String message) {
        return new CucumberDataTableException(
            format("Can't convert DataTable to %s. %s", typeName(type), message));
    }

    private static CucumberDataTableException cantConvertToMap(Type keyType, Type valueType, String message) {
        return new CucumberDataTableException(
            format("Can't convert DataTable to Map<%s, %s>.\n%s", typeName(keyType), typeName(valueType), message));
    }

    static <K, V> CucumberDataTableException duplicateKeyException(
            Type keyType, Type valueType, K key, V value, V replaced
    ) {
        return cantConvertToMap(keyType, valueType,
            format("Encountered duplicate key %s with values %s and %s", key, replaced, value));
    }

    static CucumberDataTableException keyValueMismatchException(
            boolean firstHeaderCellIsBlank, int keySize, Type keyType, int valueSize, Type valueType
    ) {
        if (keySize > valueSize) {
            return cantConvertToMap(keyType, valueType,
                "There are more keys than values. " +
                        "Did you use a TableEntryTransformer for the value while using a TableRow or TableCellTransformer for the keys?");
        }

        if (valueSize % keySize == 0) {
            return cantConvertToMap(keyType, valueType,
                format(
                    "There is more then one value per key. " +
                            "Did you mean to transform to Map<%s, List<%s>> instead?",
                    typeName(keyType), typeName(valueType)));
        }

        if (firstHeaderCellIsBlank) {
            return cantConvertToMap(keyType, valueType,
                "There are more values then keys. The first header cell was left blank. You can add a value there");
        }

        return cantConvertToMap(keyType, valueType,
            "There are more values then keys. " +
                    "Did you use a TableEntryTransformer for the key while using a TableRow or TableCellTransformer for the value?");
    }

    static CucumberDataTableException keysImplyTableEntryTransformer(Type keyType, Type valueType) {
        return cantConvertToMap(keyType, valueType,
            format("The first cell was either blank or you have registered a TableEntryTransformer for the key type.\n"
                    +
                    "\n" +
                    "This requires that there is a TableEntryTransformer for the value type but I couldn't find any.\n"
                    +
                    "\n" +
                    "You can either:\n" +
                    "\n" +
                    "  1) Use a DataTableType that uses a TableEntryTransformer for %s\n" +
                    "\n" +
                    "  2) Add a key to the first cell and use a DataTableType that uses a TableEntryTransformer for %s",
                valueType, keyType));
    }

}
