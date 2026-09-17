package tools.dscode.control.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maven-order Pickleball version comparison. SNAPSHOT of the same numeric
 * version sorts before the release so "latest" prefers a release.
 *
 * <p>JDK-only: used by the outer-jar launcher hop and {@code .pickleball} store.
 */
public final class PickleballVersion implements Comparable<PickleballVersion> {
    private static final Pattern JAR_NAME =
            Pattern.compile("^pickleball-(.+)\\.jar$", Pattern.CASE_INSENSITIVE);
    private static final String SNAPSHOT = "SNAPSHOT";

    private final String raw;
    private final List<Integer> numeric;
    private final String qualifier;
    private final boolean snapshot;

    private PickleballVersion(String raw, List<Integer> numeric, String qualifier, boolean snapshot) {
        this.raw = raw;
        this.numeric = List.copyOf(numeric);
        this.qualifier = qualifier;
        this.snapshot = snapshot;
    }

    public static PickleballVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Pickleball version is blank.");
        }
        String value = raw.trim();
        String numericPart = value;
        String qualifier = "";
        int dash = value.indexOf('-');
        if (dash >= 0) {
            numericPart = value.substring(0, dash);
            qualifier = value.substring(dash + 1);
        }
        List<Integer> numbers = new ArrayList<>();
        for (String part : numericPart.split("\\.")) {
            if (part.isEmpty()) continue;
            try {
                numbers.add(Integer.parseInt(part));
            } catch (NumberFormatException ignored) {
                qualifier = qualifier.isEmpty() ? part : part + "-" + qualifier;
            }
        }
        if (numbers.isEmpty()) numbers.add(0);
        boolean snapshot = qualifier.toUpperCase(Locale.ROOT).contains(SNAPSHOT);
        return new PickleballVersion(value, numbers, qualifier, snapshot);
    }

    public static Optional<PickleballVersion> tryParse(String raw) {
        if (raw == null || raw.isBlank() || !PickleballLocalLayout.isSafeVersion(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(parse(raw));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public String raw() {
        return raw;
    }

    public boolean snapshot() {
        return snapshot;
    }

    public static Comparator<PickleballVersion> newestFirst() {
        return Comparator.<PickleballVersion>naturalOrder().reversed();
    }

    @Override
    public int compareTo(PickleballVersion other) {
        if (other == null) return 1;
        int length = Math.max(numeric.size(), other.numeric.size());
        for (int i = 0; i < length; i++) {
            int left = i < numeric.size() ? numeric.get(i) : 0;
            int right = i < other.numeric.size() ? other.numeric.get(i) : 0;
            int compared = Integer.compare(left, right);
            if (compared != 0) return compared;
        }
        if (snapshot != other.snapshot) return snapshot ? -1 : 1;
        return qualifier.compareToIgnoreCase(other.qualifier);
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof PickleballVersion other && raw.equals(other.raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    /**
     * Version of the currently executing Pickleball code. Implementation-Version,
     * then {@code META-INF/pickleball-build.properties}, then the jar filename.
     */
    public static String running(Class<?> type) {
        Class<?> source = type == null ? PickleballVersion.class : type;
        Package packageInfo = source.getPackage();
        if (packageInfo != null && packageInfo.getImplementationVersion() != null
                && !packageInfo.getImplementationVersion().isBlank()) {
            return packageInfo.getImplementationVersion().trim();
        }
        try (InputStream input = source.getClassLoader()
                .getResourceAsStream("META-INF/pickleball-build.properties")) {
            if (input != null) {
                Properties properties = new Properties();
                properties.load(input);
                for (String key : List.of("pickleball.version", "version", "build.version", "artifact.version")) {
                    String value = properties.getProperty(key);
                    if (value != null && !value.isBlank()) return value.trim();
                }
            }
        } catch (IOException ignored) {
            // Filename is the final fallback.
        }
        String artifact = runningArtifactFile(source);
        Matcher matcher = JAR_NAME.matcher(artifact);
        return matcher.matches() ? matcher.group(1) : "unknown";
    }

    public static String runningArtifactFile(Class<?> type) {
        return runningJar(type).map(Path::getFileName).map(Path::toString).orElse("");
    }

    public static Optional<Path> runningJar(Class<?> type) {
        try {
            if (type == null
                    || type.getProtectionDomain() == null
                    || type.getProtectionDomain().getCodeSource() == null
                    || type.getProtectionDomain().getCodeSource().getLocation() == null) {
                return Optional.empty();
            }
            URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path path = Path.of(location);
            if (Files.isRegularFile(path) && path.getFileName() != null
                    && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                return Optional.of(path.toAbsolutePath().normalize());
            }
            return Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static Optional<String> versionFromJarName(Path jar) {
        if (jar == null || jar.getFileName() == null) return Optional.empty();
        Matcher matcher = JAR_NAME.matcher(jar.getFileName().toString());
        if (!matcher.matches()) return Optional.empty();
        String version = matcher.group(1).trim();
        return PickleballLocalLayout.isSafeVersion(version) ? Optional.of(version) : Optional.empty();
    }
}
