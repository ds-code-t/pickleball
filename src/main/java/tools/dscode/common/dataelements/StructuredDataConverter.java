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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        JsonNode result = readXmlTree(XML_MAPPER, value);
        if (result == null) {
            throw new DataQueryException("XML input produced no value.");
        }
        return result;
    }

    private static final Pattern XML_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern NAMESPACED_NAME = Pattern.compile(
            "(?:</|<|\\s)([A-Za-z_][A-Za-z0-9_.-]*):([A-Za-z_][A-Za-z0-9_.-]*)"
    );
    private static final Pattern DECLARED_PREFIX = Pattern.compile(
            "xmlns:([A-Za-z_][A-Za-z0-9_.-]*)\\s*="
    );
    private static final Pattern ROOT_START_TAG = Pattern.compile("<(?![?!/])([^>]*?)(/?)>");

    /**
     * Reads XML, including a namespace-prefixed root such as {@code ns1:R} or
     * {@code soapenv:Envelope}. A prefix that already has an {@code xmlns}
     * declaration is unchanged. A prefix with no declaration is declared on the
     * root and parsed again, so the tree uses the local name. XML that already
     * parses is not rewritten.
     */
    public static JsonNode readXmlTree(ObjectMapper mapper, String xml)
            throws JsonProcessingException {
        rejectUnsafeXml(xml);
        try {
            return mapper.readTree(xml);
        } catch (JsonProcessingException exception) {
            String declared = declareMissingNamespacePrefixes(xml);
            if (declared == xml || !isUndeclaredNamespacePrefix(exception)) {
                throw exception;
            }
            return mapper.readTree(declared);
        }
    }

    /**
     * Same namespace fallback as {@link #readXmlTree(ObjectMapper, String)} for
     * callers that bind XML to a map before converting it to a tree.
     */
    public static Object readXmlValue(ObjectMapper mapper, String xml)
            throws JsonProcessingException {
        rejectUnsafeXml(xml);
        String stripped = xml == null ? "" : xml.strip();
        try {
            return mapper.readValue(stripped, Object.class);
        } catch (JsonProcessingException exception) {
            String declared = declareMissingNamespacePrefixes(stripped);
            if (declared.equals(stripped) || !isUndeclaredNamespacePrefix(exception)) {
                throw exception;
            }
            return mapper.readValue(declared, Object.class);
        }
    }

    static String declareMissingNamespacePrefixes(String xml) {
        if (xml == null || xml.indexOf(':') < 0) {
            return xml;
        }
        Set<String> used = new LinkedHashSet<>();
        Set<String> declared = new LinkedHashSet<>();
        Matcher tags = XML_TAG.matcher(xml);
        while (tags.find()) {
            String tag = tags.group();
            if (tag.startsWith("<?") || tag.startsWith("<!") || tag.startsWith("<!--")) {
                continue;
            }
            Matcher names = NAMESPACED_NAME.matcher(tag);
            while (names.find()) {
                String prefix = names.group(1);
                if (!"xmlns".equals(prefix)) {
                    used.add(prefix);
                }
            }
            Matcher declarations = DECLARED_PREFIX.matcher(tag);
            while (declarations.find()) {
                declared.add(declarations.group(1));
            }
        }
        used.removeAll(declared);
        if (used.isEmpty()) {
            return xml;
        }
        StringBuilder attributes = new StringBuilder();
        for (String prefix : used) {
            attributes.append(" xmlns:").append(prefix)
                    .append("=\"urn:pickleball:prefix:").append(prefix).append('"');
        }
        Matcher root = ROOT_START_TAG.matcher(xml);
        if (!root.find()) {
            return xml;
        }
        int slash = root.group(2).isEmpty() ? 1 : 2;
        int insertAt = root.end() - slash;
        return xml.substring(0, insertAt) + attributes + xml.substring(insertAt);
    }

    private static boolean isUndeclaredNamespacePrefix(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Undeclared namespace prefix")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public static void rejectUnsafeXml(String value) {
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
