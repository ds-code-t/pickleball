package tools.dscode.common.dataelements;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.cucumber.datatable.DataTable;
import io.cucumber.docstring.DocString;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import static tools.dscode.coredefinitions.DataTableDefinitions.dataTableToJsonNode;

public final class StructuredDataConverter {
    private static final ObjectMapper JSON_MAPPER =
            JsonMapper.builder().build();
    private static final ObjectMapper YAML_MAPPER =
            YAMLMapper.builder().build();
    private static final XmlMapper XML_MAPPER =
            XmlMapper.builder().build();

    private StructuredDataConverter() {
    }

    public static Object convert(
            Object source,
            DataElementKind requestedKind
    ) {
        try {
            return switch (requestedKind) {
                case STRUCTURED_DATA -> bestEffortData(source);
                case JSON_DATA -> jsonData(source);
                case YAML_DATA -> yamlData(source);
                case XML_DATA -> xmlData(source);
                case DATA_STRING -> dataString(source);
                case JSON_STRING -> jsonString(source);
                case YAML_STRING -> yamlString(source);
                case XML_STRING -> xmlString(source);
                default -> throw new DataQueryException(
                        "Structured conversion is not implemented for "
                                + requestedKind.singularName() + "."
                );
            };
        } catch (DataQueryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DataQueryException(
                    "Could not convert "
                            + describeSource(source)
                            + " to " + requestedKind.singularName()
                            + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    private static JsonNode bestEffortData(Object source)
            throws JsonProcessingException {
        if (source instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        if (source instanceof DataTable dataTable) {
            return dataTableToJsonNode(dataTable);
        }
        if (source instanceof DocString docString) {
            String contentType = normalizeType(docString.getContentType());
            return switch (contentType) {
                case "json" -> parseJson(docString.getContent());
                case "yaml", "yml", "x-yaml" ->
                        parseYaml(docString.getContent());
                case "xml" -> parseXml(docString.getContent());
                default -> detectString(docString.getContent());
            };
        }
        if (source instanceof String text) {
            return detectString(text);
        }
        return JSON_MAPPER.valueToTree(source);
    }

    private static JsonNode jsonData(Object source)
            throws JsonProcessingException {
        if (source instanceof JsonNode jsonNode) {
            return jsonNode;
        }
        if (source instanceof DocString docString) {
            return parseJson(docString.getContent());
        }
        if (source instanceof String text) {
            return parseJson(text);
        }
        if (source instanceof DataTable dataTable) {
            return dataTableToJsonNode(dataTable);
        }
        return JSON_MAPPER.valueToTree(source);
    }

    private static JsonNode yamlData(Object source)
            throws JsonProcessingException {
        if (source instanceof DocString docString) {
            return parseYaml(docString.getContent());
        }
        if (source instanceof String text) {
            return parseYaml(text);
        }
        if (source instanceof DataTable dataTable) {
            return dataTableToJsonNode(dataTable);
        }
        return source instanceof JsonNode jsonNode
                ? jsonNode
                : JSON_MAPPER.valueToTree(source);
    }

    private static JsonNode xmlData(Object source)
            throws JsonProcessingException {
        if (source instanceof DocString docString) {
            return parseXml(docString.getContent());
        }
        if (source instanceof String text) {
            return parseXml(text);
        }
        if (source instanceof DataTable dataTable) {
            return dataTableToJsonNode(dataTable);
        }
        return source instanceof JsonNode jsonNode
                ? jsonNode
                : JSON_MAPPER.valueToTree(source);
    }

    private static String dataString(Object source)
            throws JsonProcessingException {
        if (source == null) {
            return "null";
        }
        if (source instanceof String text) {
            return text;
        }
        if (source instanceof DocString docString) {
            return docString.getContent();
        }
        if (source instanceof DataTable dataTable) {
            return JSON_MAPPER.writeValueAsString(
                    dataTableToJsonNode(dataTable)
            );
        }
        if (source instanceof JsonNode
                || source instanceof Map<?, ?>
                || source instanceof Collection<?>
                || source.getClass().isArray()) {
            return JSON_MAPPER.writeValueAsString(
                    source instanceof JsonNode jsonNode
                            ? jsonNode
                            : JSON_MAPPER.valueToTree(source)
            );
        }
        return DataStringFormatter.format(source);
    }

    private static String jsonString(Object source)
            throws JsonProcessingException {
        return JSON_MAPPER.writeValueAsString(bestEffortData(source));
    }

    private static String yamlString(Object source)
            throws JsonProcessingException {
        return YAML_MAPPER.writeValueAsString(bestEffortData(source))
                .stripTrailing();
    }

    private static String xmlString(Object source)
            throws JsonProcessingException {
        if (source instanceof String text && looksLikeXml(text)) {
            rejectUnsafeXml(text);
            parseXml(text);
            return text;
        }
        return XML_MAPPER.writer()
                .withRootName("Data")
                .writeValueAsString(bestEffortData(source));
    }

    private static JsonNode detectString(String source)
            throws JsonProcessingException {
        String text = source == null ? "" : source.trim();
        if (looksLikeJson(text)) {
            return parseJson(text);
        }
        if (looksLikeXml(text)) {
            return parseXml(text);
        }
        return parseYaml(source);
    }

    private static JsonNode parseJson(String value)
            throws JsonProcessingException {
        JsonNode result = JSON_MAPPER.readTree(value);
        if (result == null) {
            throw new DataQueryException("JSON input produced no value.");
        }
        return result;
    }

    private static JsonNode parseYaml(String value)
            throws JsonProcessingException {
        JsonNode result = YAML_MAPPER.readTree(value);
        if (result == null) {
            throw new DataQueryException("YAML input produced no value.");
        }
        return result;
    }

    private static JsonNode parseXml(String value)
            throws JsonProcessingException {
        rejectUnsafeXml(value);
        JsonNode result = XML_MAPPER.readTree(value);
        if (result == null) {
            throw new DataQueryException("XML input produced no value.");
        }
        return result;
    }

    private static void rejectUnsafeXml(String value) {
        String normalized = value == null
                ? ""
                : value.toUpperCase(Locale.ROOT);
        if (normalized.contains("<!DOCTYPE")
                || normalized.contains("<!ENTITY")) {
            throw new DataQueryException(
                    "XML declarations containing DOCTYPE or ENTITY "
                            + "are not supported."
            );
        }
    }

    private static boolean looksLikeJson(String value) {
        return value.startsWith("{") || value.startsWith("[");
    }

    private static boolean looksLikeXml(String value) {
        return value.trim().startsWith("<");
    }

    private static String normalizeType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String normalized = contentType.trim()
                .toLowerCase(Locale.ROOT)
                .replaceFirst(";.*$", "");
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        int plus = normalized.lastIndexOf('+');
        return plus >= 0
                ? normalized.substring(plus + 1)
                : normalized;
    }

    private static String describeSource(Object source) {
        return source == null
                ? "null"
                : source.getClass().getSimpleName();
    }
}
