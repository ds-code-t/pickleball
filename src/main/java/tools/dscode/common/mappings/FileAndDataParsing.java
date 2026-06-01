package tools.dscode.common.mappings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    private static final int BUILD_JSON_CACHE_MAX_SIZE = 256;
    private static final long BUILD_JSON_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(10);
    private static final JsonNode NULL_SENTINEL = NullNode.getInstance();
    private static final ConcurrentHashMap<String, CacheEntry> BUILD_JSON_CACHE = new ConcurrentHashMap<>();

    private static final String ROOT_PROP = "rootProp";

    private static final class CacheEntry {
        private final JsonNode value;
        private final long expiresAtNanos;

        private CacheEntry(JsonNode value, long expiresAtNanos) {
            this.value = value;
            this.expiresAtNanos = expiresAtNanos;
        }

        private boolean isExpired(long now) {
            return now >= expiresAtNanos;
        }
    }

    private static final class ParseFailureException extends RuntimeException {
        private ParseFailureException(String message, Throwable cause) {
            super(message, cause);
        }

        private ParseFailureException(String message) {
            super(message);
        }
    }

    private record PathSegment(String value, char separatorAfter) {
        boolean directoryRequired() {
            return separatorAfter == '/' || separatorAfter == '\\';
        }
    }

    private record ClasspathRoot(Path path, Closeable closeable) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (closeable != null) {
                closeable.close();
            }
        }
    }

    private record Candidate(Path path, boolean directory, String extension) {
    }

    private record SegmentName(String resourceName, String inlineDataPath) {
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
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        String key = resourcePath.trim();
        long now = System.nanoTime();

        CacheEntry entry = BUILD_JSON_CACHE.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                return existing;
            }

            JsonNode jsonNode = attemptBuildJsonFromPath(k);

            return new CacheEntry(
                    jsonNode == null ? NULL_SENTINEL : jsonNode.deepCopy(),
                    now + BUILD_JSON_CACHE_TTL_NANOS
            );
        });

        cleanupBuildJsonCache(now);
        return entry.value == NULL_SENTINEL ? null : entry.value.deepCopy();
    }

    private static void cleanupBuildJsonCache(long now) {
        if (BUILD_JSON_CACHE.size() <= BUILD_JSON_CACHE_MAX_SIZE) {
            return;
        }

        BUILD_JSON_CACHE.entrySet().removeIf(e -> e.getValue().isExpired(now));

        int overflow = BUILD_JSON_CACHE.size() - BUILD_JSON_CACHE_MAX_SIZE;
        if (overflow <= 0) {
            return;
        }

        var it = BUILD_JSON_CACHE.keySet().iterator();
        while (overflow-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public static JsonNode attemptBuildJsonFromPath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        List<PathSegment> segments = tokenizePath(resourcePath.trim());
        if (segments.isEmpty()) {
            return null;
        }

        for (ClasspathRoot root : getClasspathRoots()) {
            try (root) {
                JsonNode resolved = resolveFromDirectory(root.path(), segments, 0);
                if (resolved != null) {
                    return resolved;
                }
            } catch (ParseFailureException e) {
                throw e;
            } catch (Exception ignored) {
                // Try the next classpath root.
            }
        }

        return null;
    }

    /**
     * Kept for compatibility with any internal callers that still need exact classloader lookup.
     * buildJsonFromPath uses segment-by-segment lookup instead.
     */
    private static JsonNode buildJsonFromExactPath(String resourcePath) {
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

    private static JsonNode resolveFromDirectory(Path directory, List<PathSegment> segments, int index) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }

        if (index >= segments.size()) {
            return buildFromRoot(directory);
        }

        PathSegment segment = segments.get(index);
        SegmentName segmentName = splitResourceSegmentFromInlineData(segment.value());

        /*
         * If the next segment is not a normal resource segment, stop treating the
         * path as file-system/resource traversal. Build the JSON value for the
         * valid directory prefix and let NodeMap parse the remainder.
         */
        if (segmentName.resourceName().isBlank()) {
            JsonNode directoryValue = buildFromRoot(directory);
            return directoryValue == null
                    ? null
                    : getFromFileValueUsingNodeMap(directoryValue, buildRemainderPath("", segments, index));
        }

        List<Candidate> candidates = findCandidates(directory, segmentName.resourceName(), segment.directoryRequired());

        /*
         * If no child directory/file matches this segment, the current directory is
         * still a valid JSON prefix. Delegate the rest of the path to NodeMap. This
         * is what allows foreign syntax / JSONata-like syntax / unsupported bracket
         * syntax to be handled outside this class instead of making resource lookup fail.
         */
        if (candidates.isEmpty()) {
            JsonNode directoryValue = buildFromRoot(directory);
            return directoryValue == null
                    ? null
                    : getFromFileValueUsingNodeMap(directoryValue, buildRemainderPath("", segments, index));
        }

        for (Candidate candidate : candidates) {
            if (candidate.directory()) {
                JsonNode resolved = resolveDirectoryCandidate(candidate.path(), segmentName, segments, index);
                if (resolved != null) {
                    return resolved;
                }
                continue;
            }

            JsonNode resolved = resolveFileCandidate(candidate, segmentName, segment, segments, index);
            if (resolved != null) {
                return resolved;
            }
        }

        return null;
    }

    private static JsonNode resolveDirectoryCandidate(
            Path childDirectory,
            SegmentName segmentName,
            List<PathSegment> segments,
            int index
    ) throws IOException {
        JsonNode directoryValue = null;

        if (index == segments.size() - 1) {
            return buildFromRoot(childDirectory);
        }

        if (segmentName.inlineDataPath() != null && !segmentName.inlineDataPath().isBlank()) {
            directoryValue = buildFromRoot(childDirectory);
            String remainder = buildRemainderPath(segmentName.inlineDataPath(), segments, index + 1);
            return directoryValue == null ? null : getFromFileValueUsingNodeMap(directoryValue, remainder);
        }

        JsonNode child = resolveFromDirectory(childDirectory, segments, index + 1);
        if (child != null) {
            return child;
        }

        /*
         * The child directory was a valid prefix, but the following segment did not
         * resolve as another resource. Use the built directory ObjectNode and let
         * NodeMap try the remaining path.
         */
        directoryValue = buildFromRoot(childDirectory);
        String remainder = buildRemainderPath("", segments, index + 1);
        return directoryValue == null || remainder == null || remainder.isBlank()
                ? directoryValue
                : getFromFileValueUsingNodeMap(directoryValue, remainder);
    }

    private static JsonNode resolveFileCandidate(
            Candidate candidate,
            SegmentName segmentName,
            PathSegment originalSegment,
            List<PathSegment> segments,
            int index
    ) throws IOException {
        if (originalSegment.directoryRequired()) {
            return null;
        }

        JsonNode fileValue = buildFromRoot(candidate.path());
        if (fileValue == null) {
            return null;
        }

        int remainderStart = index + 1;
        String inlineRemainder = segmentName.inlineDataPath();

        /*
         * Allows explicit suffixes such as people.csv, people.csv[1], c.yaml,
         * or c.yaml.PropA to behave the same as people, people[1], c, or c.PropA.
         */
        if (remainderStart < segments.size()
                && originalSegment.separatorAfter() == '.'
                && candidate.extension() != null) {
            SegmentName extensionSegment = splitResourceSegmentFromInlineData(segments.get(remainderStart).value());
            if (extensionSegment.resourceName().equalsIgnoreCase(candidate.extension())) {
                inlineRemainder = appendRemainder(inlineRemainder, extensionSegment.inlineDataPath());
                remainderStart++;
            }
        }

        String remainder = buildRemainderPath(inlineRemainder, segments, remainderStart);
        if (remainder == null || remainder.isBlank()) {
            return fileValue;
        }

        return getFromFileValueUsingNodeMap(fileValue, remainder);
    }

    private static JsonNode getFromFileValueUsingNodeMap(JsonNode fileValue, String remainderPath) {
        if (fileValue == null) {
            return null;
        }

        ObjectNode wrapper = JSON_MAPPER.createObjectNode();
        wrapper.set(ROOT_PROP, fileValue);

        Object value = new NodeMap(wrapper).get(ROOT_PROP + normalizeRemainderForNodeMap(remainderPath));
        return value == null ? null : JSON_MAPPER.valueToTree(value);
    }

    private static String normalizeRemainderForNodeMap(String remainderPath) {
        if (remainderPath == null || remainderPath.isBlank()) {
            return "";
        }

        String trimmed = remainderPath.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("(")) {
            return trimmed;
        }

        if (trimmed.startsWith(".")) {
            return trimmed;
        }

        return "." + trimmed;
    }

    private static String buildRemainderPath(String inlineDataPath, List<PathSegment> segments, int startIndex) {
        StringBuilder out = new StringBuilder();

        if (inlineDataPath != null && !inlineDataPath.isBlank()) {
            out.append(inlineDataPath);
        }

        for (int i = startIndex; i < segments.size(); i++) {
            if (out.length() > 0 && !endsWithPathJoiner(out)) {
                out.append('.');
            }
            out.append(segments.get(i).value());
        }

        return out.toString();
    }

    private static String appendRemainder(String left, String right) {
        if (right == null || right.isBlank()) {
            return left == null ? "" : left;
        }
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right.startsWith("[") || right.startsWith("(") || right.startsWith(".")) {
            return left + right;
        }
        return left + "." + right;
    }

    private static boolean endsWithPathJoiner(StringBuilder out) {
        if (out.isEmpty()) {
            return false;
        }
        char ch = out.charAt(out.length() - 1);
        return ch == '.' || ch == '[' || ch == '(';
    }

    private static SegmentName splitResourceSegmentFromInlineData(String rawSegment) {
        if (rawSegment == null) {
            return new SegmentName("", "");
        }

        String segment = rawSegment.trim();
        if (segment.isEmpty()) {
            return new SegmentName("", "");
        }

        int dataSyntax = firstDataSyntaxIndex(segment);
        if (dataSyntax >= 0) {
            return new SegmentName(stripAllowedExtension(segment.substring(0, dataSyntax)), segment.substring(dataSyntax));
        }

        return new SegmentName(stripAllowedExtension(segment), "");
    }

    private static int firstDataSyntaxIndex(String segment) {
        int first = -1;
        char[] special = {'[', '(', ')', '*', '#', '<', '>', '?'};

        for (char ch : special) {
            int idx = segment.indexOf(ch);
            if (idx >= 0 && (first < 0 || idx < first)) {
                first = idx;
            }
        }

        return first;
    }

    private static List<PathSegment> tokenizePath(String resourcePath) {
        List<PathSegment> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        int squareDepth = 0;
        int parenDepth = 0;

        for (int i = 0; i < resourcePath.length(); i++) {
            char ch = resourcePath.charAt(i);

            if (ch == '[') {
                squareDepth++;
                current.append(ch);
                continue;
            }
            if (ch == ']' && squareDepth > 0) {
                squareDepth--;
                current.append(ch);
                continue;
            }
            if (ch == '(') {
                parenDepth++;
                current.append(ch);
                continue;
            }
            if (ch == ')' && parenDepth > 0) {
                parenDepth--;
                current.append(ch);
                continue;
            }

            if ((ch == '/' || ch == '\\' || ch == '.') && squareDepth == 0 && parenDepth == 0) {
                if (!current.isEmpty()) {
                    out.add(new PathSegment(current.toString(), ch));
                    current.setLength(0);
                }
                continue;
            }

            current.append(ch);
        }

        if (!current.isEmpty()) {
            out.add(new PathSegment(current.toString(), '\0'));
        }

        return out;
    }

    private static List<Candidate> findCandidates(Path directory, String requestedSegment, boolean directoryOnly) throws IOException {
        String requestedBase = stripAllowedExtension(requestedSegment);

        List<Path> children;
        try (Stream<Path> stream = Files.list(directory)) {
            children = stream
                    .sorted(Comparator
                            .comparing((Path p) -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList();
        }

        List<Candidate> exact = matchingCandidates(children, requestedBase, directoryOnly, true);
        if (!exact.isEmpty()) {
            return exact;
        }

        return matchingCandidates(children, requestedBase, directoryOnly, false);
    }

    private static List<Candidate> matchingCandidates(
            List<Path> children,
            String requestedBase,
            boolean directoryOnly,
            boolean exact
    ) {
        List<Candidate> matches = new ArrayList<>();

        for (Path child : children) {
            Candidate candidate = toCandidate(child);
            if (candidate == null) {
                continue;
            }

            if (directoryOnly && !candidate.directory()) {
                continue;
            }

            String childBase = baseNameIgnoringAllowedExtension(child.getFileName().toString());
            boolean nameMatches = exact
                    ? childBase.equals(requestedBase)
                    : childBase.equalsIgnoreCase(requestedBase);

            if (nameMatches) {
                matches.add(candidate);
            }
        }

        // File first unless the caller explicitly required a directory.
        matches.sort(Comparator
                .comparing(Candidate::directory)
                .thenComparing(c -> c.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(c -> c.path().getFileName().toString()));

        return matches;
    }

    private static Candidate toCandidate(Path path) {
        try {
            if (Files.isDirectory(path)) {
                return new Candidate(path, true, null);
            }

            if (!Files.isRegularFile(path)) {
                return null;
            }

            String extension = getExtensionLower(path.getFileName().toString());
            if (!ALLOWED_EXTENSIONS.contains(extension)) {
                return null;
            }

            return new Candidate(path, false, extension);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String baseNameIgnoringAllowedExtension(String fileName) {
        String normalized = fileName == null ? "" : fileName.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }

        return stripAllowedExtension(normalized);
    }

    private static String stripAllowedExtension(String text) {
        if (text == null) {
            return "";
        }

        int dot = text.lastIndexOf('.');
        if (dot > 0 && dot < text.length() - 1) {
            String ext = text.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ALLOWED_EXTENSIONS.contains(ext)) {
                return text.substring(0, dot);
            }
        }

        return text;
    }

    private static List<ClasspathRoot> getClasspathRoots() {
        List<ClasspathRoot> roots = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();

        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        try {
            Enumeration<URL> rootUrls = loader.getResources("");
            while (rootUrls.hasMoreElements()) {
                URL url = rootUrls.nextElement();
                if (!"file".equalsIgnoreCase(url.getProtocol())) {
                    continue;
                }

                String decoded = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8);
                addClasspathRoot(roots, seen, Paths.get(decoded), null);
            }
        } catch (Exception ignored) {
        }

        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank()) {
            for (String entry : classPath.split(File.pathSeparator)) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }

                try {
                    Path path = Paths.get(entry);
                    if (Files.isDirectory(path)) {
                        addClasspathRoot(roots, seen, path, null);
                    } else if (Files.isRegularFile(path) && entry.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                        ClasspathRoot jarRoot = openJarClasspathRoot(path);
                        if (jarRoot != null) {
                            addClasspathRoot(roots, seen, jarRoot.path(), jarRoot.closeable());
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return roots;
    }

    private static void addClasspathRoot(List<ClasspathRoot> roots, HashSet<String> seen, Path path, Closeable closeable) {
        if (path == null) {
            closeQuietly(closeable);
            return;
        }

        String key = path.toAbsolutePath().normalize().toString();
        if (seen.add(key)) {
            roots.add(new ClasspathRoot(path, closeable));
        } else {
            closeQuietly(closeable);
        }
    }

    private static ClasspathRoot openJarClasspathRoot(Path jarPath) {
        try {
            URI uri = URI.create("jar:" + jarPath.toUri());

            FileSystem fs;
            boolean shouldClose;
            try {
                fs = FileSystems.newFileSystem(uri, Map.of());
                shouldClose = true;
            } catch (FileSystemAlreadyExistsException alreadyExists) {
                fs = FileSystems.getFileSystem(uri);
                shouldClose = false;
            }

            FileSystem finalFs = fs;
            Closeable closeable = shouldClose ? finalFs::close : null;
            return new ClasspathRoot(fs.getPath("/"), closeable);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }

        try {
            closeable.close();
        } catch (IOException ignored) {
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