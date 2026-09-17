package tools.dscode.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses the generated Workbench runtime-lib lockfile and fetches those files
 * into the versioned controller lib cache. JDK-only: no Aether, Jackson, JavaFX,
 * or MCP types.
 */
final class WorkbenchRuntimeLibs {
    static final String MANIFEST_RESOURCE = "META-INF/pickleball/workbench-runtime-libs.txt";
    static final String CLASSIFIER_TOKEN = "CLASSIFIER";
    static final String COMPLETENESS_MARKER = ".complete";
    static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    private static final Pattern LOCAL_REPOSITORY =
            Pattern.compile("<localRepository>\\s*([^<]+)\\s*</localRepository>");
    private static final Pattern MIRROR_URL =
            Pattern.compile("<mirror>\\s*[\\s\\S]*?<url>\\s*([^<]+)\\s*</url>", Pattern.CASE_INSENSITIVE);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private WorkbenchRuntimeLibs() {
    }

    /**
     * Must stay identical to {@code JavaFxSupport.platformKey()}. The launcher
     * must not load {@code JavaFxSupport} because that class imports {@code javafx.*}
     * and lives only in the nested controller JAR.
     */
    static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) return "win";
        if (os.contains("mac")) return arm ? "mac-aarch64" : "mac";
        return "linux";
    }

    static Manifest readManifest(Path controllerJar) {
        if (controllerJar == null || !Files.isRegularFile(controllerJar)) {
            throw new IllegalStateException("Workbench controller JAR is missing: " + controllerJar);
        }
        try (JarFile jar = new JarFile(controllerJar.toFile())) {
            var entry = jar.getJarEntry(MANIFEST_RESOURCE);
            if (entry == null) {
                throw new IllegalStateException(
                        "Thin Workbench JAR is missing " + MANIFEST_RESOURCE + ": " + controllerJar
                );
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return parse(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Could not read Workbench runtime-lib manifest from " + controllerJar + ": "
                            + failure.getMessage(),
                    failure
            );
        }
    }

    static Manifest parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Workbench runtime-lib manifest is empty.");
        }
        String version = null;
        boolean formatSeen = false;
        List<Lib> libs = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                String comment = line.substring(1).trim();
                if (comment.startsWith("format=")) {
                    formatSeen = true;
                    String format = comment.substring("format=".length()).trim();
                    if (!"1".equals(format)) {
                        throw new IllegalStateException(
                                "Unsupported Workbench runtime-lib manifest format: " + format
                        );
                    }
                } else if (comment.startsWith("workbench-version=")) {
                    version = comment.substring("workbench-version=".length()).trim();
                }
                continue;
            }
            libs.add(Lib.parse(line));
        }
        if (!formatSeen) {
            throw new IllegalStateException("Workbench runtime-lib manifest is missing format=1.");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("Workbench runtime-lib manifest is missing workbench-version.");
        }
        if (libs.isEmpty()) {
            throw new IllegalStateException("Workbench runtime-lib manifest has no library rows.");
        }
        return new Manifest(version, List.copyOf(libs));
    }

    static Manifest withLaunchClassifier(Manifest manifest, String classifier) {
        List<Lib> substituted = new ArrayList<>(manifest.libs().size());
        for (Lib lib : manifest.libs()) {
            substituted.add(lib.withClassifierToken(classifier));
        }
        return new Manifest(manifest.version(), List.copyOf(substituted));
    }

    static List<Path> resolve(Manifest manifest, Path libCache) {
        Objects.requireNonNull(manifest, "manifest");
        Path cache = libCache.toAbsolutePath().normalize();
        try {
            Files.createDirectories(cache);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not create Workbench lib cache: " + cache, failure);
        }

        List<Path> resolved = new ArrayList<>(manifest.libs().size());
        if (complete(cache, manifest)) {
            for (Lib lib : manifest.libs()) {
                resolved.add(cache.resolve(lib.filename()));
            }
            return List.copyOf(resolved);
        }

        SearchPlan plan = SearchPlan.discover();
        for (Lib lib : manifest.libs()) {
            resolved.add(resolveOne(lib, cache, plan));
        }
        writeCompletenessMarker(cache, manifest);
        return List.copyOf(resolved);
    }

    static Path libCacheForController(Path controllerJar, String version) {
        Path jar = controllerJar.toAbsolutePath().normalize();
        Path shaDir = jar.getParent();
        Path controllerDir = shaDir == null ? null : shaDir.getParent();
        Path workbenchDir = controllerDir == null ? null : controllerDir.getParent();
        if (controllerDir != null
                && "controller".equals(controllerDir.getFileName().toString())
                && workbenchDir != null) {
            return workbenchDir.resolve("lib").resolve(version);
        }
        throw new IllegalStateException(
                "Workbench controller JAR is not on the content-addressed extract path: " + jar
        );
    }

    private static boolean complete(Path cache, Manifest manifest) {
        Path marker = cache.resolve(COMPLETENESS_MARKER);
        if (!Files.isRegularFile(marker)) return false;
        for (Lib lib : manifest.libs()) {
            if (!Files.isRegularFile(cache.resolve(lib.filename()))) return false;
        }
        return true;
    }

    private static void writeCompletenessMarker(Path cache, Manifest manifest) {
        StringBuilder body = new StringBuilder();
        for (Lib lib : manifest.libs()) {
            body.append(lib.filename()).append('\n');
        }
        Path marker = cache.resolve(COMPLETENESS_MARKER);
        Path temporary = cache.resolve(COMPLETENESS_MARKER + ".tmp");
        try {
            Files.writeString(temporary, body.toString(), StandardCharsets.UTF_8);
            moveAtomic(temporary, marker);
        } catch (IOException failure) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best-effort cleanup.
            }
            throw new IllegalStateException("Could not write Workbench lib completeness marker.", failure);
        }
    }

    private static Path resolveOne(Lib lib, Path cache, SearchPlan plan) {
        Path target = cache.resolve(lib.filename());
        if (Files.isRegularFile(target)) return target;

        List<String> searched = new ArrayList<>();
        Path mavenHit = plan.mavenLocal.resolve(lib.mavenRelativePath());
        searched.add("Maven local (" + mavenHit + ")");
        if (copyIfPresent(mavenHit, target)) return target;

        Optional<Path> gradleHit = findGradleArtifact(plan.gradleCache, lib);
        searched.add("Gradle cache (" + plan.gradleCache.resolve(lib.gradleRelativeDir()) + ")");
        if (gradleHit.isPresent() && copyIfPresent(gradleHit.get(), target)) return target;

        for (String mirror : plan.mirrors) {
            String url = joinUrl(mirror, lib.mavenRelativePath().toString().replace('\\', '/'));
            searched.add(url);
            if (download(url, target)) return target;
        }

        String central = joinUrl(MAVEN_CENTRAL, lib.mavenRelativePath().toString().replace('\\', '/'));
        searched.add(central);
        if (download(central, target)) return target;

        throw new IllegalStateException(
                "Could not resolve " + lib.coordinates()
                        + ". Searched: " + String.join("; ", searched)
        );
    }

    private static Optional<Path> findGradleArtifact(Path gradleRoot, Lib lib) {
        Path dir = gradleRoot.resolve(lib.gradleRelativeDir());
        if (!Files.isDirectory(dir)) return Optional.empty();
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> lib.filename().equals(path.getFileName().toString()))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static boolean copyIfPresent(Path source, Path target) {
        if (!Files.isRegularFile(source)) return false;
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomic(temporary, target);
            return Files.isRegularFile(target);
        } catch (IOException failure) {
            return false;
        }
    }

    private static boolean download(String url, Path target) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(HTTP_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "pickleball-workbench-launcher")
                    .GET()
                    .build();
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.createDirectories(target.getParent());
            HttpResponse<Path> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofFile(temporary)
            );
            if (response.statusCode() != 200 || !Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
                Files.deleteIfExists(temporary);
                return false;
            }
            moveAtomic(temporary, target);
            return Files.isRegularFile(target);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private static void moveAtomic(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String joinUrl(String base, String relative) {
        String prefix = base.endsWith("/") ? base : base + "/";
        return prefix + relative;
    }

    record Manifest(String version, List<Lib> libs) {
    }

    record Lib(String group, String artifact, String version, String classifier) {
        static Lib parse(String row) {
            String[] parts = row.split(":");
            if (parts.length != 3 && parts.length != 4) {
                throw new IllegalStateException("Invalid Workbench runtime-lib row: " + row);
            }
            for (String part : parts) {
                if (part == null || part.isBlank()) {
                    throw new IllegalStateException("Invalid Workbench runtime-lib row: " + row);
                }
            }
            String classifier = parts.length == 4 ? parts[3] : null;
            return new Lib(parts[0], parts[1], parts[2], classifier);
        }

        Lib withClassifierToken(String replacement) {
            if (CLASSIFIER_TOKEN.equals(classifier)) {
                return new Lib(group, artifact, version, replacement);
            }
            return this;
        }

        boolean classified() {
            return classifier != null && !classifier.isBlank();
        }

        String filename() {
            return classified()
                    ? artifact + "-" + version + "-" + classifier + ".jar"
                    : artifact + "-" + version + ".jar";
        }

        String coordinates() {
            return classified()
                    ? group + ":" + artifact + ":" + version + ":" + classifier
                    : group + ":" + artifact + ":" + version;
        }

        Path mavenRelativePath() {
            return Path.of(group.replace('.', '/'), artifact, version, filename());
        }

        Path gradleRelativeDir() {
            return Path.of(group, artifact, version);
        }
    }

    private record SearchPlan(Path mavenLocal, Path gradleCache, List<String> mirrors) {
        static SearchPlan discover() {
            Path home = Path.of(System.getProperty("user.home", ""));
            Path settings = home.resolve(".m2").resolve("settings.xml");
            Path mavenLocal = home.resolve(".m2").resolve("repository");
            List<String> mirrors = new ArrayList<>();
            if (Files.isRegularFile(settings)) {
                try {
                    String xml = Files.readString(settings, StandardCharsets.UTF_8);
                    Matcher local = LOCAL_REPOSITORY.matcher(xml);
                    if (local.find()) {
                        String configured = expandHome(local.group(1).trim());
                        if (!configured.isBlank()) mavenLocal = Path.of(configured);
                    }
                    Matcher mirror = MIRROR_URL.matcher(xml);
                    Set<String> unique = new LinkedHashSet<>();
                    while (mirror.find()) {
                        String url = mirror.group(1).trim();
                        if (!url.isBlank()) unique.add(url);
                    }
                    mirrors.addAll(unique);
                } catch (IOException ignored) {
                    // Fall back to defaults.
                }
            }
            Path gradleCache = home.resolve(".gradle")
                    .resolve("caches")
                    .resolve("modules-2")
                    .resolve("files-2.1");
            return new SearchPlan(mavenLocal, gradleCache, List.copyOf(mirrors));
        }

        private static String expandHome(String path) {
            String home = System.getProperty("user.home", "");
            return path.replace("${user.home}", home).replace("${USER_HOME}", home);
        }
    }
}
