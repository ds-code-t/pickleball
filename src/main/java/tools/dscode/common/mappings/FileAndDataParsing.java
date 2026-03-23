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
import java.io.StringReader;
import java.io.UncheckedIOException;
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
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

public final class FileAndDataParsing {

    private FileAndDataParsing() {
    }

    private static final long MAX_CONFIG_FILE_SIZE_BYTES = 1L * 1024 * 1024;

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

    private static final class ParseFailureException extends RuntimeException {
        private ParseFailureException(String message, Throwable cause) {
            super(message, cause);
        }

        private ParseFailureException(String message) {
            super(message);
        }
    }

    public static JsonNode readResourceFile(String path) throws IOException {
        InputStream raw = getResourceStream(path);
        if (raw == null) {
            return null;
        }

        try (InputStream in = raw) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseSingleFile(content, path);
        } catch (ParseFailureException e) {
            throw new IOException(e.getMessage(), e);
        } catch (IOException e) {
            return null;
        }
    }

    public static JsonNode buildJsonFromPath(String resourcePath) {
        URL url = getResourceUrl(resourcePath);
        if (url == null) {
            return null;
        }

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
        } catch (ParseFailureException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonNode parseSingleFile(String content, String fileName) {
        if (fileName == null) {
            return null;
        }
        return parseDataString(content, fileName);
    }

    public static JsonNode parseDataString(String content, String name) {
        if (name == null || !hasMeaningfulContent(content, name)) {
            return null;
        }

        String type = getExtensionLower(name);

        switch (type) {
            case "csv":
                return parseCsv(content);

            case "xml":
                return parseXmlStrict(content, name);

            case "json":
                return parseJsonStrict(content, name);

            case "yaml":
            case "yml":
                return parseYamlStrict(content, name);

            case "properties":
                return parseProperties(content, name);

            case "ini":
            case "conf":
                return parseIniOrConf(content, name);

            case "txt":
                return JSON_MAPPER.valueToTree(content);

            default:
                try {
                    return JSON_MAPPER.readTree(content);
                } catch (IOException ignored) {
                }

                try {
                    return YAML_MAPPER.readTree(content);
                } catch (IOException ignored) {
                }

                return JSON_MAPPER.valueToTree(content);
        }
    }

    public static String getBaseFileName(String filePath) {
        String name = filePath.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return (dot > 0) ? name.substring(0, dot) : name;
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
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    private static JsonNode buildFromRoot(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return null;
        }

        if (!Files.isDirectory(root)) {
            String fileName = root.getFileName().toString();
            String ext = getExtensionLower(fileName);

            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                return null;
            }

            long size = Files.size(root);
            if (size <= 0 || size > MAX_CONFIG_FILE_SIZE_BYTES) {
                return null;
            }

            String content = Files.readString(root, StandardCharsets.UTF_8);
            return parseSingleFile(content, fileName);
        }

        ObjectNode dir = JSON_MAPPER.createObjectNode();

        try (Stream<Path> walk = Files.list(root)) {
            walk.sorted((a, b) -> {
                        int ci = String.CASE_INSENSITIVE_ORDER.compare(a.toString(), b.toString());
                        if (ci != 0) {
                            return ci;
                        }
                        return a.toString().compareTo(b.toString());
                    })
                    .forEach(p -> {
                        try {
                            if (Files.isDirectory(p)) {
                                String key = p.getFileName().toString();
                                JsonNode child = buildFromRoot(p);
                                if (child != null) {
                                    dir.set(key, child);
                                }
                            } else {
                                String fileName = p.getFileName().toString();
                                String ext = getExtensionLower(fileName);

                                if (!ALLOWED_EXTENSIONS.contains(ext)) {
                                    return;
                                }

                                long size = Files.size(p);
                                if (size <= 0 || size > MAX_CONFIG_FILE_SIZE_BYTES) {
                                    return;
                                }

                                String base = getBaseFileName(fileName);
                                String content = Files.readString(p, StandardCharsets.UTF_8);
                                JsonNode parsed = parseSingleFile(content, fileName);
                                if (parsed != null) {
                                    dir.set(base, parsed);
                                }
                            }
                        } catch (ParseFailureException e) {
                            throw e;
                        } catch (IOException ioe) {
                            throw new UncheckedIOException(ioe);
                        }
                    });
        }

        return dir.size() == 0 ? null : dir;
    }

    private static boolean hasMeaningfulContent(String content, String name) {
        if (content == null) {
            return false;
        }

        String type = getExtensionLower(name);
        switch (type) {
            case "yaml":
            case "yml":
                return hasMeaningfulYamlContent(content);

            case "xml":
                return hasMeaningfulXmlContent(content);

            case "properties":
                return hasMeaningfulPropertiesContent(content);

            case "ini":
            case "conf":
                return hasMeaningfulIniOrConfContent(content);

            default:
                return !content.isBlank();
        }
    }

    private static boolean hasMeaningfulYamlContent(String content) {
        return content.lines()
                .map(String::trim)
                .anyMatch(line ->
                        !line.isEmpty()
                                && !line.startsWith("#")
                                && !line.equals("---")
                                && !line.equals("..."));
    }

    private static boolean hasMeaningfulXmlContent(String content) {
        String normalized = content.replaceFirst("^\\uFEFF", "");
        normalized = normalized.replaceAll("(?s)<!--.*?-->", "");
        normalized = normalized.replaceAll("(?s)<\\?xml.*?\\?>", "");
        return !normalized.trim().isEmpty();
    }

    private static boolean hasMeaningfulPropertiesContent(String content) {
        return content.lines()
                .map(String::trim)
                .anyMatch(line ->
                        !line.isEmpty()
                                && !line.startsWith("#")
                                && !line.startsWith("!"));
    }

    private static boolean hasMeaningfulIniOrConfContent(String content) {
        return content.lines()
                .map(String::trim)
                .anyMatch(line ->
                        !line.isEmpty()
                                && !line.startsWith("#")
                                && !line.startsWith(";")
                                && !(line.startsWith("[") && line.endsWith("]")));
    }

    private static ParseFailureException parseFailure(String type, String name, Exception cause) {
        String detail = cause.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = cause.getClass().getSimpleName();
        }
        return new ParseFailureException(
                "Failed to parse " + type + " file '" + name + "': " + detail,
                cause
        );
    }

    private static ParseFailureException parseFailure(String type, String name, String detail) {
        return new ParseFailureException(
                "Failed to parse " + type + " file '" + name + "': " + detail
        );
    }

    private static JsonNode parseJsonStrict(String content, String name) {
        try {
            return JSON_MAPPER.readTree(content);
        } catch (IOException e) {
            throw parseFailure("JSON", name, e);
        }
    }

    private static JsonNode parseYamlStrict(String content, String name) {
        try {
            return YAML_MAPPER.readTree(content);
        } catch (IOException e) {
            throw parseFailure("YAML", name, e);
        }
    }

    private static JsonNode parseXmlStrict(String content, String name) {
        try {
            Object value = XML_MAPPER.readValue(content.strip(), Object.class);
            return value == null ? null : JSON_MAPPER.valueToTree(value);
        } catch (IOException e) {
            throw parseFailure("XML", name, e);
        }
    }

    private static JsonNode parseProperties(String content, String name) {
        Properties properties = new Properties();
        try (StringReader reader = new StringReader(content == null ? "" : content)) {
            properties.load(reader);
        } catch (IOException e) {
            throw parseFailure("properties", name, e);
        }

        if (properties.isEmpty()) {
            return null;
        }

        ObjectNode result = JSON_MAPPER.createObjectNode();
        properties.stringPropertyNames().stream()
                .sorted()
                .forEach(key -> result.put(key, properties.getProperty(key)));

        return result.size() == 0 ? null : result;
    }

    private static JsonNode parseIniOrConf(String content, String name) {
        ObjectNode result = JSON_MAPPER.createObjectNode();
        String currentSection = null;
        String[] lines = content.split("\\R", -1);

        for (int i = 0; i < lines.length; i++) {
            String rawLine = lines[i];
            String line = rawLine.trim();

            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }

            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1).trim();
                if (section.isEmpty()) {
                    throw parseFailure("INI/CONF", name, "empty section name at line " + (i + 1));
                }
                currentSection = section;
                continue;
            }

            int sep = findKeyValueSeparator(line);
            if (sep < 0) {
                throw parseFailure("INI/CONF", name, "missing '=' or ':' at line " + (i + 1));
            }

            String key = line.substring(0, sep).trim();
            String value = line.substring(sep + 1).trim();

            if (key.isEmpty()) {
                throw parseFailure("INI/CONF", name, "empty key at line " + (i + 1));
            }

            String fullKey = (currentSection == null || currentSection.isBlank())
                    ? key
                    : currentSection + "." + key;

            result.put(fullKey, value);
        }

        return result.size() == 0 ? null : result;
    }

    private static int findKeyValueSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');

        if (eq < 0) {
            return colon;
        }
        if (colon < 0) {
            return eq;
        }
        return Math.min(eq, colon);
    }

    private static JsonNode parseCsv(String content) {
        var result = JSON_MAPPER.createArrayNode();

        if (content == null || content.isBlank()) {
            return null;
        }

        String[] lines = content.split("\\R");
        java.util.List<String[]> rows = new java.util.ArrayList<>();

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] cells = java.util.Arrays.stream(trimmed.split(","))
                    .map(String::trim)
                    .toArray(String[]::new);
            rows.add(cells);
        }

        if (rows.isEmpty()) {
            return null;
        }

        String[] firstRow = rows.get(0);
        int columns = firstRow.length;

        if (columns == 1) {
            for (String[] row : rows) {
                String value = row.length > 0 ? row[0] : "";
                result.add(value);
            }
            return result.isEmpty() ? null : result;
        }

        String[] headers = firstRow;
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            var obj = JSON_MAPPER.createObjectNode();
            for (int c = 0; c < headers.length; c++) {
                String key = headers[c];
                if (key == null || key.isBlank()) {
                    key = "col_" + (c + 1);
                }
                String value = (c < row.length) ? row[c] : null;
                if (value != null) {
                    obj.put(key, value);
                } else {
                    obj.putNull(key);
                }
            }
            result.add(obj);
        }

        return result.size() == 0 ? null : result;
    }
}