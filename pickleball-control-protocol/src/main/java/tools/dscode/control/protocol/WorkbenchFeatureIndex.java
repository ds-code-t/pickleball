package tools.dscode.control.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Hash-validated feature index for Workbench selection.
 *
 * <p>The index reads {@code .feature} files as text. It does not call Cucumber
 * and it does not change nested scenario lookup. A cache hit reuses the parsed
 * scenarios when the file bytes still hash to the stored digest. A changed or
 * new file is parsed again. A corrupt cache is ignored and the tree is scanned.
 * When a file cannot be read, or a tag expression cannot be evaluated, the
 * launch must not trust a narrowed path list: that is the full-scan fallback.
 * A file the text parser cannot fully model (a {@code # language:} header other
 * than {@code en}, an unsupported language, or a real Gherkin keyword from
 * another dialect) is not a full-scan fallback. That file is always added to a
 * narrowed selection, and it contributes no scenario hits. A free-text
 * description such as {@code Note:} is not a keyword and does not force inclusion.</p>
 */
public final class WorkbenchFeatureIndex {
    static final int VERSION = 3;
    public static final String CACHE_FILE = "feature-index.v3";
    public static final String NO_MATCH_FEATURE = "no-match.feature";

    private WorkbenchFeatureIndex() {
    }

    public record FeatureLine(String path, int line) {
    }

    public record Request(
            Path projectRoot,
            List<Path> extraRoots,
            String tags,
            String name,
            String example,
            List<String> selects,
            boolean refresh
    ) {
        public Request {
            if (projectRoot == null) {
                throw new IllegalArgumentException("projectRoot is required");
            }
            extraRoots = extraRoots == null ? List.of() : List.copyOf(extraRoots);
            selects = selects == null ? List.of() : List.copyOf(selects);
        }
    }

    public record Hit(
            String relativePath,
            String featureName,
            String scenarioName,
            int startLine,
            int endLine,
            int exampleRow,
            int line,
            List<String> tags,
            List<String> headers,
            List<String> values
    ) {
        public Hit {
            relativePath = relativePath == null ? "" : relativePath;
            featureName = featureName == null ? "" : featureName;
            scenarioName = scenarioName == null ? "" : scenarioName;
            tags = List.copyOf(tags == null ? List.of() : tags);
            headers = List.copyOf(headers == null ? List.of() : headers);
            values = List.copyOf(values == null ? List.of() : values);
        }

        public String argumentText() {
            if (headers.isEmpty()) return "";
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) text.append(", ");
                text.append(headers.get(i)).append('=');
                text.append(i < values.size() ? values.get(i) : "");
            }
            return text.toString();
        }
    }

    public record Selection(
            boolean narrowed,
            boolean fullScanFallback,
            boolean matchedNone,
            String fallbackReason,
            String featureProperty,
            List<Hit> hits,
            int filesIndexed,
            int filesReparsed,
            int cacheHits
    ) {
        public Selection {
            fallbackReason = fallbackReason == null ? "" : fallbackReason;
            hits = List.copyOf(hits == null ? List.of() : hits);
        }
    }

    public static Selection select(Request request) {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        List<Path> files = discoverFiles(root, request.extraRoots());
        Cache cache = request.refresh() ? Cache.empty() : Cache.load(cacheFile(root));
        List<StoredFile> stored = new ArrayList<>();
        int reparsed = 0;
        int cacheHits = 0;
        String unreadable = null;
        for (Path file : files) {
            String relative = relative(root, file);
            try {
                byte[] bytes = Files.readAllBytes(file);
                String hash = sha256(bytes);
                StoredFile cached = cache.files.get(relative);
                if (cached != null && hash.equals(cached.sha256)) {
                    stored.add(cached);
                    cacheHits++;
                } else {
                    stored.add(parse(root, file, relative, hash, bytes));
                    reparsed++;
                }
            } catch (IOException failure) {
                unreadable = relative;
            }
        }
        try {
            Cache.write(cacheFile(root), stored);
        } catch (IOException ignored) {
            // The next select falls back to a full parse. Narrowing still uses this pass.
        }
        if (unreadable != null) {
            return new Selection(
                    false, true, false,
                    "unreadable feature " + unreadable,
                    null, List.of(), files.size(), reparsed, cacheHits
            );
        }
        TagExpression tags = TagExpression.compile(request.tags());
        if (tags.unsupported()) {
            return new Selection(
                    false, true, false,
                    "unsupported tag expression: " + request.tags(),
                    null, List.of(), files.size(), reparsed, cacheHits
            );
        }
        Pattern name = compileName(request.name());
        ExampleRowSelector example = compileExample(request.example());
        List<FeatureLine> lines = parseSelectors(request.selects());
        List<Hit> hits = new ArrayList<>();
        List<String> forced = new ArrayList<>();
        for (StoredFile file : stored) {
            if (file.unindexable) {
                forced.add(file.relativePath);
                continue;
            }
            for (StoredScenario scenario : file.scenarios) {
                hits.addAll(matchScenario(file, scenario, tags, name, example, lines));
            }
        }
        hits.sort(Comparator
                .comparing(Hit::relativePath)
                .thenComparingInt(Hit::line)
                .thenComparingInt(Hit::exampleRow));
        boolean filtered = hasFilter(request, lines);
        boolean lineSpecific = !lines.isEmpty();
        Set<String> matchedFiles = new LinkedHashSet<>();
        for (Hit hit : hits) matchedFiles.add(hit.relativePath());
        Set<String> included = new LinkedHashSet<>(matchedFiles);
        included.addAll(forced);
        boolean coversAllFiles = !stored.isEmpty() && included.size() == stored.size();
        boolean subset = !coversAllFiles || lineSpecific || (filtered && hits.isEmpty() && forced.isEmpty());
        boolean narrowed = filtered && subset;
        String property = null;
        if (narrowed && (!hits.isEmpty() || !forced.isEmpty())) {
            property = featureProperty(root, hits, lineSpecific, forced);
        }
        return new Selection(
                narrowed,
                false,
                narrowed && hits.isEmpty() && forced.isEmpty(),
                "",
                property,
                hits,
                stored.size(),
                reparsed,
                cacheHits
        );
    }

    public static Path cacheFile(Path projectRoot) {
        return PickleballLocalLayout.workbenchStateRoot(projectRoot).resolve(CACHE_FILE);
    }

    /** Tiny feature Cucumber can open when the Workbench filter matched nothing. */
    public static String noMatchFeatureProperty(Path projectRoot) throws IOException {
        Path file = PickleballLocalLayout.workbenchStateRoot(projectRoot).resolve(NO_MATCH_FEATURE);
        Files.createDirectories(file.getParent());
        String body = "Feature: Workbench selection matched nothing\n";
        if (!Files.isRegularFile(file) || !body.equals(Files.readString(file))) {
            Files.writeString(file, body);
        }
        return "wd-file:" + file.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    public static List<FeatureLine> parseSelectors(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<FeatureLine> lines = new ArrayList<>();
        for (String token : raw) {
            if (token == null || token.isBlank()) continue;
            lines.addAll(parseSelector(token.strip()));
        }
        return List.copyOf(lines);
    }

    public static boolean sameFeature(String relativePath, Path file, String selectorPath) {
        String relative = normalize(relativePath);
        String selector = normalize(selectorPath);
        if (selector.startsWith("./")) selector = selector.substring(2);
        if (relative.equals(selector) || relative.endsWith("/" + selector) || selector.endsWith("/" + relative)) {
            return true;
        }
        if (file != null) {
            String absolute = normalize(file.toAbsolutePath().normalize().toString());
            if (absolute.equals(selector) || selector.endsWith("/" + relative)) return true;
        }
        return false;
    }

    private static boolean hasFilter(Request request, List<FeatureLine> lines) {
        return (request.tags() != null && !request.tags().isBlank())
                || (request.name() != null && !request.name().isBlank())
                || (request.example() != null && !request.example().isBlank())
                || !lines.isEmpty();
    }

    private static String featureProperty(Path root, List<Hit> hits, boolean lineSpecific, List<String> forcedPaths) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (Hit hit : hits) {
            Path file = root.resolve(hit.relativePath()).toAbsolutePath().normalize();
            String value = "wd-file:" + file.toString().replace('\\', '/');
            if (lineSpecific) value = value + ":" + hit.line();
            paths.add(value);
        }
        for (String relative : forcedPaths) {
            Path file = root.resolve(relative).toAbsolutePath().normalize();
            paths.add("wd-file:" + file.toString().replace('\\', '/'));
        }
        return String.join(",", paths);
    }

    private static List<Hit> matchScenario(
            StoredFile file,
            StoredScenario scenario,
            TagExpression tags,
            Pattern name,
            ExampleRowSelector example,
            List<FeatureLine> lines
    ) {
        if (name != null && !name.matcher(scenario.name).find()) return List.of();
        List<StoredExample> rows = scenario.examples;
        if (rows.isEmpty()) {
            if (!tags.matches(scenario.tags)) return List.of();
            if (example != null && !example.matches(1, 1, 1)) return List.of();
            if (!lines.isEmpty() && !lineSelectsScenario(file.relativePath, scenario, 0, lines)) return List.of();
            return List.of(hit(file, scenario, 0, scenario.keywordLine, scenario.tags, List.of(), List.of()));
        }
        List<Hit> matched = new ArrayList<>();
        for (StoredExample row : rows) {
            List<String> effective = new ArrayList<>(scenario.tags);
            effective.addAll(row.tags);
            if (!tags.matches(effective)) continue;
            if (example != null && !example.matches(row.overall, row.table, row.row)) continue;
            if (!lines.isEmpty() && !lineSelectsRow(file.relativePath, scenario, row, lines)) continue;
            matched.add(hit(file, scenario, row.row, row.line, effective, row.headers, row.values));
        }
        return matched;
    }

    private static boolean lineSelectsScenario(String relative, StoredScenario scenario, int ignored, List<FeatureLine> lines) {
        for (FeatureLine line : lines) {
            if (!sameFeature(relative, null, line.path())) continue;
            if (line.line() >= scenario.rangeStart && line.line() <= scenario.endLine) return true;
        }
        return false;
    }

    private static boolean lineSelectsRow(String relative, StoredScenario scenario, StoredExample row, List<FeatureLine> lines) {
        for (FeatureLine line : lines) {
            if (!sameFeature(relative, null, line.path())) continue;
            if (line.line() == row.line) return true;
            if (line.line() >= scenario.rangeStart && line.line() <= scenario.endLine && !isExampleLine(scenario, line.line())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExampleLine(StoredScenario scenario, int line) {
        for (StoredExample row : scenario.examples) {
            if (row.line == line) return true;
        }
        return false;
    }

    private static Hit hit(
            StoredFile file,
            StoredScenario scenario,
            int exampleRow,
            int line,
            List<String> tags,
            List<String> headers,
            List<String> values
    ) {
        return new Hit(
                file.relativePath,
                file.featureName,
                scenario.name,
                scenario.keywordLine,
                scenario.endLine,
                exampleRow,
                line,
                tags,
                headers,
                values
        );
    }

    private static Pattern compileName(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return Pattern.compile(name.strip());
        } catch (PatternSyntaxException failure) {
            throw new IllegalArgumentException(
                    "Unparseable scenario name expression: " + name.strip()
                            + " (" + failure.getDescription() + ")"
            );
        }
    }

    private static ExampleRowSelector compileExample(String example) {
        if (example == null || example.isBlank()) return null;
        return ExampleRowSelector.parse(example);
    }

    static List<FeatureLine> parseSelector(String token) {
        String text = token.replace('\\', '/');
        int feature = text.toLowerCase(Locale.ROOT).lastIndexOf(".feature");
        if (feature < 0) {
            throw new IllegalArgumentException(
                    "Path:line selection must be a feature file and a line: " + token
            );
        }
        int pathEnd = feature + ".feature".length();
        String path = text.substring(0, pathEnd);
        String rest = text.substring(pathEnd);
        if (rest.isEmpty() || rest.charAt(0) != ':') {
            throw new IllegalArgumentException(
                    "Path:line selection must be a feature file and a line: " + token
            );
        }
        List<FeatureLine> lines = new ArrayList<>();
        for (String part : rest.substring(1).split(":")) {
            if (part.isEmpty()) continue;
            int line;
            try {
                line = Integer.parseInt(part);
            } catch (NumberFormatException failure) {
                throw new IllegalArgumentException(
                        "Path:line selection must be a feature file and a line: " + token
                );
            }
            if (line <= 0) {
                throw new IllegalArgumentException(
                        "Path:line selection must be a feature file and a line: " + token
                );
            }
            lines.add(new FeatureLine(path, line));
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(
                    "Path:line selection must be a feature file and a line: " + token
            );
        }
        return lines;
    }

    private static List<Path> discoverFiles(Path root, List<Path> extraRoots) {
        LinkedHashSet<Path> searchRoots = new LinkedHashSet<>();
        addDir(searchRoots, root.resolve("src").resolve("test").resolve("resources").resolve("features"));
        addDir(searchRoots, root.resolve("src").resolve("test").resolve("resources"));
        for (Path configured : configuredFeatureRoots(root)) addDir(searchRoots, configured);
        if (extraRoots != null) {
            for (Path extra : extraRoots) {
                if (extra == null) continue;
                Path path = extra.isAbsolute() ? extra : root.resolve(extra);
                addDir(searchRoots, path);
                addDir(searchRoots, path.resolve("features"));
            }
        }
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (Path searchRoot : searchRoots) collect(searchRoot, files);
        return List.copyOf(files);
    }

    private static List<Path> configuredFeatureRoots(Path projectRoot) {
        List<Path> roots = new ArrayList<>();
        List<Path> propertyFiles = List.of(
                projectRoot.resolve("src").resolve("test").resolve("resources").resolve("pickleball.properties"),
                projectRoot.resolve("src").resolve("main").resolve("resources").resolve("pickleball.properties"),
                projectRoot.resolve("pickleball.properties")
        );
        for (Path file : propertyFiles) {
            if (!Files.isRegularFile(file)) continue;
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (trimmed.startsWith("#") || !trimmed.contains("pkb_features")) continue;
                    int equals = trimmed.indexOf('=');
                    if (equals < 0) continue;
                    String raw = trimmed.substring(equals + 1).strip();
                    if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
                        raw = raw.substring(1, raw.length() - 1);
                    }
                    for (String part : raw.split(",")) {
                        Path resolved = resolveConfigured(projectRoot, part);
                        if (resolved != null) roots.add(resolved);
                    }
                }
            } catch (IOException ignored) {
                // Optional discovery input.
            }
        }
        return roots;
    }

    private static Path resolveConfigured(Path projectRoot, String value) {
        if (value == null || value.isBlank()) return null;
        PickleballPaths.Parsed parsed = PickleballPaths.parse(value.strip());
        if (parsed.kind() == PickleballPaths.Kind.CLASSPATH) {
            String resource = parsed.classpathResource() == null ? "" : parsed.classpathResource();
            Path testResources = projectRoot.resolve("src").resolve("test").resolve("resources");
            return resource.isBlank() ? testResources : testResources.resolve(resource);
        }
        return PickleballPaths.toFile(parsed, projectRoot);
    }

    private static void addDir(Set<Path> roots, Path path) {
        if (path != null && Files.isDirectory(path)) roots.add(path.toAbsolutePath().normalize());
    }

    private static void collect(Path root, Set<Path> files) {
        try (Stream<Path> walk = Files.walk(root, 8)) {
            walk.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".feature"))
                    .forEach(path -> files.add(path.toAbsolutePath().normalize()));
        } catch (IOException ignored) {
            // One unreadable directory must not hide the rest. An unreadable file is a fallback.
        }
    }

    private static final Pattern LANGUAGE_HEADER = Pattern.compile("(?i)^#\\s*language\\s*:\\s*(\\S+)");

    private static StoredFile parse(Path root, Path file, String relative, String hash, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        String[] rawLines = text.split("\\R", -1);
        String featureName = "";
        List<String> featureTags = List.of();
        List<String> ruleTags = List.of();
        List<String> pending = new ArrayList<>();
        int pendingStart = -1;
        String currentName = null;
        int keywordLine = -1;
        int rangeStart = -1;
        List<String> ownTags = List.of();
        List<StoredExample> examples = new ArrayList<>();
        List<String> exampleHeader = null;
        List<String> exampleTags = List.of();
        int table = 0;
        int overall = 0;
        int rowInTable = 0;
        boolean inExamples = false;
        boolean inDocString = false;
        boolean unindexable = false;
        String language = "en";
        boolean languageSeen = false;
        List<StoredScenario> scenarios = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            String trimmed = rawLines[i].strip();
            int lineNo = i + 1;
            if (isDocStringDelimiter(trimmed)) {
                inDocString = !inDocString;
                continue;
            }
            if (inDocString) continue;
            if (isTagLine(trimmed)) {
                if (pending.isEmpty()) pendingStart = lineNo;
                pending.addAll(tagTokens(trimmed));
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (trimmed.startsWith("#")) {
                    unindexable |= foreignLanguage(trimmed);
                    if (!languageSeen) {
                        java.util.regex.Matcher matcher = LANGUAGE_HEADER.matcher(trimmed);
                        if (matcher.find()) {
                            language = matcher.group(1);
                            languageSeen = true;
                        }
                    }
                }
                continue;
            }
            if (starts(trimmed, "Feature:") || starts(trimmed, "Business Need:") || starts(trimmed, "Ability:")) {
                featureName = trimmed.substring(trimmed.indexOf(':') + 1).strip();
                featureTags = List.copyOf(pending);
                ruleTags = List.of();
                pending.clear();
                pendingStart = -1;
                inExamples = false;
                continue;
            }
            if (starts(trimmed, "Rule:")) {
                ruleTags = List.copyOf(pending);
                pending.clear();
                pendingStart = -1;
                inExamples = false;
                continue;
            }
            if (starts(trimmed, "Background:")) {
                pending.clear();
                pendingStart = -1;
                inExamples = false;
                continue;
            }
            if (starts(trimmed, "Scenario Outline:") || starts(trimmed, "Scenario Template:")
                    || starts(trimmed, "Scenario:") || starts(trimmed, "Example:")) {
                if (currentName != null) {
                    scenarios.add(scenario(currentName, keywordLine, rangeStart, lineNo - 1, ownTags, examples));
                }
                currentName = trimmed.substring(trimmed.indexOf(':') + 1).strip();
                keywordLine = lineNo;
                rangeStart = pendingStart > 0 ? pendingStart : lineNo;
                ownTags = merge(featureTags, ruleTags, pending);
                pending.clear();
                pendingStart = -1;
                examples = new ArrayList<>();
                exampleHeader = null;
                exampleTags = List.of();
                table = 0;
                overall = 0;
                rowInTable = 0;
                inExamples = false;
                continue;
            }
            if (starts(trimmed, "Examples:") || starts(trimmed, "Scenarios:")) {
                inExamples = currentName != null;
                exampleHeader = null;
                exampleTags = List.copyOf(pending);
                if (inExamples) table++;
                rowInTable = 0;
                pending.clear();
                pendingStart = -1;
                continue;
            }
            if (inExamples && trimmed.startsWith("|")) {
                List<String> cells = cells(trimmed);
                if (exampleHeader == null) {
                    exampleHeader = cells;
                } else {
                    rowInTable++;
                    overall++;
                    examples.add(new StoredExample(
                            lineNo, overall, table, rowInTable,
                            withScenarioTags(exampleTags, exampleHeader, cells),
                            exampleHeader,
                            cells
                    ));
                }
                pending.clear();
                pendingStart = -1;
                continue;
            }
            if (isStep(trimmed) || trimmed.startsWith("|")) {
                pending.clear();
                pendingStart = -1;
                inExamples = false;
                continue;
            }
            if (looksLikeUnknownKeyword(trimmed, language)) {
                unindexable = true;
            }
            pending.clear();
            pendingStart = -1;
        }
        if (currentName != null) {
            scenarios.add(scenario(currentName, keywordLine, rangeStart, rawLines.length, ownTags, examples));
        }
        if (unindexable) scenarios = List.of();
        return new StoredFile(relative, hash, featureName, scenarios, unindexable);
    }

    /**
     * A {@code Scenario Tags} examples column is a Pickleball outline tag, not a Gherkin tag line.
     * {@code @} is a normal tag. {@code %} is a component tag. Other cell text is not a tag.
     */
    private static List<String> withScenarioTags(List<String> exampleTags, List<String> headers, List<String> cells) {
        int index = headers.indexOf("Scenario Tags");
        if (index < 0 || index >= cells.size()) return exampleTags;
        String raw = cells.get(index);
        if (raw == null || raw.isBlank()) return exampleTags;
        List<String> tags = new ArrayList<>(exampleTags);
        for (String token : raw.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            if (token.startsWith("@")) {
                String value = token;
                while (value.startsWith("@")) value = value.substring(1);
                if (!value.isEmpty()) tags.add(value);
            } else if (token.startsWith("%")) {
                tags.add(token);
            }
        }
        return List.copyOf(tags);
    }

    private static boolean foreignLanguage(String comment) {
        java.util.regex.Matcher matcher = LANGUAGE_HEADER.matcher(comment);
        if (!matcher.find()) return false;
        return !"en".equalsIgnoreCase(matcher.group(1));
    }

    private static boolean isDocStringDelimiter(String trimmed) {
        return trimmed.startsWith("\"\"\"") || trimmed.startsWith("```");
    }

    private static boolean isStep(String trimmed) {
        return stepKeyword(trimmed, "Given")
                || stepKeyword(trimmed, "When")
                || stepKeyword(trimmed, "Then")
                || stepKeyword(trimmed, "And")
                || stepKeyword(trimmed, "But")
                || trimmed.equals("*")
                || trimmed.startsWith("* ")
                || trimmed.startsWith("*\t");
    }

    private static boolean stepKeyword(String trimmed, String keyword) {
        return trimmed.equals(keyword) || trimmed.startsWith(keyword + " ") || trimmed.startsWith(keyword + "\t");
    }

    /**
     * A real Gherkin keyword from a dialect other than the file's {@code # language:}.
     * Free-text descriptions ({@code Note:}, {@code As a shopper:}) are not keywords.
     */
    private static boolean looksLikeUnknownKeyword(String trimmed, String language) {
        int colon = trimmed.indexOf(':');
        if (colon <= 0) return false;
        String head = trimmed.substring(0, colon).strip();
        return GherkinKeywords.isForeignKeyword(language, head);
    }

    private static StoredScenario scenario(
            String name,
            int keywordLine,
            int rangeStart,
            int endLine,
            List<String> tags,
            List<StoredExample> examples
    ) {
        return new StoredScenario(name, keywordLine, rangeStart, Math.max(keywordLine, endLine), tags, examples);
    }

    private static List<String> merge(List<String> feature, List<String> rule, List<String> own) {
        List<String> tags = new ArrayList<>();
        tags.addAll(feature);
        tags.addAll(rule);
        tags.addAll(own);
        return List.copyOf(tags);
    }

    private static boolean starts(String trimmed, String keyword) {
        return trimmed.startsWith(keyword);
    }

    private static boolean isTagLine(String trimmed) {
        if (trimmed.isEmpty() || !trimmed.startsWith("@")) return false;
        String[] tokens = trimmed.split("\\s+");
        for (String token : tokens) {
            if (!token.startsWith("@") || token.length() == 1) return false;
        }
        return tokens.length > 0;
    }

    private static List<String> tagTokens(String trimmed) {
        List<String> tags = new ArrayList<>();
        for (String token : trimmed.split("\\s+")) {
            String value = token;
            while (value.startsWith("@")) value = value.substring(1);
            if (!value.isEmpty()) tags.add(value);
        }
        return tags;
    }

    private static List<String> cells(String trimmed) {
        String row = trimmed.substring(1);
        if (row.endsWith("|")) row = row.substring(0, row.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : row.split("\\|", -1)) cells.add(cell.strip());
        return cells;
    }

    private static String relative(Path root, Path file) {
        try {
            return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
            return file.toAbsolutePath().normalize().toString().replace('\\', '/');
        }
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record StoredExample(
            int line,
            int overall,
            int table,
            int row,
            List<String> tags,
            List<String> headers,
            List<String> values
    ) {
    }

    private record StoredScenario(
            String name,
            int keywordLine,
            int rangeStart,
            int endLine,
            List<String> tags,
            List<StoredExample> examples
    ) {
    }

    private record StoredFile(
            String relativePath,
            String sha256,
            String featureName,
            List<StoredScenario> scenarios,
            boolean unindexable
    ) {
    }

    private static final class Cache {
        final LinkedHashMap<String, StoredFile> files;

        Cache(LinkedHashMap<String, StoredFile> files) {
            this.files = files;
        }

        static Cache empty() {
            return new Cache(new LinkedHashMap<>());
        }

        static Cache load(Path file) {
            if (!Files.isRegularFile(file)) return empty();
            try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                if (in.readInt() != VERSION) return empty();
                int count = in.readInt();
                LinkedHashMap<String, StoredFile> files = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    StoredFile stored = readFile(in);
                    files.put(stored.relativePath, stored);
                }
                return new Cache(files);
            } catch (IOException failure) {
                return empty();
            }
        }

        static void write(Path file, List<StoredFile> stored) throws IOException {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(temp))) {
                out.writeInt(VERSION);
                out.writeInt(stored.size());
                for (StoredFile item : stored) writeFile(out, item);
            }
            try {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException failure) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private static void writeFile(DataOutputStream out, StoredFile file) throws IOException {
            out.writeUTF(file.relativePath);
            out.writeUTF(file.sha256);
            out.writeUTF(file.featureName == null ? "" : file.featureName);
            out.writeBoolean(file.unindexable);
            out.writeInt(file.scenarios.size());
            for (StoredScenario scenario : file.scenarios) {
                out.writeUTF(scenario.name == null ? "" : scenario.name);
                out.writeInt(scenario.keywordLine);
                out.writeInt(scenario.rangeStart);
                out.writeInt(scenario.endLine);
                writeList(out, scenario.tags);
                out.writeInt(scenario.examples.size());
                for (StoredExample example : scenario.examples) {
                    out.writeInt(example.line);
                    out.writeInt(example.overall);
                    out.writeInt(example.table);
                    out.writeInt(example.row);
                    writeList(out, example.tags);
                    writeList(out, example.headers);
                    writeList(out, example.values);
                }
            }
        }

        private static StoredFile readFile(DataInputStream in) throws IOException {
            String path = in.readUTF();
            String hash = in.readUTF();
            String feature = in.readUTF();
            boolean unindexable = in.readBoolean();
            int scenarios = in.readInt();
            List<StoredScenario> parsed = new ArrayList<>();
            for (int i = 0; i < scenarios; i++) {
                String name = in.readUTF();
                int keyword = in.readInt();
                int range = in.readInt();
                int end = in.readInt();
                List<String> tags = readList(in);
                int examples = in.readInt();
                List<StoredExample> rows = new ArrayList<>();
                for (int row = 0; row < examples; row++) {
                    rows.add(new StoredExample(
                            in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                            readList(in), readList(in), readList(in)
                    ));
                }
                parsed.add(new StoredScenario(name, keyword, range, end, tags, rows));
            }
            return new StoredFile(path, hash, feature, parsed, unindexable);
        }

        private static void writeList(DataOutputStream out, List<String> values) throws IOException {
            List<String> safe = values == null ? List.of() : values;
            out.writeInt(safe.size());
            for (String value : safe) out.writeUTF(value == null ? "" : value);
        }

        private static List<String> readList(DataInputStream in) throws IOException {
            int size = in.readInt();
            List<String> values = new ArrayList<>();
            for (int i = 0; i < size; i++) values.add(in.readUTF());
            return List.copyOf(values);
        }
    }

    /** Cucumber-style {@code and}/{@code or}/{@code not} tag expression. Unsupported input does not narrow. */
    static final class TagExpression {
        private final Node node;
        private final boolean unsupported;

        private TagExpression(Node node, boolean unsupported) {
            this.node = node;
            this.unsupported = unsupported;
        }

        static TagExpression compile(String raw) {
            if (raw == null || raw.isBlank()) return new TagExpression(null, false);
            Parser parser = new Parser(raw.strip());
            Node node = parser.parseOr();
            parser.skipWs();
            if (parser.failed || parser.index < parser.text.length()) {
                return new TagExpression(null, true);
            }
            return new TagExpression(node, false);
        }

        boolean unsupported() {
            return unsupported;
        }

        boolean matches(List<String> tags) {
            if (node == null) return !unsupported;
            Set<String> set = new LinkedHashSet<>();
            if (tags != null) {
                for (String tag : tags) {
                    if (tag == null) continue;
                    String value = tag;
                    while (value.startsWith("@")) value = value.substring(1);
                    if (!value.isEmpty()) set.add(value);
                }
            }
            return node.eval(set);
        }

        private interface Node {
            boolean eval(Set<String> tags);
        }

        private record TagNode(String tag) implements Node {
            public boolean eval(Set<String> tags) {
                return tags.contains(tag);
            }
        }

        private record NotNode(Node inner) implements Node {
            public boolean eval(Set<String> tags) {
                return !inner.eval(tags);
            }
        }

        private record AndNode(Node left, Node right) implements Node {
            public boolean eval(Set<String> tags) {
                return left.eval(tags) && right.eval(tags);
            }
        }

        private record OrNode(Node left, Node right) implements Node {
            public boolean eval(Set<String> tags) {
                return left.eval(tags) || right.eval(tags);
            }
        }

        private static final class Parser {
            final String text;
            int index;
            boolean failed;

            Parser(String text) {
                this.text = text;
            }

            Node parseOr() {
                Node left = parseAnd();
                while (keyword("or")) left = new OrNode(left, parseAnd());
                return left;
            }

            Node parseAnd() {
                Node left = parseNot();
                while (keyword("and")) left = new AndNode(left, parseNot());
                return left;
            }

            Node parseNot() {
                if (keyword("not")) return new NotNode(parseNot());
                skipWs();
                if (consume('(')) {
                    Node inner = parseOr();
                    if (!consume(')')) failed = true;
                    return inner;
                }
                return parseTag();
            }

            Node parseTag() {
                skipWs();
                if (index >= text.length() || text.charAt(index) != '@') {
                    failed = true;
                    return new TagNode("");
                }
                int start = ++index;
                while (index < text.length()) {
                    char ch = text.charAt(index);
                    if (Character.isWhitespace(ch) || ch == '(' || ch == ')') break;
                    index++;
                }
                if (start == index) {
                    failed = true;
                    return new TagNode("");
                }
                return new TagNode(text.substring(start, index));
            }

            boolean keyword(String word) {
                skipWs();
                if (!text.regionMatches(index, word, 0, word.length())) return false;
                int end = index + word.length();
                if (end < text.length()) {
                    char ch = text.charAt(end);
                    if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-') return false;
                }
                index = end;
                return true;
            }

            boolean consume(char expected) {
                skipWs();
                if (index < text.length() && text.charAt(index) == expected) {
                    index++;
                    return true;
                }
                return false;
            }

            void skipWs() {
                while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
            }
        }
    }
}
