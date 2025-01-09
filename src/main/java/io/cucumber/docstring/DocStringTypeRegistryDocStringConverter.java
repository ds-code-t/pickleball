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

import io.cucumber.docstring.DocString.DocStringConverter;
import org.apiguardian.api.API;

import java.lang.reflect.Type;
import java.util.List;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

@API(status = API.Status.STABLE)
public final class DocStringTypeRegistryDocStringConverter implements DocStringConverter {

    private final DocStringTypeRegistry docStringTypeRegistry;

    public DocStringTypeRegistryDocStringConverter(DocStringTypeRegistry docStringTypeRegistry) {
        this.docStringTypeRegistry = requireNonNull(docStringTypeRegistry);
    }

    @SuppressWarnings("unchecked")
    public <T> T convert(DocString docString, Type targetType) {
        if (DocString.class.equals(targetType)) {
            return (T) docString;
        }

        List<DocStringType> docStringTypes = docStringTypeRegistry.lookup(docString.getContentType(), targetType);

        if (docStringTypes.isEmpty()) {
            if (docString.getContentType() == null) {
                throw new CucumberDocStringException(format(
                    "It appears you did not register docstring type for %s",
                    targetType.getTypeName()));
            }
            throw new CucumberDocStringException(format(
                "It appears you did not register docstring type for '%s' or %s",
                docString.getContentType(),
                targetType.getTypeName()));
        }
        if (docStringTypes.size() > 1) {
            List<String> suggestedContentTypes = suggestedContentTypes(docStringTypes);
            if (docString.getContentType() == null) {
                throw new CucumberDocStringException(format(
                    "Multiple converters found for type %s, add one of the following content types to your docstring %s",
                    targetType.getTypeName(),
                    suggestedContentTypes));
            }
            throw new CucumberDocStringException(format(
                "Multiple converters found for type %s, and the content type '%s' did not match any of the registered types %s. Change the content type of the docstring or register a docstring type for '%s'",
                targetType.getTypeName(),
                docString.getContentType(),
                suggestedContentTypes,
                docString.getContentType()));
        }

        return (T) docStringTypes.get(0).transform(docString.getContent());
    }

    private List<String> suggestedContentTypes(List<DocStringType> docStringTypes) {
        return docStringTypes.stream()
                .map(DocStringType::getContentType)
                .map(DocStringTypeRegistryDocStringConverter::emptyToAnonymous)
                .sorted()
                .distinct()
                .collect(Collectors.toList());
    }

    private static String emptyToAnonymous(String contentType) {
        return contentType.isEmpty() ? "[anonymous]" : contentType;
    }

}
