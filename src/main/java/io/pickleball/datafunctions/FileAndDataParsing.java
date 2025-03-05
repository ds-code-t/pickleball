package io.pickleball.datafunctions;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileAndDataParsing {

    public static JsonNode buildJsonFromPath(File fileOrDir) {
        if (fileOrDir.isFile()) {
            return parseSingleFile(fileOrDir);
        } else if (fileOrDir.isDirectory()) {
            ObjectNode folderNode = JSON_MAPPER.createObjectNode();
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isDirectory()) {
                        folderNode.set(child.getName(), buildJsonFromPath(child));
                    } else {
                        JsonNode fileContents = parseSingleFile(child);
                        if (fileContents != null) {
                            String baseName = removeFileExtension(child.getName());
                            folderNode.set(baseName, fileContents);
                        }
                    }
                }
            }
            return folderNode;
        } else {
            return JSON_MAPPER.createObjectNode();
        }
    }

    public static JsonNode parseSingleFile(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String fileName = file.getName().toLowerCase();

            if (fileName.endsWith(".xml")) {
                try {
                    Object value = XML_MAPPER.readValue(content.strip(), Object.class);
                    return JSON_MAPPER.valueToTree(value);
                } catch (IOException e) {
                    System.out.println("XML parsing failed: " + e.getMessage());
                }
            }

            try {
                return JSON_MAPPER.readTree(content);
            } catch (IOException ignored) {
            }
            try {
                return YAML_MAPPER.readTree(content);
            } catch (IOException ignored) {
            }
            return null;
        } catch (IOException e) {
            System.out.println("File reading failed: " + e.getMessage());
            return null;
        }
    }

    public static String removeFileExtension(String fileName) {
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            return fileName.substring(0, dotIdx);
        }
        return fileName;
    }


    public static final ObjectMapper JSON_MAPPER = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public static final ObjectMapper YAML_MAPPER = YAMLMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public static final XmlMapper XML_MAPPER = XmlMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, true)
            .configure(JsonParser.Feature.IGNORE_UNDEFINED, true)
            .build();


    public static Configuration valueConfig = Configuration.builder()
            .mappingProvider(new JacksonMappingProvider())
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .options(
                    // You can still keep ALWAYS_RETURN_LIST if you want arrays
                    // for multiple matches, but remove AS_PATH_LIST!
                    Option.ALWAYS_RETURN_LIST,
                    Option.SUPPRESS_EXCEPTIONS
            )
            .build();
}
