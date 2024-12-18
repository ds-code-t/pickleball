package io.cucumber.docstring;

import org.apiguardian.api.API;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.lang.reflect.Type;
import java.util.Objects;

import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

/**
 * A doc string. For example:
 *
 * <pre>
 * """application/json
 * {
 *   "hello": "world"
 * }
 * """
 * </pre>
 * <p>
 * A doc string is either empty or contains some content. The content type is an
 * optional description of the content using a <a
 * href=https://tools.ietf.org/html/rfc2616#section-3.7>media-type</a>.
 * <p>
 * A DocString is immutable and thread safe.
 */
@API(status = API.Status.STABLE)
public final class DocString {

    private final String content;
    private final String contentType;
    private final DocStringConverter converter;

    protected DocString(String content, String contentType, DocStringConverter converter) {
        this.content = Objects.requireNonNull(content);
        this.contentType = contentType;
        this.converter = Objects.requireNonNull(converter);
    }

    // Existing methods remain unmodified...

    // New Factory Methods
    public static DocString fromString(String content) {
        return new DocString(content, "text/plain", new ConversionRequired());
    }

    public static DocString from(io.cucumber.messages.types.DocString docString) {
        return new DocString(docString.getContent(),
                docString.getMediaType().orElse(null),
                new ConversionRequired());
    }

    public static DocString from(io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument gherkinDocString) {
        return new DocString(gherkinDocString.getContent(),
                gherkinDocString.getContentType(),
                new ConversionRequired());
    }

    public static DocString from(io.cucumber.core.stepexpression.DocStringArgument stepDocString) {
        return new DocString(stepDocString.toString().split("\n", 2)[1].trim(),
                null,
                new ConversionRequired());
    }

    public static DocString from(io.cucumber.messages.types.PickleDocString pickleDocString) {
        return new DocString(pickleDocString.getContent(),
                pickleDocString.getMediaType().orElse(null),
                new ConversionRequired());
    }

    // Conversion Methods
    public io.cucumber.messages.types.DocString toMessagesDocString() {
        return new io.cucumber.messages.types.DocString(
                null,
                contentType,
                content,
                "\"\"\"");
    }

    public io.cucumber.core.stepexpression.DocStringArgument toDocStringArgument() {
        return new io.cucumber.core.stepexpression.DocStringArgument(
                (input, type) -> this, // Transform the content into this DocString instance
                content,
                contentType
        );
    }


    public io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument toGherkinMessagesDocString() {
        return new io.cucumber.core.gherkin.messages.GherkinMessagesDocStringArgument(
                new io.cucumber.messages.types.PickleDocString(contentType, content),
                0);
    }

    public io.cucumber.core.stepexpression.DocStringArgument toStepExpressionDocString() {
        return new io.cucumber.core.stepexpression.DocStringArgument(
                (input, type) -> input, content, contentType);
    }

    public io.cucumber.messages.types.PickleDocString toPickleDocString() {
        return new io.cucumber.messages.types.PickleDocString(contentType, content);
    }

    // Parsing Methods
    public Object toJson() throws Exception {
        return new ObjectMapper().readTree(content);
    }

    public Object toYaml() throws Exception {
        return new YAMLMapper().readTree(content);
    }

    public Object toXml() throws Exception {
        return new XmlMapper().readTree(content);
    }

    // Additional Constructors
    public static DocString create(String content) {
        return new DocString(content, null, new ConversionRequired());
    }

    public static DocString create(String content, String contentType) {
        return new DocString(content, contentType, new ConversionRequired());
    }

    public static DocString create(String content, String contentType, DocStringConverter converter) {
        return new DocString(content, contentType, converter);
    }

    // Retain existing behavior of the interface.
    public String getContent() {
        return content;
    }

    public String getContentType() {
        return contentType;
    }

    public Object convert(Type type) {
        return converter.convert(this, type);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DocString docString = (DocString) o;
        return content.equals(docString.content) &&
                Objects.equals(contentType, docString.contentType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(content, contentType);
    }

    @Override
    public String toString() {
        return stream(content.split("\n"))
                .collect(joining(
                        "\n      ",
                        "      \"\"\"" + contentType + "\n      ",
                        "\n      \"\"\""));
    }

    // ConversionRequired remains for legacy compatibility
    private static class ConversionRequired implements DocStringConverter {
        @Override
        public <T> T convert(DocString docString, Type targetType) {
            throw new UnsupportedOperationException("Conversion is not implemented");
        }
    }

    public interface DocStringConverter {
        <T> T convert(DocString docString, Type targetType);
    }
}
