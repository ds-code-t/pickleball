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

package io.cucumber.docstring;

import org.apiguardian.api.API;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.lang.String.format;

@API(status = API.Status.STABLE)
public final class DocStringTypeRegistry {

    private static final Class<String> DEFAULT_TYPE = String.class;
    private static final String DEFAULT_CONTENT_TYPE = "";
    private final Map<String, Map<Type, DocStringType>> docStringTypes = new HashMap<>();

    public DocStringTypeRegistry() {
        // Register default String handling
        defineDocStringType(new DocStringType(DEFAULT_TYPE, DEFAULT_CONTENT_TYPE, (String docString) -> docString));

        // Register additional default DocString types
        registerDefaultTypes();
    }

    private void registerDefaultTypes() {
        // Register io.cucumber.messages.types.DocString
        defineDocStringType(new DocStringType(
                io.cucumber.messages.types.DocString.class,
                DEFAULT_CONTENT_TYPE,
                content -> new io.cucumber.messages.types.DocString(null, null, content, "\"\"\"")
        ));

        // Register io.cucumber.core.stepexpression.DocStringArgument
        defineDocStringType(new DocStringType(
                io.cucumber.core.stepexpression.DocStringArgument.class,
                DEFAULT_CONTENT_TYPE,
                content -> new io.cucumber.core.stepexpression.DocStringArgument(
                        (input, type) -> input, content, null
                )
        ));
    }

    public void defineDocStringType(DocStringType docStringType) {
        DocStringType existing = lookupByContentTypeAndType(docStringType.getContentType(), docStringType.getType());
        if (existing != null) {
            throw createDuplicateTypeException(existing, docStringType);
        }
        Map<Type, DocStringType> map = docStringTypes.computeIfAbsent(docStringType.getContentType(),
                s -> new HashMap<>());
        map.put(docStringType.getType(), docStringType);
        docStringTypes.put(docStringType.getContentType(), map);
    }

    private static CucumberDocStringException createDuplicateTypeException(
            DocStringType existing, DocStringType docStringType
    ) {
        String contentType = existing.getContentType();
        return new CucumberDocStringException(format("" +
                        "There is already docstring type registered for '%s' and %s.\n" +
                        "You are trying to add '%s' and %s",
                emptyToAnonymous(contentType),
                existing.getType().getTypeName(),
                emptyToAnonymous(docStringType.getContentType()),
                docStringType.getType().getTypeName()));
    }

    private static String emptyToAnonymous(String contentType) {
        return contentType.isEmpty() ? "[anonymous]" : contentType;
    }

    List<DocStringType> lookup(String contentType, Type type) {
        DocStringType docStringType = lookupByContentTypeAndType(orDefault(contentType), type);
        if (docStringType != null) {
            return Collections.singletonList(docStringType);
        }

        return lookUpByType(type);
    }

    private String orDefault(String contentType) {
        return contentType == null ? DEFAULT_CONTENT_TYPE : contentType;
    }

    private List<DocStringType> lookUpByType(Type type) {
        return docStringTypes.values().stream()
                .flatMap(typeDocStringTypeMap -> typeDocStringTypeMap.entrySet().stream()
                        .filter(entry -> entry.getKey().equals(type))
                        .map(Map.Entry::getValue))
                .collect(Collectors.toList());
    }

    private DocStringType lookupByContentTypeAndType(String contentType, Type type) {
        Map<Type, DocStringType> docStringTypesByType = docStringTypes.get(contentType);
        if (docStringTypesByType == null) {
            return null;
        }
        return docStringTypesByType.get(type);
    }
}
