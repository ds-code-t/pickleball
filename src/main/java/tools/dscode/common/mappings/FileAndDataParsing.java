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

    // TEMPORARY DEBUG: remove these before committing.
    // Enabled by default in this debug build. You can disable with:
    // -DFileAndDataParsing.debug=false
    private static final boolean DEBUG_BUILD_JSON = Boolean.parseBoolean(
            System.getProperty("FileAndDataParsing.debug", "true")
    );

    private static void debug(String message) {
        if (DEBUG_BUILD_JSON) {
            System.out.println("@@FileAndDataParsing " + message);
        }
    }

    private static String brief(JsonNode node) {
        if (node == null) {
            return "null";
        }
        String type = node.getNodeType().toString();
        String size = node.isContainerNode() ? ",size=" + node.size() : "";
        String text = node.toString();
        if (text.length() > 240) {
            text = text.substring(0, 240) + "...";
        }
        return type + size + ":" + text;
    }

    private static String briefObject(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        if (text.length() > 240) {
            text = text.substring(0, 240) + "...";
        }
        return value.getClass().getName() + ":" + text;
    }

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
        debug("buildJsonFromPath input='" + resourcePath + "' key='" + key + "'");
        long now = System.nanoTime();

        CacheEntry entry = BUILD_JSON_CACHE.compute(key, (k, existing) -> {
            if (existing != null && !existing.isExpired(now)) {
                debug("cache HIT key='" + k + "' value=" + brief(existing.value));
                return existing;
            }

            JsonNode jsonNode = attemptBuildJsonFromPath(k);
            debug("resolved key='" + k + "' value=" + brief(jsonNode));

            // Do not cache nulls in this debug build. A pre-resolution pass can otherwise
            // hide the useful split/query diagnostics during the phrase execution.
            if (jsonNode == null) {
                return null;
            }

            return new CacheEntry(jsonNode.deepCopy(), now + BUILD_JSON_CACHE_TTL_NANOS);
        });

        cleanupBuildJsonCache(now);
        JsonNode returned = entry == null ? null : entry.value.deepCopy();
        debug("return key='" + key + "' value=" + brief(returned));
        return returned;
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

    private record PrefixMatch(JsonNode value, String remainder) {
    }

    public static JsonNode attemptBuildJsonFromPath(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        String input = resourcePath.trim();
        debug("attemptBuildJsonFromPath input='" + input + "'");

        List<ClasspathRoot> roots = getClasspathRoots();
        for (ClasspathRoot root : roots) {
            try (root) {
                PrefixMatch match = resolveBestResourcePrefix(root.path(), input);
                if (match == null || match.value() == null) {
                    continue;
                }

                if (match.remainder() == null || match.remainder().isBlank()) {
                    return match.value();
                }

                JsonNode selected = getFromValueUsingNodeMap(match.value(), match.remainder());
                return selected;
            } catch (ParseFailureException e) {
                throw e;
            } catch (Exception ex) {
                // Try the next classpath root.
            }
        }

        debug("attemptBuildJsonFromPath no match input='" + input + "'");
        return null;
    }

    /**
     * Finds the best leading resource prefix. If a path segment contains inline
     * Tokenized/JSONata syntax, the preferred split is before that whole segment.
     * Example:
     *   data_test/CsvTests/people[0..1].name as:LIST
     * becomes:
     *   resource prefix = data_test/CsvTests/
     *   remainder       = people[0..1].name as:LIST
     */
    private static PrefixMatch resolveBestResourcePrefix(Path classpathRoot, String input) throws IOException {
        if (classpathRoot == null || !Files.isDirectory(classpathRoot) || input == null || input.isBlank()) {
            return null;
        }

        List<Integer> cuts = prefixCutPositions(input);
        debug("split candidates input='" + input + "' cuts=" + describeCuts(input, cuts));

        for (int cut : cuts) {
            String prefix = input.substring(0, cut);
            String remainder = input.substring(cut);
            if (prefix.isBlank()) {
                continue;
            }

            JsonNode value = resolveResourceOnly(classpathRoot, prefix);
            if (value != null) {
                debug("selected split prefix='" + prefix + "' remainder='" + remainder + "' value=" + brief(value));
                return new PrefixMatch(value, remainder);
            }
        }

        debug("no resource split matched input='" + input + "'");
        return null;
    }

    /**
     * Candidate prefix cuts. A forced cut is tried first when syntax belongs to a
     * path segment instead of being a real file/directory name.
     */
    private static List<Integer> prefixCutPositions(String input) {
        int boundary = firstSpecialSyntaxBoundary(input);
        int scanEnd = boundary >= 0 ? boundary : input.length();

        List<Integer> cuts = new ArrayList<>();
        Integer forcedCut = forcedDirectoryPrefixCutBeforeInlineDataSegment(input, boundary);
        if (forcedCut != null) {
            addCut(cuts, forcedCut);
        }

        addCut(cuts, trimTrailingDots(input, scanEnd));

        int squareDepth = 0;
        int parenDepth = 0;
        int braceDepth = 0;
        boolean inBackticks = false;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < scanEnd; i++) {
            char ch = input.charAt(i);
            char prev = i > 0 ? input.charAt(i - 1) : '\0';

            if (ch == '`' && !inSingleQuote && !inDoubleQuote) {
                inBackticks = !inBackticks;
                continue;
            }
            if (ch == '\'' && !inBackticks && !inDoubleQuote && prev != '\\') {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inBackticks && !inSingleQuote && prev != '\\') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (inBackticks || inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (ch == '[') {
                squareDepth++;
                continue;
            }
            if (ch == ']' && squareDepth > 0) {
                squareDepth--;
                continue;
            }
            if (ch == '(') {
                parenDepth++;
                continue;
            }
            if (ch == ')' && parenDepth > 0) {
                parenDepth--;
                continue;
            }
            if (ch == '{') {
                braceDepth++;
                continue;
            }
            if (ch == '}' && braceDepth > 0) {
                braceDepth--;
                continue;
            }

            if (squareDepth == 0 && parenDepth == 0 && braceDepth == 0
                    && (ch == '/' || ch == '\\' || ch == '.')
                    && !(ch == '.' && i + 1 < input.length() && input.charAt(i + 1) == '.')
                    && !(ch == '.' && i > 0 && input.charAt(i - 1) == '.')) {
                addCut(cuts, i + ((ch == '/' || ch == '\\') && i < scanEnd ? 1 : 0));
            }
        }

        List<Integer> forced = cuts.isEmpty() ? List.of() : List.of(cuts.getFirst());
        List<Integer> remaining = cuts.stream()
                .filter(i -> i > 0)
                .distinct()
                .filter(i -> forced.isEmpty() || !i.equals(forced.getFirst()))
                .sorted(Comparator.reverseOrder())
                .toList();

        List<Integer> result = new ArrayList<>();
        if (!forced.isEmpty() && forced.getFirst() > 0) {
            result.add(forced.getFirst());
        }
        result.addAll(remaining);
        return result.stream().filter(i -> i > 0).distinct().toList();
    }

    private static Integer forcedDirectoryPrefixCutBeforeInlineDataSegment(String input, int boundary) {
        if (input == null || boundary < 0 || boundary >= input.length()) {
            return null;
        }

        int segmentStart = lastResourceSeparatorBefore(input, boundary) + 1;
        if (segmentStart <= 0) {
            return null;
        }

        String segmentRemainder = input.substring(segmentStart);
        if (!segmentHasInlineDataSyntax(segmentRemainder)) {
            return null;
        }

        debug("forced split before inline syntax segment='" + segmentRemainder
                + "' prefix='" + input.substring(0, segmentStart)
                + "' remainder='" + input.substring(segmentStart) + "'");
        return segmentStart;
    }

    private static int lastResourceSeparatorBefore(String input, int beforeIndex) {
        int slash = input.lastIndexOf('/', beforeIndex);
        int backslash = input.lastIndexOf('\\', beforeIndex);
        return Math.max(slash, backslash);
    }

    private static boolean segmentHasInlineDataSyntax(String segmentRemainder) {
        if (segmentRemainder == null || segmentRemainder.isBlank()) {
            return false;
        }

        // Complex bracket/index syntax should be handled by NodeMap, not resource lookup.
        if (segmentRemainder.matches(".*\\[[^\\]]*(?:\\.\\.|,)[^\\]]*].*")) {
            return true;
        }

        // One-based # range/comma syntax should be handled by Tokenized.rewrite.
        if (segmentRemainder.matches(".*#\\d+(?:\\.\\.|,)\\d+.*")) {
            return true;
        }

        // Wildcards, projections, filters, and suffixes are data-query syntax.
        return segmentRemainder.contains(".*")
                || segmentRemainder.contains("*")
                || segmentRemainder.contains("{")
                || segmentRemainder.contains("}")
                || segmentRemainder.contains("?")
                || segmentRemainder.contains("(")
                || segmentRemainder.contains(")")
                || segmentRemainder.contains(" as:")
                || segmentRemainder.contains(" as-");
    }

    private static String describeCuts(String input, List<Integer> cuts) {
        if (cuts == null || cuts.isEmpty()) {
            return "[]";
        }
        return cuts.stream()
                .map(cut -> cut + "=('" + input.substring(0, cut) + "' | '" + input.substring(cut) + "')")
                .toList()
                .toString();
    }

    private static void addCut(List<Integer> cuts, int cut) {
        if (cut > 0 && !cuts.contains(cut)) {
            cuts.add(cut);
        }
    }

    private static int trimTrailingDots(String input, int end) {
        int cut = Math.min(end, input.length());
        while (cut > 0 && input.charAt(cut - 1) == '.') {
            cut--;
        }
        return cut;
    }

    private static int firstSpecialSyntaxBoundary(String input) {
        int squareDepth = 0;
        int parenDepth = 0;
        int braceDepth = 0;
        boolean inBackticks = false;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            char prev = i > 0 ? input.charAt(i - 1) : '\0';

            if (ch == '`' && !inSingleQuote && !inDoubleQuote) {
                inBackticks = !inBackticks;
                continue;
            }
            if (ch == '\'' && !inBackticks && !inDoubleQuote && prev != '\\') {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (ch == '"' && !inBackticks && !inSingleQuote && prev != '\\') {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (inBackticks || inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (ch == '[') {
                return i;
            }
            if (ch == '(' || ch == ')' || ch == '*' || ch == '#' || ch == '<' || ch == '>'
                    || ch == '?' || ch == '{' || ch == '}' || ch == '=' || ch == '%'
                    || ch == ':' || Character.isWhitespace(ch)) {
                return i;
            }
            if (ch == '.' && i + 1 < input.length() && input.charAt(i + 1) == '.') {
                return i;
            }

            if (ch == ']') {
                squareDepth = Math.max(0, squareDepth - 1);
            } else if (ch == ')') {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (ch == '}') {
                braceDepth = Math.max(0, braceDepth - 1);
            }
        }

        return -1;
    }

    private static JsonNode resolveResourceOnly(Path root, String resourcePath) throws IOException {
        List<PathSegment> segments = tokenizeResourcePath(resourcePath);
        if (segments.isEmpty()) {
            return null;
        }
        JsonNode result = resolveResourceOnly(root, segments, 0);
        return result;
    }

    private static JsonNode resolveResourceOnly(Path directory, List<PathSegment> segments, int index) throws IOException {
        if (directory == null || !Files.isDirectory(directory) || index >= segments.size()) {
            return null;
        }

        PathSegment segment = segments.get(index);
        List<Candidate> candidates = findCandidates(directory, segment.value(), segment.directoryRequired());
        if (candidates.isEmpty()) {
            return null;
        }

        for (Candidate candidate : candidates) {
            if (candidate.directory()) {
                if (index == segments.size() - 1) {
                    JsonNode built = buildFromRoot(candidate.path());
                    return built;
                }

                JsonNode child = resolveResourceOnly(candidate.path(), segments, index + 1);
                if (child != null) {
                    return child;
                }
                continue;
            }

            if (segment.directoryRequired()) {
                continue;
            }

            int consumedIndex = consumeOptionalExplicitExtension(candidate, segment, segments, index);
            if (consumedIndex == segments.size() - 1) {
                JsonNode built = buildFromRoot(candidate.path());
                return built;
            }
        }

        return null;
    }

    private static int consumeOptionalExplicitExtension(
            Candidate candidate,
            PathSegment fileSegment,
            List<PathSegment> segments,
            int index
    ) {
        int nextIndex = index + 1;
        if (candidate.extension() == null || nextIndex >= segments.size() || fileSegment.separatorAfter() != '.') {
            return index;
        }

        PathSegment next = segments.get(nextIndex);
        return next.value().equalsIgnoreCase(candidate.extension()) ? nextIndex : index;
    }

    private static List<PathSegment> tokenizeResourcePath(String resourcePath) {
        List<PathSegment> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < resourcePath.length(); i++) {
            char ch = resourcePath.charAt(i);
            if (ch == '/' || ch == '\\' || ch == '.') {
                if (!current.isEmpty()) {
                    out.add(new PathSegment(stripAllowedExtension(current.toString()), ch));
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }

        if (!current.isEmpty()) {
            out.add(new PathSegment(stripAllowedExtension(current.toString()), '\0'));
        }

        return out;
    }

    private static JsonNode getFromValueUsingNodeMap(JsonNode value, String remainderPath) {
        if (value == null) {
            return null;
        }

        ObjectNode wrapper = JSON_MAPPER.createObjectNode();
        wrapper.set(ROOT_PROP, value);

        String nodeMapQuery = joinRootPropAndRemainder(value, remainderPath);
        debug("NodeMap remainder='" + remainderPath + "' query='" + nodeMapQuery + "'");
        Object selected = new NodeMap(wrapper).get(nodeMapQuery);
        debug("NodeMap raw selected=" + briefObject(selected));
        JsonNode result = selected == null ? null : JSON_MAPPER.valueToTree(selected);
        debug("NodeMap result JsonNode=" + brief(result));
        return result;
    }

    private static String joinRootPropAndRemainder(JsonNode value, String remainderPath) {
        if (remainderPath == null || remainderPath.isBlank()) {
            return ROOT_PROP;
        }

        String r = remainderPath.stripLeading();
        String joined;
        if (value.isArray() && r.startsWith(".*")) {
            joined = ROOT_PROP + "[*]" + r.substring(2);
        } else if (value.isArray() && r.startsWith("*")) {
            joined = ROOT_PROP + "[*]" + r.substring(1);
        } else if (r.startsWith(".")
                || r.startsWith("[")
                || r.startsWith("#")
                || r.startsWith("(")
                || r.startsWith("{")) {
            joined = ROOT_PROP + r;
        } else {
            joined = ROOT_PROP + "." + r;
        }

        String normalized = normalizeArrayDotWildcards(value, joined);
        if (!joined.equals(normalized)) {
            debug("normalized array wildcard query='" + joined + "' -> '" + normalized + "'");
        }
        return normalized;
    }

    /**
     * Converts only dot-wildcards that follow an actual ArrayNode to [*].
     * This keeps object wildcard queries such as rootProp.* as object wildcards,
     * while fixing rootProp.items.*.name when rootProp.items is an array.
     */
    private static String normalizeArrayDotWildcards(JsonNode rootValue, String query) {
        if (rootValue == null || query == null || query.isBlank() || !query.contains(".*")) {
            return query;
        }

        StringBuilder out = new StringBuilder(query);
        int searchFrom = 0;
        while (searchFrom < out.length()) {
            int dotStar = out.indexOf(".*", searchFrom);
            if (dotStar < 0) {
                break;
            }

            String pathBeforeWildcard = out.substring(0, dotStar);
            JsonNode nodeBeforeWildcard = resolveSimpleNodePathFromRootProp(rootValue, pathBeforeWildcard);
            if (nodeBeforeWildcard != null && nodeBeforeWildcard.isArray()) {
                out.replace(dotStar, dotStar + 2, "[*]");
                searchFrom = dotStar + 3;
            } else {
                searchFrom = dotStar + 2;
            }
        }
        return out.toString();
    }

    private static JsonNode resolveSimpleNodePathFromRootProp(JsonNode rootValue, String queryPrefix) {
        if (rootValue == null || queryPrefix == null || !queryPrefix.startsWith(ROOT_PROP)) {
            return null;
        }

        JsonNode current = rootValue;
        int i = ROOT_PROP.length();
        while (i < queryPrefix.length()) {
            char ch = queryPrefix.charAt(i);
            if (ch == '.') {
                int next = i + 1;
                int end = next;
                while (end < queryPrefix.length()) {
                    char c = queryPrefix.charAt(end);
                    if (c == '.' || c == '[' || c == '#') {
                        break;
                    }
                    end++;
                }
                if (end <= next || current == null || !current.isObject()) {
                    return null;
                }
                String field = queryPrefix.substring(next, end);
                if (field.startsWith("`") && field.endsWith("`") && field.length() >= 2) {
                    field = field.substring(1, field.length() - 1);
                }
                current = current.get(field);
                i = end;
                continue;
            }

            if (ch == '[') {
                int end = queryPrefix.indexOf(']', i);
                if (end < 0 || current == null || !current.isArray()) {
                    return null;
                }
                String body = queryPrefix.substring(i + 1, end).trim();
                if (body.equals("*") || body.contains(",") || body.contains("..")) {
                    return current;
                }
                try {
                    int index = Integer.parseInt(body);
                    if (index < 0) {
                        index = current.size() + index;
                    }
                    current = index >= 0 && index < current.size() ? current.get(index) : null;
                } catch (NumberFormatException ignored) {
                    return null;
                }
                i = end + 1;
                continue;
            }

            return null;
        }
        return current;
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

        List<Candidate> ci = matchingCandidates(children, requestedBase, directoryOnly, false);
        return ci;
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
            JsonNode parsed = parseSingleFile(content, fileName);
            return parsed;
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

        JsonNode builtDir = dir.size() == 0 ? null : dir;
        return builtDir;
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