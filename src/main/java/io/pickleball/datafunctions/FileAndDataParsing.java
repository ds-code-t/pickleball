package io.pickleball.datafunctions;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import io.pickleball.cacheandstate.GlobalCache;
import io.pickleball.exceptions.PickleballException;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileAndDataParsing {



    public class FileReader {
        public static ObjectNode readFile(String path) throws PickleballException {
            try {
                ObjectMapper mapper;
                String lowerPath = path.toLowerCase();
                if (lowerPath.endsWith(".yaml") || lowerPath.endsWith(".yml")) {
                    mapper = new ObjectMapper(new YAMLFactory());
                } else if (lowerPath.endsWith(".json")) {
                    mapper = new ObjectMapper();
                } else if (lowerPath.endsWith(".xml")) {
                    mapper = new XmlMapper();
                } else {
                    throw new PickleballException("Unsupported file extension for path: " + path);
                }
                return (ObjectNode) mapper.readTree(
                        FileReader.class.getClassLoader().getResourceAsStream(path)
                );
            } catch (IOException e) {
                throw new PickleballException("Failed to read file at resources path '" + path + "': " + e.getMessage());
            }
        }
    }

    public static File getFile(String path)  {
        URL resourceUrl = GlobalCache.class.getClassLoader().getResource(path);
        try {
            return new File(resourceUrl.toURI());
        } catch (URISyntaxException e) {
            throw new PickleballException("Failed to read file at resources path '" +path + "' , " + e.getMessage());
        }
    }


    public static JsonNode getJsonNode(String path) {
        return buildJsonFromPath(getFile(path));
    }

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
