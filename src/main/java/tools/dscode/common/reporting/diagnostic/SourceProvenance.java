package tools.dscode.common.reporting.diagnostic;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static tools.dscode.testengine.PKB_props.PKB_GIT_SNAPSHOT;

/**
 * Captures source-control and source-location provenance for diagnostic evidence.
 * Git metadata is best-effort and never required for test execution.
 */
public final class SourceProvenance {
    private static final String BUILD_INFO = "META-INF/pickleball-build.properties";
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(4);
    private static volatile SourceProvenance platformSnapshot;

    private final Repository consumer;
    private final Repository pickleball;
    private final Path consumerRoot;
    private final Path consumerProjectRoot;
    private final String snapshotMode;

    private SourceProvenance(
            Repository consumer,
            Repository pickleball,
            Path consumerRoot,
            Path consumerProjectRoot,
            String snapshotMode
    ) {
        this.consumer = consumer;
        this.pickleball = pickleball;
        this.consumerRoot = consumerRoot;
        this.consumerProjectRoot = consumerProjectRoot;
        this.snapshotMode = snapshotMode;
    }

    public static void validate(Map<String, String> config) {
        String mode = find(config, PKB_GIT_SNAPSHOT);
        if (mode == null || mode.isBlank()) return;
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        if (!List.of("metadata", "diff", "none").contains(normalized)) {
            throw new IllegalArgumentException("pkb_gitsnapshot must be metadata, diff, or none but was: " + mode);
        }
    }

    public static SourceProvenance capture(Map<String, String> config) {
        String mode = find(config, PKB_GIT_SNAPSHOT);
        if (mode == null || mode.isBlank()) mode = "metadata";
        mode = mode.trim().toLowerCase(Locale.ROOT);
        if (!List.of("metadata", "diff", "none").contains(mode)) {
            throw new IllegalArgumentException("pkb_gitsnapshot must be metadata, diff, or none but was: " + mode);
        }

        Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        GitRepository live = "none".equals(mode) ? null : inspectGitRepository(workingDirectory);
        Repository consumer = live == null
                ? Repository.unavailable("consumer")
                : live.repository("consumer");

        BuildInfo buildInfo = loadBuildInfo();
        Repository framework = buildInfo.repository();
        if (live != null && isPickleballRemote(live.remote) && framework.commit().isBlank()) {
            framework = live.repository("pickleball");
        }
        framework = framework.withArtifact(frameworkVersion(buildInfo), frameworkArtifactSha256());

        return new SourceProvenance(
                consumer,
                framework,
                live == null ? null : live.root,
                projectRoot(workingDirectory),
                mode
        );
    }

    public static Map<String, String> platformValues() {
        SourceProvenance current = platformSnapshot;
        if (current == null) {
            synchronized (SourceProvenance.class) {
                current = platformSnapshot;
                if (current == null) {
                    current = capture(Map.of());
                    platformSnapshot = current;
                }
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        current.addPlatformValues(values, "git.consumer", current.consumer);
        current.addPlatformValues(values, "git.pickleball", current.pickleball);
        values.put("pickleball.version", current.pickleball.version());
        return values;
    }

    public Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("snapshotMode", snapshotMode);
        body.put("repositories", List.of(consumer.asMap(), pickleball.asMap()));
        body.put("notes", List.of(
                "A clean repository with a commit hash is reproducible from Git.",
                "A dirty repository is not fully reproducible from its commit alone.",
                "Source pointers are best-effort; external step libraries may not expose repository metadata."
        ));
        return body;
    }

    public Map<String, Object> comparisonMetadata() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consumerRepository", consumer.name());
        result.put("consumerCommit", emptyToNull(consumer.commit()));
        result.put("consumerBranch", emptyToNull(consumer.branch()));
        result.put("consumerDirty", consumer.dirty());
        result.put("consumerReproducibleFromGit", consumer.reproducibleFromGit());
        result.put("pickleballVersion", emptyToNull(pickleball.version()));
        result.put("pickleballCommit", emptyToNull(pickleball.commit()));
        result.put("pickleballDirty", pickleball.dirty());
        result.put("pickleballArtifactSha256", emptyToNull(pickleball.artifactSha256()));
        return result;
    }

    public Map<String, Object> featureSource(String featureUri, long line) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("repository", "consumer");
        source.put("uri", featureUri == null ? "" : featureUri);
        source.put("line", line);
        Path file = resolveFeaturePath(featureUri);
        if (file != null) {
            String relative = relativeToConsumer(file);
            if (!relative.isBlank()) source.put("path", relative);
            if (Files.isRegularFile(file)) source.put("sha256", sha256(file));
        }
        source.put("commit", emptyToNull(consumer.commit()));
        source.put("reproducibleFromGit", consumer.reproducibleFromGit());
        return source;
    }

    public Map<String, Object> definitionSource(Method method, String codeLocation) {
        Map<String, Object> definition = new LinkedHashMap<>();
        if (method == null) {
            definition.put("origin", "UNKNOWN");
            definition.put("codeLocation", codeLocation == null ? "" : codeLocation);
            return definition;
        }

        Class<?> declaringClass = method.getDeclaringClass();
        boolean framework = isPickleballClass(declaringClass);
        definition.put("origin", framework ? "PICKLEBALL" : "NON_PICKLEBALL");
        definition.put("class", declaringClass.getName());
        definition.put("method", method.getName());
        definition.put("codeLocation", codeLocation == null ? "" : codeLocation);
        definition.put("repository", framework ? "pickleball" : "consumer");

        String classPath = declaringClass.getName().replace('.', '/').replaceAll("\\$.*$", "") + ".java";
        Path sourceFile = null;
        String sourcePath;
        if (framework) {
            sourcePath = "src/main/java/" + classPath;
            sourceFile = findLocalSource(sourcePath);
            definition.put("commit", emptyToNull(pickleball.commit()));
            definition.put("reproducibleFromGit", pickleball.reproducibleFromGit());
        } else {
            sourceFile = findConsumerSource(declaringClass, classPath);
            sourcePath = sourceFile == null ? "" : relativeToConsumer(sourceFile);
            boolean consumerSource = sourceFile != null;
            definition.put("repository", consumerSource ? "consumer" : "external");
            if (consumerSource) {
                definition.put("commit", emptyToNull(consumer.commit()));
                definition.put("reproducibleFromGit", consumer.reproducibleFromGit());
            }
        }
        if (!sourcePath.isBlank()) definition.put("sourcePath", sourcePath);
        if (sourceFile != null && Files.isRegularFile(sourceFile)) definition.put("sourceSha256", sha256(sourceFile));
        String binaryHash = classBinarySha256(declaringClass);
        if (!binaryHash.isBlank()) definition.put("classBinarySha256", binaryHash);
        String codeSource = codeSourceName(declaringClass);
        if (!codeSource.isBlank()) definition.put("codeSource", codeSource);
        return definition;
    }

    public void writeOptionalSnapshot(Path runRoot) throws IOException {
        if (!"diff".equals(snapshotMode) || consumerRoot == null || !consumer.dirty()) return;
        String diff = git(consumerRoot, "diff", "--binary");
        String cached = git(consumerRoot, "diff", "--cached", "--binary", "HEAD");
        String status = git(consumerRoot, "status", "--porcelain", "--untracked-files=normal");
        String text = "# git status --porcelain\n" + status
                + "\n# git diff --binary\n" + diff
                + "\n# git diff --cached --binary HEAD\n" + cached;
        Path target = runRoot.resolve("source").resolve("consumer-working-tree.patch.gz");
        Files.createDirectories(target.getParent());
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    public record ReferencedFile(String path, String role) {
        public ReferencedFile {
            path = path == null ? "" : path.replace('\\', '/');
            role = role == null || role.isBlank() ? "feature" : role;
        }
    }

    static SourceProvenance testing(Path consumerRoot, Path projectRoot, String snapshotMode) {
        Repository unavailable = Repository.unavailable("consumer");
        return new SourceProvenance(
                unavailable,
                Repository.unavailable("pickleball"),
                consumerRoot,
                projectRoot == null ? consumerRoot : projectRoot,
                snapshotMode == null || snapshotMode.isBlank() ? "metadata" : snapshotMode
        );
    }

    public void writeReferencedPack(Path runRoot, List<ReferencedFile> files) throws IOException {
        writeReferencedPack(runRoot, files, null);
    }

    public void writeReferencedPack(Path runRoot, List<ReferencedFile> files, String liveBufferFeature) throws IOException {
        if (runRoot == null) return;
        List<ReferencedFile> unique = dedupe(files);
        Path sourceRoot = runRoot.resolve("source");
        Path filesRoot = sourceRoot.resolve("files");
        Files.createDirectories(filesRoot);

        List<Map<String, String>> entries = new ArrayList<>();
        List<String> gitPaths = new ArrayList<>();
        boolean anyDirty = false;
        for (ReferencedFile file : unique) {
            if (file.path().isBlank() || file.path().contains("..")) continue;
            Path resolved = resolveReferenced(file.path());
            if (resolved == null || !Files.isRegularFile(resolved)) continue;
            Path relative = Path.of(file.path()).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) continue;
            Path filesRootAbs = filesRoot.toAbsolutePath().normalize();
            Path copy = filesRoot.resolve(relative).toAbsolutePath().normalize();
            if (!copy.startsWith(filesRootAbs)) continue;
            Files.createDirectories(copy.getParent());
            Files.copy(resolved, copy, StandardCopyOption.REPLACE_EXISTING);
            String vsHead = vsHead(file.path(), resolved);
            if ("dirty".equals(vsHead) || "untracked".equals(vsHead)) anyDirty = true;
            gitPaths.add(file.path());
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("path", file.path());
            entry.put("role", file.role());
            entry.put("sha256", sha256(resolved));
            entry.put("vsHead", vsHead);
            entries.add(entry);
        }
        if (liveBufferFeature != null && !liveBufferFeature.isBlank()) {
            Path live = sourceRoot.resolve("live-buffer.feature");
            Files.writeString(live, liveBufferFeature, StandardCharsets.UTF_8);
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("path", "live-buffer.feature");
            entry.put("role", "live-buffer");
            entry.put("sha256", sha256(live));
            entry.put("vsHead", "untracked");
            entries.add(entry);
        }
        if (entries.isEmpty()) return;
        writeReferencedJson(sourceRoot.resolve("referenced.json"), entries);
        if (anyDirty && !"none".equals(snapshotMode) && consumerRoot != null && !gitPaths.isEmpty()) {
            writeFilteredPatch(sourceRoot.resolve("referenced.patch.gz"), gitPaths);
        }
    }

    Path resolveReferenced(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        String normalized = relativePath.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        Path fromProject = existingFile(consumerProjectRoot, normalized);
        if (fromProject != null) return fromProject;
        Path fromGit = existingFile(consumerRoot, normalized);
        if (fromGit != null) return fromGit;
        return resolveFeaturePath(normalized);
    }

    private static Path existingFile(Path root, String relative) {
        if (root == null) return null;
        try {
            Path resolved = root.resolve(relative).toAbsolutePath().normalize();
            if (!resolved.startsWith(root.toAbsolutePath().normalize())) return null;
            return Files.isRegularFile(resolved) ? resolved : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String vsHead(String relativePath, Path resolved) {
        if ("none".equals(snapshotMode) || consumerRoot == null) return "no-git";
        String status = git(consumerRoot, "status", "--porcelain", "--untracked-files=normal", "--", relativePath);
        if (status.isBlank()) {
            String tracked = git(consumerRoot, "ls-files", "--", relativePath);
            return tracked.isBlank() ? "untracked" : "clean";
        }
        String trimmed = status.trim();
        if (trimmed.startsWith("??")) return "untracked";
        return "dirty";
    }

    private void writeFilteredPatch(Path target, List<String> paths) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("diff");
        args.add("HEAD");
        args.add("--");
        args.addAll(paths);
        String unstaged = git(consumerRoot, args.toArray(String[]::new));
        List<String> cachedArgs = new ArrayList<>();
        cachedArgs.add("diff");
        cachedArgs.add("--cached");
        cachedArgs.add("HEAD");
        cachedArgs.add("--");
        cachedArgs.addAll(paths);
        String staged = git(consumerRoot, cachedArgs.toArray(String[]::new));
        String text = unstaged;
        if (!staged.isBlank()) {
            text = text.isBlank() ? staged : text + "\n" + staged;
        }
        if (text.isBlank()) return;
        Files.createDirectories(target.getParent());
        try (GZIPOutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<ReferencedFile> dedupe(List<ReferencedFile> files) {
        Map<String, ReferencedFile> unique = new LinkedHashMap<>();
        if (files == null) return List.of();
        for (ReferencedFile file : files) {
            if (file == null || file.path().isBlank()) continue;
            unique.putIfAbsent(file.path(), file);
        }
        return List.copyOf(unique.values());
    }

    private static void writeReferencedJson(Path target, List<Map<String, String>> entries) throws IOException {
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"schemaVersion\": 1,\n  \"files\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Map<String, String> entry = entries.get(i);
            json.append("    {");
            json.append("\"path\": \"").append(jsonEscape(entry.get("path"))).append("\", ");
            json.append("\"role\": \"").append(jsonEscape(entry.get("role"))).append("\", ");
            json.append("\"sha256\": \"").append(jsonEscape(entry.get("sha256"))).append("\", ");
            json.append("\"vsHead\": \"").append(jsonEscape(entry.get("vsHead"))).append("\"}");
            if (i + 1 < entries.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        Files.writeString(target, json.toString(), StandardCharsets.UTF_8);
    }

    private static String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public String optionalSnapshotPath() {
        return "diff".equals(snapshotMode) && consumer.dirty()
                ? "source/consumer-working-tree.patch.gz"
                : "";
    }

    private void addPlatformValues(Map<String, String> values, String prefix, Repository repo) {
        values.put(prefix + ".name", repo.name());
        values.put(prefix + ".remote", repo.remote());
        values.put(prefix + ".webUrl", repo.webUrl());
        values.put(prefix + ".branch", repo.branch());
        values.put(prefix + ".commit", repo.commit());
        values.put(prefix + ".commitMessage", repo.commitMessage());
        values.put(prefix + ".dirty", Boolean.toString(repo.dirty()));
    }

    private Path resolveFeaturePath(String featureUri) {
        if (featureUri == null || featureUri.isBlank()) return null;
        try {
            URI uri = URI.create(featureUri);
            if ("file".equalsIgnoreCase(uri.getScheme())) return Path.of(uri).toAbsolutePath().normalize();
        } catch (Throwable ignored) { }
        String raw = featureUri.replace('\\', '/');
        if (raw.startsWith("classpath:")) raw = raw.substring("classpath:".length());
        while (raw.startsWith("/")) raw = raw.substring(1);

        Path source = findClasspathSource(raw);
        if (source != null) return source;

        source = findSource(
                consumerProjectRoot,
                raw,
                "src/test/resources/",
                "src/main/resources/",
                ""
        );
        if (source != null) return source;

        source = findSource(consumerRoot, raw, "src/test/resources/", "src/main/resources/", "");
        return source != null
                ? source
                : findNestedSource(consumerRoot, raw, "src/test/resources/", "src/main/resources/");
    }

    private Path findConsumerSource(Class<?> type, String classPath) {
        Path moduleRoot = moduleRootFromOutputRoot(codeSourceRoot(type));
        Path source = findSource(moduleRoot, classPath, "src/test/java/", "src/main/java/");
        if (source != null) return source;

        source = findSource(consumerProjectRoot, classPath, "src/test/java/", "src/main/java/");
        if (source != null) return source;

        source = findSource(consumerRoot, classPath, "src/test/java/", "src/main/java/");
        return source != null
                ? source
                : findNestedSource(consumerRoot, classPath, "src/test/java/", "src/main/java/");
    }

    private Path findClasspathSource(String raw) {
        if (raw.isBlank()) return null;
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            URL resource = loader == null ? null : loader.getResource(raw);
            if (resource == null) resource = SourceProvenance.class.getClassLoader().getResource(raw);
            if (resource == null || !"file".equalsIgnoreCase(resource.getProtocol())) return null;

            Path resourcePath = Path.of(resource.toURI()).toAbsolutePath().normalize();
            Path outputRoot = resourcePath;
            for (String ignored : raw.split("/")) {
                outputRoot = outputRoot.getParent();
                if (outputRoot == null) return null;
            }
            Path moduleRoot = moduleRootFromOutputRoot(outputRoot);
            return findSource(moduleRoot, raw, "src/test/resources/", "src/main/resources/");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Path projectRoot(Path workingDirectory) {
        String basedir = System.getProperty("basedir", "");
        if (!basedir.isBlank()) {
            try {
                Path candidate = Path.of(basedir).toAbsolutePath().normalize();
                if (Files.isDirectory(candidate.resolve("src")) || Files.isRegularFile(candidate.resolve("pom.xml"))) {
                    return candidate;
                }
            } catch (Throwable ignored) { }
        }
        return workingDirectory;
    }

    private static Path codeSourceRoot(Class<?> type) {
        try {
            URL url = type.getProtectionDomain().getCodeSource().getLocation();
            if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) return null;
            return Path.of(url.toURI()).toAbsolutePath().normalize();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Path moduleRootFromOutputRoot(Path outputRoot) {
        if (outputRoot == null || outputRoot.getFileName() == null) return null;
        String outputName = outputRoot.getFileName().toString();
        Path target = outputRoot.getParent();
        if (target == null || target.getFileName() == null || !"target".equals(target.getFileName().toString())) return null;
        if (!"test-classes".equals(outputName) && !"classes".equals(outputName)) return null;
        return target.getParent();
    }

    private static Path findSource(Path root, String relative, String... prefixes) {
        if (root == null) return null;
        for (String prefix : prefixes) {
            Path candidate = root.resolve(prefix + relative).normalize();
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static Path findNestedSource(Path repositoryRoot, String relative, String... prefixes) {
        if (repositoryRoot == null) return null;
        for (String prefix : prefixes) {
            Path suffix = Path.of(prefix + relative).normalize();
            try (var paths = Files.find(
                    repositoryRoot,
                    10,
                    (path, attributes) -> attributes.isRegularFile() && path.normalize().endsWith(suffix)
            )) {
                Path match = paths.findFirst().orElse(null);
                if (match != null) return match.toAbsolutePath().normalize();
            } catch (IOException ignored) { }
        }
        return null;
    }

    private Path findLocalSource(String relative) {
        if (consumerRoot == null || !isPickleballRemote(consumer.remote())) return null;
        Path candidate = consumerRoot.resolve(relative).normalize();
        return Files.isRegularFile(candidate) ? candidate : null;
    }

    private String relativeToConsumer(Path path) {
        if (consumerRoot == null || path == null) return "";
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(consumerRoot)) return "";
            return consumerRoot.relativize(normalized).toString().replace('\\', '/');
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isPickleballClass(Class<?> type) {
        try {
            URL framework = SourceProvenance.class.getProtectionDomain().getCodeSource().getLocation();
            URL other = type.getProtectionDomain().getCodeSource().getLocation();
            if (framework != null && other != null) return framework.equals(other);
        } catch (Throwable ignored) { }
        return type.getName().startsWith("tools.dscode.") || type.getName().startsWith("io.cucumber.core.runner.");
    }

    private static String codeSourceName(Class<?> type) {
        try {
            URL url = type.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return "";
            Path path = Path.of(url.toURI());
            Path name = path.getFileName();
            return name == null ? "" : name.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String classBinarySha256(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            if (in == null) return "";
            return sha256(in.readAllBytes());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static BuildInfo loadBuildInfo() {
        Properties properties = new Properties();
        try (InputStream in = SourceProvenance.class.getClassLoader().getResourceAsStream(BUILD_INFO)) {
            if (in != null) properties.load(in);
        } catch (IOException ignored) { }
        Repository repository = new Repository(
                "pickleball",
                "pickleball",
                value(properties, "repositoryName", "pickleball"),
                sanitizeRemote(value(properties, "remote", "https://github.com/ds-code-t/pickleball.git")),
                webUrl(sanitizeRemote(value(properties, "remote", "https://github.com/ds-code-t/pickleball.git"))),
                value(properties, "branch", ""),
                value(properties, "commit", ""),
                value(properties, "commitMessage", ""),
                Boolean.parseBoolean(value(properties, "dirty", "false")),
                !value(properties, "commit", "").isBlank() && !Boolean.parseBoolean(value(properties, "dirty", "false")),
                value(properties, "workingTreeDiffHash", ""),
                value(properties, "version", ""),
                ""
        );
        return new BuildInfo(repository, properties);
    }

    private static String frameworkVersion(BuildInfo buildInfo) {
        String version = value(buildInfo.properties, "version", "");
        if (!version.isBlank()) return version;
        Package pkg = SourceProvenance.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) return pkg.getImplementationVersion();
        String codeSource = codeSourceName(SourceProvenance.class);
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("pickleball-([^/\\\\]+?)\\.jar").matcher(codeSource);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private static String frameworkArtifactSha256() {
        try {
            URL url = SourceProvenance.class.getProtectionDomain().getCodeSource().getLocation();
            if (url == null) return "";
            Path path = Path.of(url.toURI());
            return Files.isRegularFile(path) ? sha256(path) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static GitRepository inspectGitRepository(Path workingDirectory) {
        String rootText = git(workingDirectory, "rev-parse", "--show-toplevel");
        if (rootText.isBlank()) return null;
        Path root;
        try {
            root = Path.of(rootText).toAbsolutePath().normalize();
        } catch (Throwable ignored) {
            return null;
        }
        String remote = sanitizeRemote(git(root, "config", "--get", "remote.origin.url"));
        String branch = git(root, "symbolic-ref", "--short", "-q", "HEAD");
        if (branch.isBlank()) branch = git(root, "rev-parse", "--abbrev-ref", "HEAD");
        String commit = git(root, "rev-parse", "HEAD");
        String message = git(root, "log", "-1", "--pretty=%B");
        String status = git(root, "status", "--porcelain", "--untracked-files=normal");
        boolean dirty = !status.isBlank();
        String diffHash = dirty ? workingTreeDiffHash(root, status) : "";
        return new GitRepository(root, remote, branch, commit, message, dirty, diffHash);
    }

    private static String workingTreeDiffHash(Path root, String status) {
        String unstaged = git(root, "diff", "--binary");
        String staged = git(root, "diff", "--cached", "--binary", "HEAD");
        StringBuilder untracked = new StringBuilder();
        String files = git(root, "ls-files", "--others", "--exclude-standard");
        for (String line : files.lines().toList()) {
            if (line.isBlank()) continue;
            Path file = root.resolve(line).normalize();
            if (Files.isRegularFile(file)) untracked.append(line).append('=').append(sha256(file)).append('\n');
        }
        return sha256((status + "\n" + unstaged + "\n" + staged + "\n" + untracked).getBytes(StandardCharsets.UTF_8));
    }

    private static String git(Path root, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process started = process;
            CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                try {
                    return started.getInputStream().readAllBytes();
                } catch (IOException ignored) {
                    return new byte[0];
                }
            });
            if (!process.waitFor(GIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                output.cancel(true);
                return "";
            }
            String text = new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            return process.exitValue() == 0 ? text : "";
        } catch (Throwable ignored) {
            if (process != null) process.destroyForcibly();
            return "";
        }
    }

    private static String sanitizeRemote(String remote) {
        if (remote == null || remote.isBlank()) return "";
        String value = remote.trim();
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() != null && uri.getHost() != null && uri.getUserInfo() != null) {
                StringBuilder clean = new StringBuilder(uri.getScheme()).append("://").append(uri.getHost());
                if (uri.getPort() >= 0) clean.append(':').append(uri.getPort());
                if (uri.getRawPath() != null) clean.append(uri.getRawPath());
                if (uri.getRawQuery() != null) clean.append('?').append(uri.getRawQuery());
                if (uri.getRawFragment() != null) clean.append('#').append(uri.getRawFragment());
                return clean.toString();
            }
        } catch (Throwable ignored) { }
        return value;
    }

    private static boolean isPickleballRemote(String remote) {
        String value = remote == null ? "" : remote.toLowerCase(Locale.ROOT);
        return value.contains("ds-code-t/pickleball") || value.endsWith("/pickleball.git") || value.endsWith(":pickleball.git");
    }

    private static String repositoryName(String remote, Path root) {
        String candidate = remote == null ? "" : remote.trim();
        if (!candidate.isBlank()) {
            candidate = candidate.replace('\\', '/');
            int slash = Math.max(candidate.lastIndexOf('/'), candidate.lastIndexOf(':'));
            if (slash >= 0) candidate = candidate.substring(slash + 1);
            if (candidate.endsWith(".git")) candidate = candidate.substring(0, candidate.length() - 4);
        }
        if (!candidate.isBlank()) return candidate;
        return root == null || root.getFileName() == null ? "unknown" : root.getFileName().toString();
    }

    private static String webUrl(String remote) {
        if (remote == null || remote.isBlank()) return "";
        String value = remote.trim();
        if (value.startsWith("git@") && value.contains(":")) {
            int at = value.indexOf('@');
            int colon = value.indexOf(':', at);
            value = "https://" + value.substring(at + 1, colon) + "/" + value.substring(colon + 1);
        } else if (value.startsWith("ssh://")) {
            try {
                URI uri = URI.create(value);
                if (uri.getHost() != null) {
                    value = "https://" + uri.getHost() + (uri.getRawPath() == null ? "" : uri.getRawPath());
                }
            } catch (Throwable ignored) { }
        }
        if (value.endsWith(".git")) value = value.substring(0, value.length() - 4);
        return value.startsWith("http://") || value.startsWith("https://") ? value : "";
    }

    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return hex(digest.digest());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] hash) {
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format(Locale.ROOT, "%02x", b));
        return out.toString();
    }

    private static String value(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String find(Map<String, String> config, String key) {
        if (config == null) return null;
        return config.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static Object emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record BuildInfo(Repository repository, Properties properties) { }

    private record GitRepository(
            Path root,
            String remote,
            String branch,
            String commit,
            String commitMessage,
            boolean dirty,
            String diffHash
    ) {
        Repository repository(String role) {
            return new Repository(
                    role,
                    role,
                    repositoryName(remote, root),
                    sanitizeRemote(remote),
                    webUrl(sanitizeRemote(remote)),
                    branch == null ? "" : branch,
                    commit == null ? "" : commit,
                    commitMessage == null ? "" : commitMessage,
                    dirty,
                    !dirty && commit != null && !commit.isBlank(),
                    diffHash == null ? "" : diffHash,
                    "",
                    ""
            );
        }
    }

    public record Repository(
            String id,
            String role,
            String name,
            String remote,
            String webUrl,
            String branch,
            String commit,
            String commitMessage,
            boolean dirty,
            boolean reproducibleFromGit,
            String workingTreeDiffHash,
            String version,
            String artifactSha256
    ) {
        static Repository unavailable(String role) {
            return new Repository(role, role, "unknown", "", "", "", "", "", false, false, "", "", "");
        }

        boolean available() {
            return !commit.isBlank() || !remote.isBlank() || !version.isBlank();
        }

        Repository withArtifact(String version, String artifactSha256) {
            return new Repository(id, role, name, remote, webUrl, branch, commit, commitMessage, dirty,
                    reproducibleFromGit, workingTreeDiffHash,
                    version == null ? "" : version,
                    artifactSha256 == null ? "" : artifactSha256);
        }

        Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("role", role);
            map.put("name", name);
            map.put("remote", emptyToNull(remote));
            map.put("webUrl", emptyToNull(webUrl));
            map.put("branch", emptyToNull(branch));
            map.put("commit", emptyToNull(commit));
            map.put("commitMessage", emptyToNull(commitMessage));
            map.put("dirty", dirty);
            map.put("reproducibleFromGit", reproducibleFromGit);
            map.put("workingTreeDiffHash", emptyToNull(workingTreeDiffHash));
            map.put("version", emptyToNull(version));
            map.put("artifactSha256", emptyToNull(artifactSha256));
            return map;
        }
    }
}
