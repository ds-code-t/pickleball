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
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    public static File getFile(String path) {
        URL resourceUrl = GlobalCache.class.getClassLoader().getResource(path);
        System.out.println("@@resourceUrl: " + resourceUrl);
        try {
            return new File(resourceUrl.toURI());
        } catch (Exception e1) {
            System.out.println("No resources found at path '" + path + "'");
            return null;
        }
    }


    public static JsonNode buildJsonFromPath(String resourcePath) {
        ObjectNode folderNode = JSON_MAPPER.createObjectNode();
        URL resourceUrl = GlobalCache.class.getClassLoader().getResource(resourcePath);
        if (resourceUrl == null) {
            return folderNode;
        }

        try {
            if ("jar".equals(resourceUrl.getProtocol())) {
                try (FileSystem fs = FileSystems.newFileSystem(resourceUrl.toURI(), Map.of())) {
                    Path dirPath = fs.getPath(resourcePath);
                    if (Files.isDirectory(dirPath)) {
                        Files.walk(dirPath)
                                .forEach(p -> {
                                    String relativePath = dirPath.relativize(p).toString();
                                    String fileName = p.getFileName().toString();
                                    if (Files.isDirectory(p)) {
                                        folderNode.set(fileName, buildJsonFromPath(resourcePath + "/" + relativePath));
                                    } else {
                                        String fullPath = resourcePath + "/" + relativePath;
                                        String content = readResourceAsString(fullPath);
                                        JsonNode fileContents = parseSingleFile(content, fileName);
                                        String baseName = getBaseFileName(fileName);
                                        folderNode.set(baseName, fileContents);
                                    }
                                });
                    } else {
                        String content = readResourceAsString(resourcePath);
                        String fileName = Path.of(resourcePath).getFileName().toString();
                        return parseSingleFile(content, fileName);
                    }
                }
            } else {
                Path path = Path.of(resourceUrl.toURI());
                if (Files.isDirectory(path)) {
                    Files.walk(path)
                            .forEach(p -> {
                                String relativePath = path.relativize(p).toString();
                                String fileName = p.getFileName().toString();
                                if (Files.isDirectory(p)) {
                                    folderNode.set(fileName, buildJsonFromPath(resourcePath + "/" + relativePath));
                                } else {
                                    try {
                                        String content = Files.readString(p, StandardCharsets.UTF_8);
                                        JsonNode fileContents = parseSingleFile(content, fileName);
                                        String baseName = getBaseFileName(fileName);
                                        folderNode.set(baseName, fileContents);
                                    } catch (IOException e) {
                                        System.out.println("Failed to read file: " + p);
                                    }
                                }
                            });
                } else {
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    String fileName = path.getFileName().toString();
                    return parseSingleFile(content, fileName);
                }
            }
        } catch (IOException | URISyntaxException e) {
            System.out.println("Failed to process resource: " + resourcePath);
        }
        return folderNode;
    }


    public static JsonNode parseSingleFile(String content, String fileName) {
        if (content == null || fileName == null) {
            return JSON_MAPPER.createObjectNode();
        }

        fileName = fileName.toLowerCase();
        if (fileName.endsWith(".xml")) {
            try {
                Object value = XML_MAPPER.readValue(content.strip(), Object.class);
                return JSON_MAPPER.valueToTree(value);
            } catch (IOException e) {
                System.out.println("XML parsing failed for " + fileName + ": " + e.getMessage());
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

        // If parsing fails, store the raw string content as a JsonNode
        return JSON_MAPPER.valueToTree(content);
    }

    private static String readResourceAsString(String path) {
        try (InputStream stream = GlobalCache.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new PickleballException("Resource not found at path: " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PickleballException("Failed to read resource at path: " + path, e);
        }
    }


    public static String getBaseFileName(String filePath) {
        String fileName = filePath.replace('\\', '/');
        int lastSlash = fileName.lastIndexOf('/');
        if (lastSlash >= 0) {
            fileName = fileName.substring(lastSlash + 1);
        }
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
