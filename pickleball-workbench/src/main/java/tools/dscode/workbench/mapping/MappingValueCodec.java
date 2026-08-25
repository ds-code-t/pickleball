package tools.dscode.workbench.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Encodes Mapping property values for the existing {@code mappingPut} /
 * {@code mappingRestore} services. This is presentation-side typing only;
 * Workbench does not keep a second Mapping store.
 */
public final class MappingValueCodec {
    public enum ValueType {
        STRING,
        NUMERIC,
        BOOLEAN,
        OBJECT_JSON,
        OBJECT_XML
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private MappingValueCodec() {
    }

    public static ValueType parseType(String raw) {
        if (raw == null || raw.isBlank()) return ValueType.STRING;
        String normalized = raw.trim().toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        return switch (normalized) {
            case "STRING", "TEXT" -> ValueType.STRING;
            case "NUMERIC", "NUMBER", "INTEGER", "INT", "LONG", "DOUBLE" -> ValueType.NUMERIC;
            case "BOOLEAN", "BOOL" -> ValueType.BOOLEAN;
            case "OBJECT_JSON", "JSON", "OBJECT_AS_JSON" -> ValueType.OBJECT_JSON;
            case "OBJECT_XML", "XML", "OBJECT_AS_XML" -> ValueType.OBJECT_XML;
            default -> throw new IllegalArgumentException("Unsupported Mapping value type: " + raw);
        };
    }

    public static ValueType inferType(Object value) {
        if (value instanceof Boolean) return ValueType.BOOLEAN;
        if (value instanceof Number) return ValueType.NUMERIC;
        if (value instanceof Map<?, ?> || value instanceof List<?>) return ValueType.OBJECT_JSON;
        return ValueType.STRING;
    }

    public static Object decode(ValueType type, String text) {
        Objects.requireNonNull(type, "type");
        String value = text == null ? "" : text;
        return switch (type) {
            case STRING -> value;
            case NUMERIC -> decodeNumeric(value);
            case BOOLEAN -> decodeBoolean(value);
            case OBJECT_JSON -> decodeJson(value);
            case OBJECT_XML -> decodeXml(value);
        };
    }

    public static Object decode(String type, String text) {
        return decode(parseType(type), text);
    }

    public static String encode(Object value) {
        if (value == null) return "";
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            try {
                return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value);
            } catch (JsonProcessingException failure) {
                return Objects.toString(value);
            }
        }
        return Objects.toString(value);
    }

    private static Number decodeNumeric(String text) {
        String value = text.strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException("Numeric Mapping value must not be blank.");
        }
        if (value.contains(".") || value.contains("e") || value.contains("E")) {
            return Double.valueOf(value);
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return Double.valueOf(value);
        }
    }

    private static Boolean decodeBoolean(String text) {
        String value = text.strip();
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.valueOf(value);
        }
        throw new IllegalArgumentException("Boolean Mapping value must be true or false.");
    }

    private static Object decodeJson(String text) {
        String value = text.strip();
        if (value.isBlank()) return new LinkedHashMap<String, Object>();
        try {
            if (value.startsWith("[")) {
                return JSON.readValue(value, new TypeReference<List<Object>>() { });
            }
            if (value.startsWith("{")) {
                return JSON.readValue(value, new TypeReference<Map<String, Object>>() { });
            }
            return JSON.readValue(value, Object.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid JSON Mapping value.", failure);
        }
    }

    private static Object decodeXml(String text) {
        String value = text.strip();
        if (value.isBlank()) return new LinkedHashMap<String, Object>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
            return elementToMap(document.getDocumentElement());
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid XML Mapping value.", failure);
        }
    }

    private static Map<String, Object> elementToMap(Element element) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("_name", element.getTagName());
        NamedNodeMap attributes = element.getAttributes();
        if (attributes != null && attributes.getLength() > 0) {
            Map<String, String> attrs = new LinkedHashMap<>();
            for (int i = 0; i < attributes.getLength(); i++) {
                Node attribute = attributes.item(i);
                attrs.put(attribute.getNodeName(), attribute.getNodeValue());
            }
            map.put("_attributes", attrs);
        }
        NodeList children = element.getChildNodes();
        List<Object> nested = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                nested.add(elementToMap((Element) child));
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(child.getTextContent());
            }
        }
        String body = text.toString().strip();
        if (!body.isEmpty()) map.put("_text", body);
        if (!nested.isEmpty()) map.put("_children", nested);
        return map;
    }
}
