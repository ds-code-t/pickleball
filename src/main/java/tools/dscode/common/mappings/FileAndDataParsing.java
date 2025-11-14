package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class FileAndDataParsing {

    private FileAndDataParsing() {
    }

    // ---- Configurable parameters ------------------------------------------------

    // Max size of a config/data file to process (1 MB here; tweak as needed)
    private static final long MAX_CONFIG_FILE_SIZE_BYTES = 1L * 1024 * 1024;

    // Only these extensions are considered part of the config/data mapping
    // (lowercase, without the dot)
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "json",
            "yaml",
            "yml",
            "xml",
            "properties",
            "ini",
            "conf",
            "txt",
            "csv"
    );


    // ---- Public API -------------------------------------------------------------

    /**
     * Reads a single resource file (json/yaml/xml) from classpath into a JsonNode.
     */
    public static JsonNode readResourceFile(String path) throws Exception {
        try (InputStream in = getResourceStream(path)) {
            if (in == null)
                throw new Exception("Resource not found: " + path);
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseSingleFile(content, path);
        } catch (Exception e) {
            throw new Exception("Failed to read resource: " + path, e);
        }
    }

    /**
     * Builds a nested Json structure from a classpath resource folder or file.
     */
    public static JsonNode buildJsonFromPath(String resourcePath) {
        URL url = getResourceUrl(resourcePath);
        if (url == null)
            return JSON_MAPPER.createObjectNode();

        try {
            if ("jar".equals(url.getProtocol())) {
                try (FileSystem fs = FileSystems.newFileSystem(toJarUri(url), Map.of())) {
                    Path root = fs.getPath(resourcePath);
                    return buildFromRoot(root);
                }
            } else {
                Path root = Paths.get(url.toURI());
                return buildFromRoot(root);
            }
        } catch (Exception e) {
            System.out.println("Failed to process resource: " + resourcePath + " (" +
                    e.getMessage() + ")");
            return JSON_MAPPER.createObjectNode();
        }
    }

    // ---- Parsing ----------------------------------------------------------------

    public static JsonNode parseSingleFile(String content, String fileName) {
        if (content == null || fileName == null)
            return JSON_MAPPER.createObjectNode();

        String lower = fileName.toLowerCase();

        if (lower.endsWith(".csv")) {
            return parseCsv(content);
        }

        if (lower.endsWith(".xml")) {
            try {
                Object value = XML_MAPPER.readValue(content.strip(), Object.class);
                return JSON_MAPPER.valueToTree(value);
            } catch (IOException e) {
                System.out.println("XML parsing failed for " + fileName + ": " + e.getMessage());
            }
        } else {
            try {
                return JSON_MAPPER.readTree(content);
            } catch (IOException ignored) { }

            try {
                return YAML_MAPPER.readTree(content);
            } catch (IOException ignored) { }
        }

        // As-is text fallback
        return JSON_MAPPER.valueToTree(content);
    }


    public static String getBaseFileName(String filePath) {
        String name = filePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0)
            name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
    }

    // ---- ObjectMappers ----------------------------------------------------------

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

    // ---- Internal helpers -------------------------------------------------------

    private static URL getResourceUrl(String path) {
        return Thread.currentThread().getContextClassLoader().getResource(path);
    }

    private static InputStream getResourceStream(String path) {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
    }

    private static URI toJarUri(URL url) throws Exception {
        String u = Objects.requireNonNull(url).toString();
        int bang = u.indexOf("!/");
        String jar = (bang >= 0) ? u.substring(0, bang) : u;
        return URI.create(jar);
    }

    private static String getExtensionLower(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1)
            return "";
        return fileName.substring(dot + 1).toLowerCase();
    }

    /**
     * Build nested Json from a Path that may be a single file or a directory.
     * Only processes files with allowed extensions and within size limit.
     */
    private static JsonNode buildFromRoot(Path root) throws IOException {
        if (!Files.exists(root))
            return JSON_MAPPER.createObjectNode();

        if (!Files.isDirectory(root)) {
            String fileName = root.getFileName().toString();
            String ext = getExtensionLower(fileName);
            if (!ALLOWED_EXTENSIONS.contains(ext))
                return JSON_MAPPER.createObjectNode();

            long size = Files.size(root);
            if (size > MAX_CONFIG_FILE_SIZE_BYTES) {
                System.out.println("Skipping file (too large): " + root + " (" + size + " bytes)");
                return JSON_MAPPER.createObjectNode();
            }

            String content = Files.readString(root, StandardCharsets.UTF_8);
            return parseSingleFile(content, fileName);
        }

        ObjectNode dir = JSON_MAPPER.createObjectNode();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(p -> {
                try {
                    if (Files.isDirectory(p)) {
                        if (p.equals(root))
                            return;
                        String key = p.getFileName().toString();
                        dir.set(key, buildFromRoot(p));
                    } else {
                        String fileName = p.getFileName().toString();
                        String ext = getExtensionLower(fileName);
                        if (!ALLOWED_EXTENSIONS.contains(ext))
                            return;

                        long size = Files.size(p);
                        if (size > MAX_CONFIG_FILE_SIZE_BYTES) {
                            System.out.println("Skipping file (too large): " + p + " (" + size + " bytes)");
                            return;
                        }

                        String base = getBaseFileName(fileName);
                        String content = Files.readString(p, StandardCharsets.UTF_8);
                        JsonNode parsed = parseSingleFile(content, fileName);
                        dir.set(base, parsed); // base-name collisions will overwrite
                    }
                } catch (IOException ioe) {
                    System.out.println("Failed to read file: " + p + " (" + ioe.getMessage() + ")");
                }
            });
        }
        return dir;
    }

    private static JsonNode parseCsv(String content) {
        var result = JSON_MAPPER.createArrayNode();
        if (content == null || content.isBlank())
            return result;

        String[] lines = content.split("\\R");
        // collect non-empty trimmed lines
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty())
                continue;
            String[] cells = java.util.Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .toArray(String[]::new);
            rows.add(cells);
        }

        if (rows.isEmpty())
            return result;

        String[] firstRow = rows.get(0);
        int columns = firstRow.length;

        // Single-column CSV → ["a","b","c"]
        if (columns == 1) {
            for (String[] row : rows) {
                String value = row.length > 0 ? row[0] : "";
                result.add(value);
            }
            return result;
        }

        // Multi-column CSV → array of objects, first row = headers
        String[] headers = firstRow;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            var obj = JSON_MAPPER.createObjectNode();
            for (int c = 0; c < headers.length; c++) {
                String key = headers[c];
                if (key == null || key.isBlank())
                    key = "col_" + (c + 1);
                String value = (c < row.length) ? row[c] : null;
                if (value != null)
                    obj.put(key, value);
                else
                    obj.putNull(key);
            }
            result.add(obj);
        }

        return result;
    }

}
