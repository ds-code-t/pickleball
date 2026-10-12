package tools.dscode.common.resources;

import tools.dscode.control.protocol.PickleballPaths;
import tools.dscode.control.protocol.PickleballPaths.Kind;
import tools.dscode.control.protocol.PickleballPaths.Parsed;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves one configured Pickleball path for a normal run and for the
 * private-run config copy. Both callers use {@link PickleballPaths}.
 */
public final class ResourceLocations {
    public record Location(Kind kind, String classpathResource, Path file) {
    }

    private ResourceLocations() {
    }

    public static Path workingDirectory() {
        return Path.of("").toAbsolutePath().normalize();
    }

    public static Location locate(String configured, Path workingDirectory) {
        Parsed parsed = PickleballPaths.parse(configured);
        if (parsed.kind() == Kind.FILE_SYSTEM) {
            return new Location(Kind.FILE_SYSTEM, null, PickleballPaths.toFile(parsed, workingDirectory));
        }
        return new Location(Kind.CLASSPATH, parsed.classpathResource(), null);
    }

    /**
     * The directory to read or copy. A missing directory returns null.
     * A classpath resource inside a jar returns null so the caller can copy
     * the jar entry itself.
     */
    public static Path existingDirectory(String configured, Path workingDirectory) {
        Location location = locate(configured, workingDirectory);
        if (location.kind() == Kind.FILE_SYSTEM) {
            Path file = location.file();
            return file != null && Files.isDirectory(file) ? file : null;
        }
        return classpathDirectory(location.classpathResource());
    }

    public static Path classpathDirectory(String resource) {
        String name = PickleballPaths.stripToResource(resource);
        if (name.isBlank()) {
            return null;
        }
        URL url;
        try {
            url = loader().getResource(name);
        } catch (RuntimeException ignored) {
            return null;
        }
        if (url == null || !"file".equalsIgnoreCase(url.getProtocol())) {
            return null;
        }
        try {
            Path path = Path.of(url.toURI());
            return Files.isDirectory(path) ? path.toAbsolutePath().normalize() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ClassLoader loader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ResourceLocations.class.getClassLoader() : loader;
    }
}
