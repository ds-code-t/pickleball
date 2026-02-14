package tools.dscode.common.servicecalls;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class JacksonUtils {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();
    private static final XmlMapper XML_MAPPER = new XmlMapper();

    private JacksonUtils() {
    }

    public static JsonNode toJSON(@Language("JSON") @NotNull String jsonString) {
        try {
            return JSON_MAPPER.readTree(jsonString);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    public static JsonNode toYAML(@Language("YAML") @NotNull String yamlString) {
        try {
            return YAML_MAPPER.readTree(yamlString);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage(), e);
        }
    }

    public static JsonNode toXML(@Language("XML") @NotNull String xmlString) {
        try {
            return XML_MAPPER.readTree(xmlString);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid XML: " + e.getMessage(), e);
        }
    }


    @Language("JSON")
    public static String formatJSON(@Language("JSON") @NotNull String jsonString) {
        try {
            JsonNode node = JSON_MAPPER.readTree(jsonString);
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    @Language("YAML")
    public static String formatYAML(@Language("YAML") @NotNull String yamlString) {
        try {
            JsonNode node = YAML_MAPPER.readTree(yamlString);
            return YAML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid YAML: " + e.getMessage(), e);
        }
    }

    @Language("XML")
    public static String formatXML(@Language("XML") @NotNull String xmlString) {
        try {
            JsonNode node = XML_MAPPER.readTree(xmlString);
            return XML_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid XML: " + e.getMessage(), e);
        }
    }

    public static void main(String[] args) {
        toJSON(

                """
                        {
                        "A": "Some text"
                        }
                        """
        );
    }
}