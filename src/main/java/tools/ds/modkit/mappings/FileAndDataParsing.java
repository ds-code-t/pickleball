package tools.ds.modkit.mappings;


import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

public final class FileAndDataParsing {

    private FileAndDataParsing() {}

    // ---- Public API -----------------------------------------------------------

    /** Reads a single resource file (json/yaml/xml) from classpath into a JsonNode. */
    public static JsonNode readResourceFile(String path) throws Exception {
        try (InputStream in = getResourceStream(path)) {
            if (in == null) throw new Exception("Resource not found: " + path);
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseSingleFile(content, path);
        } catch (Exception e) {
            throw new Exception("Failed to read resource: " + path, e);
        }
    }

    /** Builds a nested Json structure from a classpath resource folder or file. */
    public static JsonNode buildJsonFromPath(String resourcePath) {
        URL url = getResourceUrl(resourcePath);
        if (url == null) return JSON_MAPPER.createObjectNode();

        try {
            if ("jar".equals(url.getProtocol())) {
                // Mount once, reuse.
                try (FileSystem fs = FileSystems.newFileSystem(toJarUri(url), Map.of())) {
                    Path root = fs.getPath(resourcePath);
                    return buildFromRoot(root);
                }
            } else {
                Path root = Paths.get(url.toURI());
                return buildFromRoot(root);
            }
        } catch (Exception e) {
            // keep original behavior: be forgiving, return empty object on failure
            System.out.println("Failed to process resource: " + resourcePath + " (" + e.getMessage() + ")");
            return JSON_MAPPER.createObjectNode();
        }
    }

    // ---- Parsing --------------------------------------------------------------

    public static JsonNode parseSingleFile(String content, String fileName) {
        if (content == null || fileName == null) return JSON_MAPPER.createObjectNode();

        String lower = fileName.toLowerCase();

        if (lower.endsWith(".xml")) {
            try {
                Object value = XML_MAPPER.readValue(content.strip(), Object.class);
                return JSON_MAPPER.valueToTree(value);
            } catch (IOException e) {
                System.out.println("XML parsing failed for " + fileName + ": " + e.getMessage());
                // fall through to store raw text, to preserve current behavior
            }
        } else {
            try {
                return JSON_MAPPER.readTree(content);
            } catch (IOException ignored) { /* try YAML next */ }

            try {
                return YAML_MAPPER.readTree(content);
            } catch (IOException ignored) { /* store raw below */ }
        }

        // As-is text fallback
        return JSON_MAPPER.valueToTree(content);
    }

    public static String getBaseFileName(String filePath) {
        String name = filePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    // ---- JsonPath configuration ----------------------------------------------

    public static final Configuration VALUE_CONFIG = Configuration.builder()
            .mappingProvider(new JacksonMappingProvider())
            .jsonProvider(new JacksonJsonNodeJsonProvider())
            .options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
            .build();

    // ---- ObjectMappers --------------------------------------------------------

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

    // ---- Internal helpers -----------------------------------------------------

    private static URL getResourceUrl(String path) {
        return Thread.currentThread().getContextClassLoader().getResource(path);
    }

    private static InputStream getResourceStream(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }

    private static URI toJarUri(URL url) throws Exception {
        // Convert "jar:file:/...!/entry" URL to "jar:file:/..." FS root
        String u = Objects.requireNonNull(url).toString();
        int bang = u.indexOf("!/");
        String jar = (bang >= 0) ? u.substring(0, bang) : u;
        return URI.create(jar);
    }

    /** Build nested Json from a Path that may be a single file or a directory. */
    private static JsonNode buildFromRoot(Path root) throws IOException {
        if (!Files.exists(root)) return JSON_MAPPER.createObjectNode();

        if (!Files.isDirectory(root)) {
            String content = Files.readString(root, StandardCharsets.UTF_8);
            return parseSingleFile(content, root.getFileName().toString());
        }

        ObjectNode dir = JSON_MAPPER.createObjectNode();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(p -> {
                try {
                    if (Files.isDirectory(p)) {
                        if (p.equals(root)) return; // skip the root itself
                        String key = p.getFileName().toString(); // keep folder name as-is
                        dir.set(key, buildFromRoot(p));          // recurse without remounting FS
                    } else {
                        String fileName = p.getFileName().toString();
                        String base = getBaseFileName(fileName);
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        JsonNode parsed = parseSingleFile(content, fileName);
                        dir.set(base, parsed); // NOTE: base-name collisions will overwrite
                    }
                } catch (IOException ioe) {
                    System.out.println("Failed to read file: " + p + " (" + ioe.getMessage() + ")");
                }
            });
        }
        return dir;
    }
}
