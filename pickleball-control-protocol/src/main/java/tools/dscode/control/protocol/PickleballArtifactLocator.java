package tools.dscode.control.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds published {@code tools.dscode:pickleball} jars in Maven local and the
 * Gradle cache. JDK-only; no Aether.
 *
 * <p>Maven local is the fast hop once a version is known. When no version is
 * pinned, the newest non-SNAPSHOT release wins and SNAPSHOT is last.
 */
public final class PickleballArtifactLocator {
    public static final String GROUP_ID = "tools.dscode";
    public static final String ARTIFACT_ID = "pickleball";

    private static final Pattern LOCAL_REPOSITORY =
            Pattern.compile("<localRepository>\\s*([^<]+)\\s*</localRepository>");

    private PickleballArtifactLocator() {
    }

    public record Repositories(Path mavenLocal, Path gradleCache) {
        public Repositories {
            mavenLocal = mavenLocal == null ? defaultMavenLocal() : mavenLocal.toAbsolutePath().normalize();
            gradleCache = gradleCache == null ? defaultGradleCache() : gradleCache.toAbsolutePath().normalize();
        }

        public static Repositories discover() {
            return new Repositories(discoverMavenLocal(), defaultGradleCache());
        }
    }

    public record Located(String version, Path jar) {
    }

    public static Path defaultMavenLocal() {
        return Path.of(System.getProperty("user.home", "")).resolve(".m2").resolve("repository");
    }

    public static Path defaultGradleCache() {
        return Path.of(System.getProperty("user.home", ""))
                .resolve(".gradle")
                .resolve("caches")
                .resolve("modules-2")
                .resolve("files-2.1");
    }

    public static Path discoverMavenLocal() {
        Path settings = Path.of(System.getProperty("user.home", "")).resolve(".m2").resolve("settings.xml");
        Path mavenLocal = defaultMavenLocal();
        if (!Files.isRegularFile(settings)) return mavenLocal;
        try {
            String xml = Files.readString(settings, StandardCharsets.UTF_8);
            Matcher local = LOCAL_REPOSITORY.matcher(xml);
            if (local.find()) {
                String configured = expandHome(local.group(1).trim());
                if (!configured.isBlank()) mavenLocal = Path.of(configured);
            }
        } catch (IOException ignored) {
            // Fall back to ~/.m2/repository.
        }
        return mavenLocal.toAbsolutePath().normalize();
    }

    public static Optional<Path> find(String version) {
        return find(version, Repositories.discover());
    }

    public static Optional<Path> find(String version, Repositories repositories) {
        if (!PickleballLocalLayout.isSafeVersion(version) || repositories == null) return Optional.empty();
        Path maven = mavenJar(repositories.mavenLocal(), version);
        if (Files.isRegularFile(maven)) return Optional.of(maven);
        return findGradleJar(repositories.gradleCache(), version);
    }

    public static Optional<Located> findLatest() {
        return findLatest(Repositories.discover());
    }

    public static Optional<Located> findLatest(Repositories repositories) {
        List<Located> found = list(repositories);
        Optional<Located> release = found.stream()
                .filter(located -> !PickleballVersion.parse(located.version()).snapshot())
                .max(Comparator.comparing(located -> PickleballVersion.parse(located.version())));
        if (release.isPresent()) return release;
        return found.stream()
                .max(Comparator.comparing(located -> PickleballVersion.parse(located.version())));
    }

    public static List<Located> list(Repositories repositories) {
        List<Located> found = new ArrayList<>();
        if (repositories == null) return List.of();
        addMavenVersions(repositories.mavenLocal(), found);
        addGradleVersions(repositories.gradleCache(), found);
        return List.copyOf(found);
    }

    public static Path mavenJar(Path mavenLocal, String version) {
        String safe = PickleballLocalLayout.requireSafeVersion(version);
        return mavenLocal.toAbsolutePath().normalize()
                .resolve(GROUP_ID.replace('.', '/'))
                .resolve(ARTIFACT_ID)
                .resolve(safe)
                .resolve(ARTIFACT_ID + "-" + safe + ".jar");
    }

    public static Path mavenArtifactDirectory(Path mavenLocal) {
        return mavenLocal.toAbsolutePath().normalize()
                .resolve(GROUP_ID.replace('.', '/'))
                .resolve(ARTIFACT_ID);
    }

    static Optional<Path> findGradleJar(Path gradleCache, String version) {
        Path dir = gradleCache.toAbsolutePath().normalize()
                .resolve(GROUP_ID)
                .resolve(ARTIFACT_ID)
                .resolve(version);
        if (!Files.isDirectory(dir)) return Optional.empty();
        String filename = ARTIFACT_ID + "-" + version + ".jar";
        try (Stream<Path> walk = Files.walk(dir, 2)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> filename.equals(path.getFileName().toString()))
                    .findFirst();
        } catch (IOException ignored) {
            return Optional.empty();
        }
    }

    private static void addMavenVersions(Path mavenLocal, List<Located> found) {
        Path artifact = mavenArtifactDirectory(mavenLocal);
        if (!Files.isDirectory(artifact)) return;
        try (Stream<Path> versions = Files.list(artifact)) {
            versions.filter(Files::isDirectory).forEach(dir -> {
                Path name = dir.getFileName();
                if (name == null) return;
                String version = name.toString();
                if (!PickleballLocalLayout.isSafeVersion(version)) return;
                Path jar = mavenJar(mavenLocal, version);
                if (Files.isRegularFile(jar) && !contains(found, version)) {
                    found.add(new Located(version, jar.toAbsolutePath().normalize()));
                }
            });
        } catch (IOException ignored) {
            // Ignore unreadable caches.
        }
    }

    private static void addGradleVersions(Path gradleCache, List<Located> found) {
        Path artifact = gradleCache.toAbsolutePath().normalize()
                .resolve(GROUP_ID)
                .resolve(ARTIFACT_ID);
        if (!Files.isDirectory(artifact)) return;
        try (Stream<Path> versions = Files.list(artifact)) {
            versions.filter(Files::isDirectory).forEach(dir -> {
                Path name = dir.getFileName();
                if (name == null) return;
                String version = name.toString();
                if (!PickleballLocalLayout.isSafeVersion(version) || contains(found, version)) return;
                findGradleJar(gradleCache, version).ifPresent(jar ->
                        found.add(new Located(version, jar.toAbsolutePath().normalize()))
                );
            });
        } catch (IOException ignored) {
            // Ignore unreadable caches.
        }
    }

    private static boolean contains(List<Located> found, String version) {
        for (Located located : found) {
            if (located.version().equals(version)) return true;
        }
        return false;
    }

    private static String expandHome(String path) {
        String home = System.getProperty("user.home", "");
        return path.replace("${user.home}", home).replace("${USER_HOME}", home);
    }

    public static boolean looksLikePickleballJar(Path jar) {
        if (jar == null || !Files.isRegularFile(jar) || jar.getFileName() == null) return false;
        String name = jar.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.startsWith("pickleball-") && name.endsWith(".jar") && !name.contains("workbench");
    }
}
